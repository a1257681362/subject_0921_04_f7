package com.evops.photo;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** EXIF 方向读取：竖拍标记、无标记、非 JPEG、损坏数据。 */
class JpegExifTest {

    @Test
    void readsOrientationFromApp1() throws IOException {
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] jpeg = TestImages.jpegWithOrientation(TestImages.solid(40, 20, 0xFFFFFF), orientation);
            assertEquals(orientation, JpegExif.readOrientation(jpeg), "orientation=" + orientation);
        }
    }

    @Test
    void plainJpegDefaultsToNormal() throws IOException {
        byte[] jpeg = TestImages.jpeg(TestImages.solid(40, 20, 0xFFFFFF));
        assertEquals(1, JpegExif.readOrientation(jpeg));
    }

    @Test
    void nonJpegDefaultsToNormal() throws IOException {
        byte[] png = TestImages.png(TestImages.solid(40, 20, 0xFFFFFF));
        assertEquals(1, JpegExif.readOrientation(png));
        assertEquals(1, JpegExif.readOrientation("not an image".getBytes()));
        assertEquals(1, JpegExif.readOrientation(new byte[0]));
        assertEquals(1, JpegExif.readOrientation(null));
    }
}
