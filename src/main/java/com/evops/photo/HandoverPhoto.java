package com.evops.photo;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 交车照片处理记录（rental-photo-1）。
 *
 * 原图仅内部留底（{@code originalPath} 不对任何外部接口开放）；
 * 唯一可对外的是 280px 水印预览 {@code previewPath}：
 * 上传同步只落原图留底并立即返回，缩放/EXIF 摆正/水印全部后台处理。
 *
 * 状态：UPLOADED 已上传待处理 → PROCESSING 处理中 → DONE 可对外 / FAILED 可重处理。
 */
@TableName("t_handover_photo")
public class HandoverPhoto extends BizEntity {

    public static final String UPLOADED = "UPLOADED";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private Long orderId;
    private String originalFileName;
    private String originalPath;
    private Long originalSize;
    private Integer originalWidth;
    private Integer originalHeight;
    /** EXIF 方向标记（1-8；无标记为 null） */
    private Integer exifOrientation;
    private String previewPath;
    private Long previewSize;
    private Integer previewWidth;
    private Integer previewHeight;
    /** 水印内容快照：网点标识 + 订单号 */
    private String watermarkText;
    private String status;
    private Integer attempts;
    /** 最近一次处理耗时（毫秒） */
    private Long processingMs;
    private String errorDetail;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public Long getOriginalSize() { return originalSize; }
    public void setOriginalSize(Long originalSize) { this.originalSize = originalSize; }
    public Integer getOriginalWidth() { return originalWidth; }
    public void setOriginalWidth(Integer originalWidth) { this.originalWidth = originalWidth; }
    public Integer getOriginalHeight() { return originalHeight; }
    public void setOriginalHeight(Integer originalHeight) { this.originalHeight = originalHeight; }
    public Integer getExifOrientation() { return exifOrientation; }
    public void setExifOrientation(Integer exifOrientation) { this.exifOrientation = exifOrientation; }
    public String getPreviewPath() { return previewPath; }
    public void setPreviewPath(String previewPath) { this.previewPath = previewPath; }
    public Long getPreviewSize() { return previewSize; }
    public void setPreviewSize(Long previewSize) { this.previewSize = previewSize; }
    public Integer getPreviewWidth() { return previewWidth; }
    public void setPreviewWidth(Integer previewWidth) { this.previewWidth = previewWidth; }
    public Integer getPreviewHeight() { return previewHeight; }
    public void setPreviewHeight(Integer previewHeight) { this.previewHeight = previewHeight; }
    public String getWatermarkText() { return watermarkText; }
    public void setWatermarkText(String watermarkText) { this.watermarkText = watermarkText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Long getProcessingMs() { return processingMs; }
    public void setProcessingMs(Long processingMs) { this.processingMs = processingMs; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
}
