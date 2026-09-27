package com.evops.photo;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 边界情况诊断（rental-photo-1）：以某业务日（缺省今天）的去年同期为中心，
 * 统计租车单/交接照片的极端值，并与当前照片上传限额逐项对照。
 *
 * 用途：上线前按真实历史极端值定限额（单张大小、单次张数），避免拍脑袋；
 * 上线后同一接口继续给出新链路实测字节/耗时极值，供回头校准。
 * 历史交接照片只留了逗号分隔地址串，张数按逗号数估算（空白串记 0）。
 */
@Service
public class PhotoBoundaryService {

    /** 去年同期默认取整月（31 天窗口） */
    private static final int DEFAULT_WINDOW_DAYS = 15;
    private static final int MAX_WINDOW_DAYS = 366;
    private static final int TOP_LIMIT = 5;

    private final PhotoBoundaryMapper boundaryMapper;
    private final PhotoProperties properties;

    public PhotoBoundaryService(PhotoBoundaryMapper boundaryMapper, PhotoProperties properties) {
        this.boundaryMapper = boundaryMapper;
        this.properties = properties;
    }

    /**
     * @param referenceDate 参考业务日（缺省今天），去年同期 = 该日减一年
     * @param windowDays    以去年同期为中心的 ± 天数（缺省整月 15 天半径）
     */
    public Map<String, Object> report(LocalDate referenceDate, Integer windowDays) {
        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        int window = windowDays != null ? windowDays : DEFAULT_WINDOW_DAYS;
        if (window < 0 || window > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("windowDays 必须在 0-" + MAX_WINDOW_DAYS + " 之间：" + window);
        }

        LocalDate samePeriod = ref.minusYears(1);
        LocalDateTime from = samePeriod.minusDays(window).atStartOfDay();
        LocalDateTime to = samePeriod.plusDays(window).plusDays(1).atStartOfDay();

        Map<String, Object> handover = boundaryMapper.handoverAggregate(from, to);
        Map<String, Object> order = boundaryMapper.orderAggregate(from, to);
        List<Map<String, Object>> top = boundaryMapper.topPhotoHandovers(from, to, TOP_LIMIT);
        Map<String, Object> photo = boundaryMapper.photoAggregate(from, to);

        long maxPhotoRefs = asLong(handover.get("max_photo_refs"));
        long maxPhotosLength = asLong(handover.get("max_photos_length"));
        long maxOriginalSize = asLong(photo.get("max_original_size"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referenceDate", ref.toString());
        result.put("samePeriodDate", samePeriod.toString());
        result.put("window", from.toLocalDate() + " ~ " + to.toLocalDate().minusDays(1));

        Map<String, Object> lastYear = new LinkedHashMap<>();
        lastYear.put("orderCount", asLong(order.get("order_count")));
        lastYear.put("handoverCount", asLong(handover.get("handover_count")));
        lastYear.put("photoRefs", asLong(handover.get("photo_refs")));
        lastYear.put("maxPhotoRefsPerHandover", maxPhotoRefs);
        lastYear.put("maxPhotosStringLength", maxPhotosLength);
        lastYear.put("fuelReadingMin", asInteger(handover.get("fuel_min")));
        lastYear.put("fuelReadingMax", asInteger(handover.get("fuel_max")));
        lastYear.put("settleAmountMin", (BigDecimal) order.get("settle_min"));
        lastYear.put("settleAmountMax", (BigDecimal) order.get("settle_max"));
        result.put("lastYearSamePeriod", lastYear);

        Map<String, Object> newChain = new LinkedHashMap<>();
        newChain.put("photoCount", asLong(photo.get("photo_count")));
        newChain.put("maxOriginalSizeBytes", maxOriginalSize);
        newChain.put("maxOriginalWidth", asLong(photo.get("max_original_width")));
        newChain.put("maxOriginalHeight", asLong(photo.get("max_original_height")));
        newChain.put("maxProcessingMs", asLong(photo.get("max_processing_ms")));
        result.put("newChainObserved", newChain);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxUploadSizeBytes", properties.getMaxUploadSizeBytes());
        limits.put("maxFilesPerRequest", properties.getMaxFilesPerRequest());
        limits.put("previewWidth", properties.getPreviewWidth());
        limits.put("watermarkAlpha", properties.getWatermarkAlpha());
        result.put("currentLimits", limits);

        Map<String, Object> assessment = new LinkedHashMap<>();
        // 历史只有地址串没有字节数：用同期最多张数对照单次张数上限；
        // 单张大小上限以新链路实测 max_original_size 对照（上线后自动出现）。
        assessment.put("filesPerRequestCoversHistory",
                properties.getMaxFilesPerRequest() >= maxPhotoRefs);
        assessment.put("uploadSizeCoversObserved",
                maxOriginalSize == 0 || properties.getMaxUploadSizeBytes() >= maxOriginalSize);
        assessment.put("note", maxOriginalSize == 0
                ? "新链路尚无实测字节数，单张大小上限暂按配置值执行，上线后用本接口回头校准"
                : "单张大小上限已覆盖同期实测最大值");
        result.put("assessment", assessment);

        result.put("topPhotoHandovers", top);
        return result;
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }
}
