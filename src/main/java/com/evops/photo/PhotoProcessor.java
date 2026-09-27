package com.evops.photo;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 交车照片处理（rental-photo-1），处理顺序固定：
 * <ol>
 *   <li><b>EXIF 摆正</b>：竖拍照片只在 EXIF 写方向标记（最常见 6=顺时针90°），
 *       先按标记旋转/翻转像素，输出图方向即最终方向（输出 JPEG 不再带方向歧义）；</li>
 *   <li><b>缩放</b>：Thumbnailator 按宽 280px 等比缩小出预览图；原图更小不放大；</li>
 *   <li><b>水印</b>：30% 透明度斜向平铺，内容为网点标识 + 订单号；</li>
 *   <li><b>输出</b>：统一 JPEG（PNG 透明底铺白），按配置质量压缩。</li>
 * </ol>
 * 本类是无状态纯计算组件，不落盘、不碰库，便于后台线程与测试直接调用。
 */
@Component
public class PhotoProcessor {

    /** 处理结果：JPEG 字节 + 摆正缩放后的输出尺寸 + 输入图 EXIF 方向。 */
    public static final class Result {
        public final byte[] jpeg;
        public final int width;
        public final int height;
        public final int exifOrientation;
        public final int orientedWidth;
        public final int orientedHeight;

        Result(byte[] jpeg, int width, int height, int exifOrientation,
               int orientedWidth, int orientedHeight) {
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
            this.exifOrientation = exifOrientation;
            this.orientedWidth = orientedWidth;
            this.orientedHeight = orientedHeight;
        }
    }

    /**
     * 生成 280px 水印预览图。
     *
     * @param source       原图字节（JPEG/PNG）
     * @param watermarkText 水印文字（网点标识 + 订单号）
     * @param previewWidth 预览宽（px），原图更宽才缩，不放大
     * @param alpha        水印透明度 0-1（0.30 = 30%）
     * @param jpegQuality  JPEG 质量 0-1
     */
    public Result process(byte[] source, String watermarkText, int previewWidth,
                          double alpha, double jpegQuality) throws IOException {
        int orientation = JpegExif.readOrientation(source);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) {
            throw new IOException("不支持的图片格式或文件已损坏，ImageIO 无法解码");
        }
        BufferedImage oriented = applyOrientation(decoded, orientation);

        // 小图不放大：仅在更宽时交给 Thumbnailator 等比缩到预览宽
        BufferedImage scaled;
        if (oriented.getWidth() > previewWidth) {
            scaled = Thumbnails.of(oriented)
                    .width(previewWidth)
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .asBufferedImage();
        } else {
            scaled = copyRgb(oriented);
        }

        BufferedImage watermarked = watermark(scaled, watermarkText, alpha);
        byte[] jpeg = writeJpeg(watermarked, jpegQuality);
        return new Result(jpeg, watermarked.getWidth(), watermarked.getHeight(),
                orientation, oriented.getWidth(), oriented.getHeight());
    }

    // ---- EXIF 方向摆正 ----

    /**
     * 按 EXIF Orientation 把像素摆正。方向含义见 {@link JpegExif}。
     * 5/7（镜像+旋转）罕见，直接构造对角反射矩阵处理。
     */
    private BufferedImage applyOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform tx;
        int outW = w;
        int outH = h;
        switch (orientation) {
            case 2: // 水平翻转
                tx = new AffineTransform(-1, 0, 0, 1, w, 0);
                break;
            case 3: // 旋转 180
                tx = new AffineTransform(1, 0, 0, 1, 0, 0);
                tx.translate(w, h);
                tx.quadrantRotate(2);
                break;
            case 4: // 垂直翻转
                tx = new AffineTransform(1, 0, 0, -1, 0, h);
                break;
            case 5: // 主对角线反射（水平翻转 + 顺时针90）
                tx = new AffineTransform(0, 1, 1, 0, 0, 0);
                outW = h;
                outH = w;
                break;
            case 6: // 顺时针 90（竖拍最常见）
                tx = new AffineTransform(1, 0, 0, 1, 0, 0);
                tx.translate(h, 0);
                tx.quadrantRotate(1);
                outW = h;
                outH = w;
                break;
            case 7: // 反对角线反射
                tx = new AffineTransform(0, -1, -1, 0, h, w);
                outW = h;
                outH = w;
                break;
            case 8: // 逆时针 90
                tx = new AffineTransform(1, 0, 0, 1, 0, 0);
                tx.translate(0, w);
                tx.quadrantRotate(3);
                outW = h;
                outH = w;
                break;
            case 1:
            default:
                return copyRgb(src);
        }
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, outW, outH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ---- 水印 ----

    /**
     * 30% 透明度黑色斜向（约 -25°）平铺水印。黑色半透明在浅色车身上清晰、
     * 在深色车身上只是更含蓄，保证不遮挡损伤判断又擦不掉。
     */
    private BufferedImage watermark(BufferedImage src, String text, double alpha) {
        BufferedImage out = copyRgb(src);
        int width = out.getWidth();
        int height = out.getHeight();

        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int fontSize = Math.max(12, Math.min(width, height) / 16);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            g.setColor(Color.BLACK);
            // 30% 透明度：例会定值，改透明度只改配置不改代码
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));

            FontMetrics fm = g.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int stepX = textWidth + fontSize * 3;
            int stepY = fontSize * 5;

            g.rotate(Math.toRadians(-25), width / 2.0, height / 2.0);
            // 旋转坐标后沿 -25° 方向铺开，网格向四周多铺一截覆盖四个角
            int spread = Math.max(width, height);
            for (int y = -spread; y <= height + spread; y += stepY) {
                for (int x = -spread; x <= width + spread; x += stepX) {
                    g.drawString(text, x, y);
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private BufferedImage copyRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        // PNG 等带透明通道的图统一铺白底转 RGB，避免 JPEG 发糊/变色
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(src, 0, 0, Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private byte[] writeJpeg(BufferedImage image, double quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JRE 无 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality((float) quality);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
