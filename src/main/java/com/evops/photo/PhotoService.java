package com.evops.photo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.branch.Branch;
import com.evops.branch.BranchMapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 交车照片上传与后台处理（rental-photo-1）。
 *
 * 上传同步段只做：校验 → 原图落盘留底 → 插 UPLOADED 记录，随即返回，
 * 缩放/EXIF 摆正/水印全部在 {@link PhotoAsyncConfig#EXECUTOR} 后台完成（不卡上传）。
 * 后台任务在请求事务提交后才调度，避免读不到未提交行；线程内透传操作人上下文，
 * 状态 UPLOADED → PROCESSING → DONE/FAILED，processing_ms 记录每张耗时。
 *
 * 原图仅内部留底：本服务不提供任何原图下载出口，对外只有 DONE 后的水印预览。
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    /** 水印文本上限（超长截断，防止极端网点名撑爆预览图） */
    private static final int WATERMARK_MAX_LEN = 120;
    private static final int ERROR_MAX_LEN = 1000;

    private final HandoverPhotoMapper photoMapper;
    private final RentalOrderMapper orderMapper;
    private final BranchMapper branchMapper;
    private final PhotoProcessor processor;
    private final PhotoProperties properties;
    private final ThreadPoolTaskExecutor executor;

    public PhotoService(HandoverPhotoMapper photoMapper,
                        RentalOrderMapper orderMapper,
                        BranchMapper branchMapper,
                        PhotoProcessor processor,
                        PhotoProperties properties,
                        @Qualifier(PhotoAsyncConfig.EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.photoMapper = photoMapper;
        this.orderMapper = orderMapper;
        this.branchMapper = branchMapper;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
    }

    // ---- 上传 ----

    /**
     * 同步上传多张交车照片：原图落盘留底 + UPLOADED 记录同事务，提交后后台处理。
     * 任一文件校验不过整单拒绝（400），不落任何文件/记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HandoverPhoto> uploadPhotos(Long orderId, List<MultipartFile> files) {
        RentalOrder order = requireOrder(orderId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("上传照片不能为空");
        }
        if (files.size() > properties.getMaxFilesPerRequest()) {
            throw new IllegalArgumentException("单次最多上传 " + properties.getMaxFilesPerRequest() + " 张照片，本次："
                    + files.size() + "（限额依据见 /api/orders/photo-boundaries）");
        }
        // 先把全部文件校验完，再统一落盘，避免收一半拒一半
        List<String> extensions = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            extensions.add(validateAndExtension(file));
        }

        String dayDir = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        List<Path> writtenFiles = new ArrayList<>();
        List<HandoverPhoto> rows = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                byte[] bytes;
                try {
                    bytes = file.getBytes();
                } catch (IOException ex) {
                    throw new IllegalArgumentException("上传文件读取失败：" + safeName(file));
                }
                String fileName = "o_" + orderId + "_" + UUID.randomUUID().toString().replace("-", "")
                        + "." + extensions.get(i);
                Path target = properties.originalDir().resolve(dayDir).resolve(fileName);
                try {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                } catch (IOException ex) {
                    throw new BizException("交车照片落盘失败：" + safeName(file));
                }
                writtenFiles.add(target);

                HandoverPhoto photo = new HandoverPhoto();
                photo.setOrderId(orderId);
                photo.setOriginalFileName(safeName(file));
                photo.setOriginalPath(relativeOf(target));
                photo.setOriginalSize((long) bytes.length);
                photo.setStatus(HandoverPhoto.UPLOADED);
                photo.setAttempts(0);
                photoMapper.insert(photo);
                rows.add(photo);
            }
        } catch (RuntimeException ex) {
            // 事务会回滚，已写文件尽力清理，不留无记录孤儿文件
            cleanupBestEffort(writtenFiles);
            throw ex;
        }

        // 必须等事务提交后再丢后台：worker 才读得到刚插入的行
        final List<HandoverPhoto> scheduled = new ArrayList<>(rows);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (HandoverPhoto photo : scheduled) {
                    scheduleProcessing(photo.getId());
                }
            }
        });
        log.info("交车照片已上传留底，订单 {} 共 {} 张，后台水印处理已排队：{}",
                order.getOrderNo(), rows.size(), orderId);
        return rows;
    }

    // ---- 查询 ----

    public List<HandoverPhoto> listPhotos(Long orderId) {
        requireOrder(orderId);
        return photoMapper.selectList(new LambdaQueryWrapper<HandoverPhoto>()
                .eq(HandoverPhoto::getOrderId, orderId)
                .orderByAsc(HandoverPhoto::getId));
    }

    /** 预览图字节：仅 DONE 可取；UPLOADED/PROCESSING 尚未就绪 409，FAILED 带失败原因 409。 */
    public PhotoView readPreview(Long orderId, Long photoId) {
        HandoverPhoto photo = requirePhotoOfOrder(orderId, photoId);
        if (HandoverPhoto.DONE.equals(photo.getStatus())) {
            Path path = Paths.get(properties.getStorageDir()).resolve(photo.getPreviewPath());
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException ex) {
                throw new BizException("预览文件丢失或不可读，请调用重处理：photoId=" + photoId);
            }
            return new PhotoView(photo, bytes);
        }
        if (HandoverPhoto.FAILED.equals(photo.getStatus())) {
            throw new BizException("预览处理失败，请重处理：" + nullable(photo.getErrorDetail()));
        }
        throw new BizException("预览尚未处理完成，当前状态：" + photo.getStatus() + "，请稍后查询处理状态");
    }

    /** 手工重处理：仅 UPLOADED/FAILED 可重处理；PROCESSING 拒绝，DONE 无需重处理。 */
    public HandoverPhoto reprocess(Long orderId, Long photoId) {
        HandoverPhoto photo = requirePhotoOfOrder(orderId, photoId);
        if (HandoverPhoto.DONE.equals(photo.getStatus())) {
            throw new BizException("预览已处理完成，无需重处理");
        }
        if (HandoverPhoto.PROCESSING.equals(photo.getStatus())) {
            throw new BizException("照片正在处理中，请稍后查询处理状态");
        }
        scheduleProcessing(photoId);
        return photoMapper.selectById(photoId);
    }

    // ---- 后台处理 ----

    /**
     * 调度单张照片的后台处理。处理池满则拒绝（记录留 UPLOADED，启动自愈/重处理兜底），
     * 不影响上传接口。在请求线程内调用，捕获操作人/业务时间/请求号透传给后台线程。
     */
    public void scheduleProcessing(Long photoId) {
        final RequestContext.Ctx ctx = currentContextSnapshot();
        try {
            executor.execute(() -> {
                RequestContext.set(ctx.getOperator(), ctx.getBizTime(), ctx.getRequestNo());
                try {
                    processOne(photoId);
                } finally {
                    RequestContext.clear();
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("交车照片后台处理排队已满，photoId={} 保持 UPLOADED，等待自愈/重处理", photoId);
        }
    }

    /** 启动自愈/无请求上下文的内部调度：以系统身份执行，不依赖请求头。 */
    public void scheduleProcessingFromBootstrap(Long photoId) {
        try {
            executor.execute(() -> {
                RequestContext.set("SYSTEM", LocalDateTime.now(), "BOOTSTRAP-PHOTO-" + photoId);
                try {
                    processOne(photoId);
                } finally {
                    RequestContext.clear();
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("启动自愈：交车照片处理排队已满，photoId={} 本次跳过", photoId);
        }
    }

    /**
     * 处理单张：CAS 抢占（只有一个 worker 能从 UPLOADED/FAILED 推进到 PROCESSING）
     * → 读原图 → EXIF 摆正 + 280px 缩放 + 水印 → 预览落盘 → DONE；任何异常落 FAILED。
     */
    public void processOne(Long photoId) {
        int claimed = photoMapper.update(null, new LambdaUpdateWrapper<HandoverPhoto>()
                .eq(HandoverPhoto::getId, photoId)
                .in(HandoverPhoto::getStatus, HandoverPhoto.UPLOADED, HandoverPhoto.FAILED)
                .set(HandoverPhoto::getStatus, HandoverPhoto.PROCESSING)
                .setSql("attempts = attempts + 1"));
        if (claimed == 0) {
            return; // 已被其他 worker 抢占或已完成
        }

        HandoverPhoto photo = photoMapper.selectById(photoId);
        long start = System.nanoTime();
        Path previewTarget = null;
        try {
            RentalOrder order = orderMapper.selectById(photo.getOrderId());
            if (order == null) {
                throw new IOException("关联租车单不存在：" + photo.getOrderId());
            }
            Path original = Paths.get(properties.getStorageDir()).resolve(photo.getOriginalPath());
            byte[] source = Files.readAllBytes(original);

            String watermark = buildWatermark(order);
            PhotoProcessor.Result result = processor.process(source, watermark,
                    properties.getPreviewWidth(), properties.getWatermarkAlpha(),
                    properties.getJpegQuality());

            String dayDir = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            previewTarget = properties.previewDir().resolve(dayDir)
                    .resolve("p_" + photoId + ".jpg");
            Files.createDirectories(previewTarget.getParent());
            Files.write(previewTarget, result.jpeg);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            photo.setStatus(HandoverPhoto.DONE);
            photo.setPreviewPath(relativeOf(previewTarget));
            photo.setPreviewSize((long) result.jpeg.length);
            photo.setPreviewWidth(result.width);
            photo.setPreviewHeight(result.height);
            photo.setOriginalWidth(result.orientedWidth);
            photo.setOriginalHeight(result.orientedHeight);
            photo.setExifOrientation(result.exifOrientation);
            photo.setWatermarkText(watermark);
            photo.setProcessingMs(elapsedMs);
            photo.setErrorDetail(null);
            photoMapper.updateById(photo);
            log.info("交车照片处理完成 photoId={} 订单={} {}x{} exif={} 耗时={}ms",
                    photoId, order.getOrderNo(), result.width, result.height,
                    result.exifOrientation, elapsedMs);
        } catch (Exception ex) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String reason = truncate(ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? "：" + ex.getMessage() : ""), ERROR_MAX_LEN);
            photo.setStatus(HandoverPhoto.FAILED);
            photo.setProcessingMs(elapsedMs);
            photo.setErrorDetail(reason);
            photoMapper.updateById(photo);
            if (previewTarget != null) {
                try {
                    Files.deleteIfExists(previewTarget);
                } catch (IOException ignore) {
                    // 留个未登记文件不影响正确性，忽略
                }
            }
            log.warn("交车照片处理失败 photoId={} 耗时={}ms 原因={}", photoId, elapsedMs, reason);
        }
    }

    // ---- 内部辅助 ----

    private String buildWatermark(RentalOrder order) {
        String branchLabel;
        String code = order.getBranchCode();
        if (code == null || code.trim().isEmpty()) {
            branchLabel = "未分派网点";
        } else {
            Branch branch = branchMapper.selectOne(new LambdaQueryWrapper<Branch>()
                    .eq(Branch::getBranchCode, code.trim()));
            String name = branch != null && branch.getName() != null ? branch.getName() : "";
            branchLabel = name.isEmpty() ? code.trim() : code.trim() + "·" + name;
        }
        return truncate("网点:" + branchLabel + " | 单号:" + order.getOrderNo(), WATERMARK_MAX_LEN);
    }

    private String validateAndExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("存在空照片文件，请检查后重新上传");
        }
        if (file.getSize() > properties.getMaxUploadSizeBytes()) {
            throw new IllegalArgumentException("照片 " + safeName(file) + " 大小 "
                    + file.getSize() + " 字节超过上限 " + properties.getMaxUploadSizeBytes()
                    + " 字节（上限依据见 /api/orders/photo-boundaries）");
        }
        String ext = extensionOf(safeName(file));
        if (!properties.getAllowedExtensions().contains(ext)) {
            throw new IllegalArgumentException("照片 " + safeName(file) + " 类型 " + ext
                    + " 不被支持，仅支持 " + properties.getAllowedExtensions());
        }
        return ext;
    }

    private RentalOrder requireOrder(Long orderId) {
        RentalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("租车单不存在：" + orderId);
        }
        return order;
    }

    private HandoverPhoto requirePhotoOfOrder(Long orderId, Long photoId) {
        requireOrder(orderId);
        HandoverPhoto photo = photoMapper.selectById(photoId);
        if (photo == null || !orderId.equals(photo.getOrderId())) {
            throw new BizException("交车照片不存在：" + photoId);
        }
        return photo;
    }

    private RequestContext.Ctx currentContextSnapshot() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return new RequestContext.Ctx("SYSTEM", LocalDateTime.now(), "SYSTEM-PHOTO");
        }
    }

    private String relativeOf(Path path) {
        return properties.baseDir().relativize(path).toString().replace('\\', '/');
    }

    private void cleanupBestEffort(List<Path> files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignore) {
                // 尽力清理
            }
        }
    }

    private static String safeName(MultipartFile file) {
        String name = file != null ? file.getOriginalFilename() : null;
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return name.trim();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nullable(String s) {
        return s == null ? "" : s;
    }

    /** 预览读取结果：记录 + 字节。 */
    public static final class PhotoView {
        private final HandoverPhoto photo;
        private final byte[] bytes;

        public PhotoView(HandoverPhoto photo, byte[] bytes) {
            this.photo = photo;
            this.bytes = bytes;
        }

        public HandoverPhoto getPhoto() { return photo; }
        public byte[] getBytes() { return bytes; }
    }
}
