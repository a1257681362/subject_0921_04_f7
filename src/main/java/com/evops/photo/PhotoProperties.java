package com.evops.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 交车照片处理配置（evops.photo）。
 */
@Component
@ConfigurationProperties(prefix = "evops.photo")
public class PhotoProperties {

    /** 照片落盘根目录（original 仅留底、preview 为对外水印预览，分目录存放） */
    private String storageDir = "data/handover-photos";

    /** 预览图宽（px）：仅缩放宽更大的图，小图不放大 */
    private int previewWidth = 280;

    /** 水印透明度（0.0-1.0；例会定 0.30 = 30%） */
    private double watermarkAlpha = 0.30;

    /** 预览 JPEG 输出质量 */
    private double jpegQuality = 0.85;

    /** 单张上传大小上限（字节，默认 10MB；边界依据见去年同期极端值诊断） */
    private long maxUploadSizeBytes = 10L * 1024 * 1024;

    /** 一次请求最多上传张数 */
    private int maxFilesPerRequest = 9;

    /** 允许上传的图片扩展名（输出统一为带水印 JPEG） */
    private List<String> allowedExtensions = Arrays.asList("jpg", "jpeg", "png");

    /** 后台处理线程池 */
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 500;
    private int awaitTerminationSeconds = 30;

    @PostConstruct
    public void validate() throws Exception {
        if (previewWidth <= 0) {
            throw new IllegalStateException("evops.photo.preview-width 必须为正数：" + previewWidth);
        }
        if (watermarkAlpha < 0 || watermarkAlpha > 1) {
            throw new IllegalStateException("evops.photo.watermark-alpha 必须在 0-1 之间：" + watermarkAlpha);
        }
        if (jpegQuality <= 0 || jpegQuality > 1) {
            throw new IllegalStateException("evops.photo.jpeg-quality 必须在 0-1（不含0）之间：" + jpegQuality);
        }
        if (maxUploadSizeBytes <= 0) {
            throw new IllegalStateException("evops.photo.max-upload-size-bytes 必须为正数");
        }
        Files.createDirectories(originalDir());
        Files.createDirectories(previewDir());
    }

    public Path baseDir() { return Paths.get(storageDir); }
    public Path originalDir() { return baseDir().resolve("original"); }
    public Path previewDir() { return baseDir().resolve("preview"); }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getPreviewWidth() { return previewWidth; }
    public void setPreviewWidth(int previewWidth) { this.previewWidth = previewWidth; }
    public double getWatermarkAlpha() { return watermarkAlpha; }
    public void setWatermarkAlpha(double watermarkAlpha) { this.watermarkAlpha = watermarkAlpha; }
    public double getJpegQuality() { return jpegQuality; }
    public void setJpegQuality(double jpegQuality) { this.jpegQuality = jpegQuality; }
    public long getMaxUploadSizeBytes() { return maxUploadSizeBytes; }
    public void setMaxUploadSizeBytes(long maxUploadSizeBytes) { this.maxUploadSizeBytes = maxUploadSizeBytes; }
    public int getMaxFilesPerRequest() { return maxFilesPerRequest; }
    public void setMaxFilesPerRequest(int maxFilesPerRequest) { this.maxFilesPerRequest = maxFilesPerRequest; }
    public List<String> getAllowedExtensions() { return allowedExtensions; }
    public void setAllowedExtensions(List<String> allowedExtensions) { this.allowedExtensions = allowedExtensions; }
    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}
