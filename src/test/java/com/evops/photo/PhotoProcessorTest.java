package com.evops.photo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片处理器单元测试：280px 缩放（不放大）、EXIF 竖拍摆正、30% 透明度水印。
 */
class PhotoProcessorTest {

    private final PhotoProcessor processor = new PhotoProcessor();

    @Test
    void scalesToPreviewWidthKeepingAspect() throws IOException {
        byte[] source = TestImages.jpeg(TestImages.solid(600, 300, 0x336699));
        PhotoProcessor.Result result = processor.process(source, "网点:B001 | 单号:RO1", 280, 0.30, 0.85);
        assertEquals(280, result.width);
        assertEquals(140, result.height);
        assertEquals(1, result.exifOrientation);
        // 输出可解码且为 JPEG
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.jpeg));
        assertEquals(280, decoded.getWidth());
        assertEquals(140, decoded.getHeight());
    }

    @Test
    void smallImageIsNotUpscaled() throws IOException {
        byte[] source = TestImages.jpeg(TestImages.solid(100, 50, 0x336699));
        PhotoProcessor.Result result = processor.process(source, "WM", 280, 0.30, 0.85);
        assertEquals(100, result.width);
        assertEquals(50, result.height);
    }

    @Test
    void portraitExifIsStraightened() throws IOException {
        // 存储像素 600x300（横），EXIF=6 表示应顺时针 90° 显示 => 摆正后 300x600（竖）
        byte[] source = TestImages.jpegWithOrientation(TestImages.solid(600, 300, 0x336699), 6);
        PhotoProcessor.Result result = processor.process(source, "WM", 280, 0.30, 0.85);
        assertEquals(6, result.exifOrientation);
        assertEquals(300, result.orientedWidth);
        assertEquals(600, result.orientedHeight);
        assertEquals(280, result.width);
        assertEquals(560, result.height);
    }

    @Test
    void watermarkAlphaIsThirtyPercent() throws IOException {
        byte[] white = TestImages.jpeg(TestImages.solid(400, 300, 0xFFFFFF));

        // 30% 透明度黑字落在白底上：0.7*255 + 0.3*0 ≈ 178
        PhotoProcessor.Result watermarked = processor.process(white, "网点:B001 | 单号:RO1", 280, 0.30, 0.95);
        int darkest30 = darkestGray(watermarked.jpeg);
        assertTrue(darkest30 >= 150 && darkest30 <= 200,
                "30% 透明度水印最暗像素应约 178，实际 " + darkest30);

        // 对照组：100% 不透明黑字最暗应接近 0
        PhotoProcessor.Result opaque = processor.process(white, "网点:B001 | 单号:RO1", 280, 1.0, 0.95);
        int darkest100 = darkestGray(opaque.jpeg);
        assertTrue(darkest100 < 60, "不透明水印最暗像素应接近黑，实际 " + darkest100);
    }

    @Test
    void pngInputProducesJpeg() throws IOException {
        byte[] png = TestImages.png(TestImages.solid(400, 200, 0x22AA44));
        PhotoProcessor.Result result = processor.process(png, "WM", 280, 0.30, 0.85);
        assertEquals(280, result.width);
        assertEquals(140, result.height);
        // JPEG 魔数 FFD8
        assertEquals((byte) 0xFF, result.jpeg[0]);
        assertEquals((byte) 0xD8, result.jpeg[1]);
    }

    /** 图中最暗像素的灰度（用于验证水印存在且透明度正确）。 */
    private static int darkestGray(byte[] jpeg) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        int darkest = 255;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r + g + b) / 3;
                if (gray < darkest) {
                    darkest = gray;
                }
            }
        }
        return darkest;
    }
}
