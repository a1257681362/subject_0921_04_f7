package com.evops.photo;

import com.evops.common.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交车照片标识化集成测试（rental-photo-1）：内存 H2 + 临时落盘目录，全链路
 * （上传同步返回 → 后台 280px/EXIF 摆正/30% 水印 → 状态与耗时可见 → 预览对外）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:photo-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.photo.storage-dir=target/photo-it"
})
class HandoverPhotoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HandoverPhotoMapper photoMapper;
    @Autowired
    private PhotoRecoveryRunner recoveryRunner;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle(String plateNo) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), reqNo())
                        .content("{\"plateNo\":\"" + plateNo + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createBranch(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/branches"), reqNo())
                        .content("{\"branchCode\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long vehicleId, String branchCode) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"张三\""
                + (branchCode != null ? ",\"branchCode\":\"" + branchCode + "\"" : "") + "}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), reqNo()).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private MockMultipartFile photo(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, MediaType.IMAGE_JPEG_VALUE, bytes);
    }

    private HandoverPhoto awaitStatus(long photoId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        HandoverPhoto photo = null;
        while (System.currentTimeMillis() < deadline) {
            photo = photoMapper.selectById(photoId);
            if (photo != null && expected.equals(photo.getStatus())) {
                return photo;
            }
            Thread.sleep(50);
        }
        fail("等待照片状态 " + expected + " 超时，当前："
                + (photo == null ? "null" : photo.getStatus() + " err=" + photo.getErrorDetail()));
        return null;
    }

    // ---- 主流程 ----

    @Test
    void uploadReturnsImmediatelyThenBackgroundProducesWatermarkedPreview() throws Exception {
        String suffix = reqNo().substring(0, 8);
        createBranch("B" + suffix, "城东一部");
        long vehicleId = createVehicle("京A-" + suffix);
        String orderNo = "RO-PH-" + suffix;
        long orderId = createOrder(orderNo, vehicleId, "B" + suffix);

        byte[] jpeg = TestImages.jpeg(TestImages.solid(600, 300, 0x336699));
        byte[] png = TestImages.png(TestImages.solid(400, 200, 0x22AA44));

        // 上传同步返回：状态仍是 UPLOADED，证明缩放/水印没有卡在请求里
        MvcResult upload = mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("front.jpg", jpeg))
                        .file(new MockMultipartFile("files", "side.png",
                                MediaType.IMAGE_PNG_VALUE, png))
                        .header("X-Operator", "tester")
                        .header("X-Request-No", reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.data[1].status").value("UPLOADED"))
                .andReturn();
        JsonNode rows = objectMapper.readTree(upload.getResponse().getContentAsString()).get("data");
        long photoId1 = rows.get(0).get("id").asLong();
        long photoId2 = rows.get(1).get("id").asLong();

        // 后台处理完成：280px 预览、水印带网点标识与订单号、处理耗时可见
        HandoverPhoto done1 = awaitStatus(photoId1, HandoverPhoto.DONE);
        assertEquals(280, done1.getPreviewWidth());
        assertEquals(140, done1.getPreviewHeight());
        assertEquals(1, done1.getExifOrientation());
        assertNotNull(done1.getProcessingMs());
        assertTrue(done1.getWatermarkText().contains("B" + suffix), "水印应带网点标识");
        assertTrue(done1.getWatermarkText().contains(orderNo), "水印应带订单号");
        assertTrue(done1.getAttempts() >= 1);

        HandoverPhoto done2 = awaitStatus(photoId2, HandoverPhoto.DONE);
        assertEquals(280, done2.getPreviewWidth());
        assertEquals(140, done2.getPreviewHeight());

        // 对外版本：280px 水印预览可下载（JPEG）
        MvcResult preview = mockMvc.perform(get("/api/orders/" + orderId + "/photos/" + photoId1 + "/preview"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        byte[] body = preview.getResponse().getContentAsByteArray();
        assertEquals((byte) 0xFF, body[0]);
        assertEquals((byte) 0xD8, body[1]);

        // 列表可见处理状态与耗时
        mockMvc.perform(get("/api/orders/" + orderId + "/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("DONE"))
                .andExpect(jsonPath("$.data[0].processingMs").isNumber())
                .andExpect(jsonPath("$.data[0].watermarkText").isNotEmpty());
    }

    @Test
    void portraitPhotoStraightenedByExif() throws Exception {
        long vehicleId = createVehicle("京B-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-EXIF-" + reqNo().substring(0, 8), vehicleId, null);

        // 竖拍：像素 600x300 横躺存储，EXIF=6 应顺时针 90° 摆正为 300x600
        byte[] portrait = TestImages.jpegWithOrientation(TestImages.solid(600, 300, 0x336699), 6);
        MvcResult upload = mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("portrait.jpg", portrait))
                        .header("X-Operator", "tester")
                        .header("X-Request-No", reqNo()))
                .andExpect(status().isOk())
                .andReturn();
        long photoId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("data").get(0).get("id").asLong();

        HandoverPhoto done = awaitStatus(photoId, HandoverPhoto.DONE);
        assertEquals(6, done.getExifOrientation());
        assertEquals(280, done.getPreviewWidth());
        assertEquals(560, done.getPreviewHeight(), "竖拍照片摆正后预览应高于宽");
    }

    // ---- 入参校验 ----

    @Test
    void invalidUploadsRejected() throws Exception {
        long vehicleId = createVehicle("京C-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-BAD-" + reqNo().substring(0, 8), vehicleId, null);

        // 不支持的类型
        mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("notes.txt", "hello".getBytes()))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isBadRequest());

        // 空文件
        mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("empty.jpg", new byte[0]))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isBadRequest());

        // 超过单次张数上限（9）
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder many =
                multipart("/api/orders/" + orderId + "/photos");
        for (int i = 0; i < 10; i++) {
            many.file(photo("p" + i + ".jpg", TestImages.jpeg(TestImages.solid(10, 10, 0x123456))));
        }
        mockMvc.perform(many.header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isBadRequest());

        // 单张超过大小上限（10MB）
        mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("huge.jpg", new byte[10 * 1024 * 1024 + 1]))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isBadRequest());

        // 订单不存在
        mockMvc.perform(multipart("/api/orders/999999/photos")
                        .file(photo("a.jpg", TestImages.jpeg(TestImages.solid(10, 10, 0x123456))))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isConflict());

        // 全部拒绝后无任何照片记录
        mockMvc.perform(get("/api/orders/" + orderId + "/photos"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void duplicateRequestNoRejected() throws Exception {
        long vehicleId = createVehicle("京D-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-DUP-" + reqNo().substring(0, 8), vehicleId, null);
        String requestNo = reqNo();
        byte[] jpeg = TestImages.jpeg(TestImages.solid(100, 100, 0x336699));

        mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("a.jpg", jpeg))
                        .header("X-Operator", "tester").header("X-Request-No", requestNo))
                .andExpect(status().isOk());
        // 同请求号重复提交被幂等拦截
        mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("a.jpg", jpeg))
                        .header("X-Operator", "tester").header("X-Request-No", requestNo))
                .andExpect(status().isConflict());
    }

    // ---- 失败与重处理 ----

    @Test
    void corruptFileFailsThenReprocessAfterFix() throws Exception {
        long vehicleId = createVehicle("京E-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-FIX-" + reqNo().substring(0, 8), vehicleId, null);

        // 扩展名是 jpg 但内容损坏：同步收下，后台判 FAILED，原因可见
        MvcResult upload = mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("broken.jpg", "not-a-real-jpeg".getBytes()))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isOk())
                .andReturn();
        long photoId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("data").get(0).get("id").asLong();

        HandoverPhoto failed = awaitStatus(photoId, HandoverPhoto.FAILED);
        assertNotNull(failed.getErrorDetail());
        assertNotNull(failed.getProcessingMs(), "失败也记录处理耗时");

        // 失败时预览 409 并提示重处理
        mockMvc.perform(get("/api/orders/" + orderId + "/photos/" + photoId + "/preview"))
                .andExpect(status().isConflict());

        // 重处理：内容仍坏，再次 FAILED，尝试次数递增
        mockMvc.perform(write(post("/api/orders/" + orderId + "/photos/" + photoId + "/reprocess"), reqNo()))
                .andExpect(status().isOk());
        HandoverPhoto failedAgain = awaitStatus(photoId, HandoverPhoto.FAILED);
        assertTrue(failedAgain.getAttempts() >= 2);

        // 现场重拍后覆盖原文件，再重处理成功
        Files.write(Paths.get("target/photo-it").resolve(failedAgain.getOriginalPath()),
                TestImages.jpeg(TestImages.solid(600, 300, 0x336699)));
        mockMvc.perform(write(post("/api/orders/" + orderId + "/photos/" + photoId + "/reprocess"), reqNo()))
                .andExpect(status().isOk());
        HandoverPhoto done = awaitStatus(photoId, HandoverPhoto.DONE);
        assertEquals(280, done.getPreviewWidth());
        mockMvc.perform(get("/api/orders/" + orderId + "/photos/" + photoId + "/preview"))
                .andExpect(status().isOk());
    }

    @Test
    void previewNotReadyAndReprocessGuards() throws Exception {
        long vehicleId = createVehicle("京F-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-PEND-" + reqNo().substring(0, 8), vehicleId, null);

        // 直接插一行 UPLOADED（不触发后台调度），预览应 409 提示未就绪
        RequestContext.set("tester", LocalDateTime.now(), "IT-" + reqNo());
        HandoverPhoto pending;
        try {
            pending = new HandoverPhoto();
            pending.setOrderId(orderId);
            pending.setOriginalFileName("manual.jpg");
            pending.setOriginalPath("original/20990101/manual_" + reqNo().substring(0, 8) + ".jpg");
            pending.setOriginalSize(1L);
            pending.setStatus(HandoverPhoto.UPLOADED);
            pending.setAttempts(0);
            photoMapper.insert(pending);
        } finally {
            RequestContext.clear();
        }

        mockMvc.perform(get("/api/orders/" + orderId + "/photos/" + pending.getId() + "/preview"))
                .andExpect(status().isConflict());

        // DONE 无需重处理：先造一张完成的
        byte[] jpeg = TestImages.jpeg(TestImages.solid(100, 100, 0x336699));
        MvcResult upload = mockMvc.perform(multipart("/api/orders/" + orderId + "/photos")
                        .file(photo("ok.jpg", jpeg))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo()))
                .andExpect(status().isOk())
                .andReturn();
        long doneId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("data").get(0).get("id").asLong();
        awaitStatus(doneId, HandoverPhoto.DONE);
        mockMvc.perform(write(post("/api/orders/" + orderId + "/photos/" + doneId + "/reprocess"), reqNo()))
                .andExpect(status().isConflict());

        // 别的订单的照片不能跨单访问
        mockMvc.perform(get("/api/orders/" + orderId + "/photos/999999/preview"))
                .andExpect(status().isConflict());
    }

    // ---- 启动自愈 ----

    @Test
    void recoveryRunnerRescuesStuckRows() throws Exception {
        long vehicleId = createVehicle("京G-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-RCV-" + reqNo().substring(0, 8), vehicleId, null);

        // 造两张遗留：一张 UPLOADED（进程没排上），一张僵死 PROCESSING（处理到一半被杀）
        String dayDir = "20990101";
        String suffix = reqNo().substring(0, 8);
        byte[] jpeg = TestImages.jpeg(TestImages.solid(300, 200, 0x336699));
        java.nio.file.Path dir = Paths.get("target/photo-it/original", dayDir);
        Files.createDirectories(dir);
        Files.write(dir.resolve("stuck_u_" + suffix + ".jpg"), jpeg);
        Files.write(dir.resolve("stuck_p_" + suffix + ".jpg"), jpeg);

        RequestContext.set("tester", LocalDateTime.now(), "IT-" + reqNo());
        HandoverPhoto uploaded;
        HandoverPhoto processing;
        try {
            uploaded = new HandoverPhoto();
            uploaded.setOrderId(orderId);
            uploaded.setOriginalFileName("stuck_u.jpg");
            uploaded.setOriginalPath("original/" + dayDir + "/stuck_u_" + suffix + ".jpg");
            uploaded.setOriginalSize((long) jpeg.length);
            uploaded.setStatus(HandoverPhoto.UPLOADED);
            uploaded.setAttempts(0);
            photoMapper.insert(uploaded);

            processing = new HandoverPhoto();
            processing.setOrderId(orderId);
            processing.setOriginalFileName("stuck_p.jpg");
            processing.setOriginalPath("original/" + dayDir + "/stuck_p_" + suffix + ".jpg");
            processing.setOriginalSize((long) jpeg.length);
            processing.setStatus(HandoverPhoto.PROCESSING);
            processing.setAttempts(1);
            photoMapper.insert(processing);
        } finally {
            RequestContext.clear();
        }

        recoveryRunner.run(null);

        HandoverPhoto rescued1 = awaitStatus(uploaded.getId(), HandoverPhoto.DONE);
        HandoverPhoto rescued2 = awaitStatus(processing.getId(), HandoverPhoto.DONE);
        assertEquals(280, rescued1.getPreviewWidth());
        assertEquals(280, rescued2.getPreviewWidth());
    }
}
