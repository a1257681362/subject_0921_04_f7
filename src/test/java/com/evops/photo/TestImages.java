package com.evops.photo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 测试图片工具：纯色图、JPEG/PNG 编码、注入 EXIF 方向标记。 */
final class TestImages {

    private TestImages() {}

    static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", baos);
        return baos.toByteArray();
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 生成带 EXIF Orientation 的 JPEG：在 SOI 后插入 APP1(Exif) 段，
     * TIFF 头小端，IFD0 单条 Orientation(tag 0x0112, SHORT)。
     */
    static byte[] jpegWithOrientation(BufferedImage img, int orientation) throws IOException {
        byte[] plain = jpeg(img);
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write("Exif".getBytes(StandardCharsets.US_ASCII));
        app1.write(0);
        app1.write(0);
        app1.write('I');
        app1.write('I');                    // 小端
        app1.write(0x2A);
        app1.write(0x00);                   // TIFF magic 42
        app1.write(new byte[]{8, 0, 0, 0}); // IFD0 偏移 = 8
        app1.write(new byte[]{1, 0});       // 1 个条目
        app1.write(new byte[]{0x12, 0x01}); // tag 0x0112 Orientation
        app1.write(new byte[]{3, 0});       // type SHORT
        app1.write(new byte[]{1, 0, 0, 0}); // count 1
        app1.write(new byte[]{(byte) orientation, 0, 0, 0}); // 值
        app1.write(new byte[]{0, 0, 0, 0}); // 下一个 IFD = 无
        byte[] seg = app1.toByteArray();
        int segLen = seg.length + 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain[0]);
        out.write(plain[1]);                // SOI
        out.write(0xFF);
        out.write(0xE1);                    // APP1
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(seg);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }
}
