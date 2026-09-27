package com.evops.photo;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 最小 JPEG EXIF 方向读取器（只读 Orientation，tag 0x0112）。
 *
 * 竖拍手机/相机不旋转像素、只在 EXIF 写方向标记，ImageIO 读入的图可能是侧躺的；
 * 输出前须按标记摆正。非 JPEG、无 APP1/无 EXIF/无 Orientation 一律返回 1（正常）。
 *
 * 标记含义（TIFF/EXIF 2.2）：
 * 1=正常 2=水平翻转 3=旋转180 4=垂直翻转
 * 5=顺时针90+水平翻转 6=顺时针90（竖拍最常见） 7=逆时针90+水平翻转 8=逆时针90
 */
final class JpegExif {

    private JpegExif() {}

    static int readOrientation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4
                || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1; // 不是 JPEG（如 PNG），无 EXIF 方向
        }
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(jpeg);
            // SOI 2 字节后逐段扫描 marker
            long skipped = in.skip(2);
            if (skipped != 2) {
                return 1;
            }
            while (true) {
                int markerHi = in.read();
                int markerLo = in.read();
                if (markerHi < 0 || markerLo < 0) {
                    return 1;
                }
                if (markerHi != 0xFF) {
                    return 1;
                }
                int marker = markerLo;
                // SOS（扫描数据开始）之后就是图像熵数据，EXIF 只可能在其之前
                if (marker == 0xDA) {
                    return 1;
                }
                // 无长度段（如 RSTn / SOI）继续
                int lenHi = in.read();
                int lenLo = in.read();
                if (lenHi < 0 || lenLo < 0) {
                    return 1;
                }
                int segLen = ((lenHi << 8) | lenLo) & 0xFFFF;
                if (segLen < 2) {
                    return 1;
                }
                byte[] seg = new byte[segLen - 2];
                readFully(in, seg);
                if (marker == 0xE1) {
                    Integer orientation = parseExifOrientation(seg);
                    if (orientation != null) {
                        return orientation;
                    }
                }
            }
        } catch (IOException ex) {
            return 1;
        }
    }

    /** 从 APP1 段内容（不含 0xFFE1 与长度）解析 Orientation；非 EXIF 或无标记返回 null。 */
    private static Integer parseExifOrientation(byte[] seg) throws IOException {
        // "Exif" + 0x00 0x00
        if (seg.length < 6
                || seg[0] != 'E' || seg[1] != 'x' || seg[2] != 'i' || seg[3] != 'f'
                || seg[4] != 0 || seg[5] != 0) {
            return null;
        }
        ByteReader r = new ByteReader(seg);
        r.pos = 6; // TIFF 头起点
        boolean bigEndian;
        int bo0 = r.u8();
        int bo1 = r.u8();
        if (bo0 == 0x4D && bo1 == 0x4D) {
            bigEndian = true;
        } else if (bo0 == 0x49 && bo1 == 0x49) {
            bigEndian = false;
        } else {
            return null;
        }
        int magic = r.u16(bigEndian);
        if (magic != 0x002A) {
            return null;
        }
        int ifd0Offset = r.u32toInt(bigEndian);
        if (ifd0Offset <= 0 || 6 + ifd0Offset + 2 > seg.length) {
            return null;
        }
        r.pos = 6 + ifd0Offset;
        int entryCount = r.u16(bigEndian);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = r.pos;
            if (entryPos + 12 > seg.length) {
                return null;
            }
            int tag = r.u16(bigEndian);
            int type = r.u16(bigEndian);
            int count = r.u32toInt(bigEndian);
            int valueOrOffset = r.u32toInt(bigEndian);
            if (tag == 0x0112) {
                // type 3 = SHORT；Orientation 必为单值，前两字节即值
                if (type != 3 || count < 1) {
                    return null;
                }
                int orientation = bigEndian
                        ? ((valueOrOffset >>> 24) & 0xFFFF)
                        : (valueOrOffset & 0xFFFF);
                if (orientation < 1 || orientation > 8) {
                    return 1;
                }
                return orientation;
            }
        }
        return null;
    }

    private static void readFully(ByteArrayInputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                throw new IOException("EXIF 段数据不完整");
            }
            total += n;
        }
    }

    private static final class ByteReader {
        final byte[] data;
        int pos;

        ByteReader(byte[] data) {
            this.data = data;
        }

        int u8() {
            return data[pos++] & 0xFF;
        }

        int u16(boolean bigEndian) {
            int hi = u8();
            int lo = u8();
            return bigEndian ? ((hi << 8) | lo) : ((lo << 8) | hi);
        }

        int u32toInt(boolean bigEndian) {
            int b0 = u8();
            int b1 = u8();
            int b2 = u8();
            int b3 = u8();
            return bigEndian
                    ? ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)
                    : ((b3 << 24) | (b2 << 16) | (b1 << 8) | b0);
        }
    }
}
