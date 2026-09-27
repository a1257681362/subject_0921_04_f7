package com.evops.photo;

import com.evops.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * 交车照片接口（rental-photo-1）。
 *
 * 上传同步只落原图留底即返回（不卡上传），280px 水印预览在后台生成；
 * 原图仅内部留底、不提供下载，对外只有带网点标识与订单号的水印预览。
 * 写请求同样遵循三件套（X-Operator / X-Request-No / 可选 X-Biz-Time）与入口幂等。
 */
@RestController
@RequestMapping("/api/orders")
public class HandoverPhotoController {

    private final PhotoService photoService;
    private final PhotoBoundaryService boundaryService;

    public HandoverPhotoController(PhotoService photoService, PhotoBoundaryService boundaryService) {
        this.photoService = photoService;
        this.boundaryService = boundaryService;
    }

    /**
     * 边界情况诊断（只读）：参考日（缺省今天）去年同期 ±windowDays 窗口内
     * 租车单/交接照片极端值（张数、地址串长度、油量、金额），对照当前上传限额，
     * 并列新链路上线后实测字节/尺寸/处理耗时极值。定限额前先看这个。
     */
    @GetMapping("/photo-boundaries")
    public ApiResponse<java.util.Map<String, Object>> boundaries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) Integer windowDays) {
        return ApiResponse.ok(boundaryService.report(bizDate, windowDays));
    }

    /** 上传交车照片（多图）：原图留底后立即返回，水印预览后台处理。 */
    @PostMapping("/{id}/photos")
    public ApiResponse<List<HandoverPhoto>> upload(@PathVariable Long id,
                                                   @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(photoService.uploadPhotos(id, files));
    }

    /** 某订单全部照片与处理状态（含处理耗时、失败原因）。 */
    @GetMapping("/{id}/photos")
    public ApiResponse<List<HandoverPhoto>> list(@PathVariable Long id) {
        return ApiResponse.ok(photoService.listPhotos(id));
    }

    /**
     * 下载对外版本——280px 水印预览（JPEG）。
     * 未处理完成/处理失败返回 409 并提示；任何情况下都不提供原图。
     */
    @GetMapping("/{id}/photos/{photoId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id, @PathVariable Long photoId) {
        PhotoService.PhotoView view = photoService.readPreview(id, photoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"handover_" + photoId + "_preview.jpg\"")
                .contentType(MediaType.IMAGE_JPEG)
                .body(view.getBytes());
    }

    /** 手工重处理失败/未处理的照片（DONE 无需重处理，PROCESSING 拒绝）。 */
    @PostMapping("/{id}/photos/{photoId}/reprocess")
    public ApiResponse<HandoverPhoto> reprocess(@PathVariable Long id, @PathVariable Long photoId) {
        return ApiResponse.ok(photoService.reprocess(id, photoId));
    }
}
