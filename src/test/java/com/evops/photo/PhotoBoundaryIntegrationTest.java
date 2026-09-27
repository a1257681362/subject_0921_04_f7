package com.evops.photo;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 去年同期租车单极端值诊断测试（rental-photo-1）。
 * 以 2025-09-26（今天 2026-09-26 的去年同期）为中心造交接数据，
 * 验证窗口统计、地址串张数估算、限额对照与新链路实测。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:photo-boundary-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.photo.storage-dir=target/photo-boundary-it"
})
class PhotoBoundaryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle(String plateNo) throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/vehicles"), reqNo())
                        .content("{\"plateNo\":\"" + plateNo + "\",\"model\":\"M\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrderAt(String orderNo, long vehicleId, String bizTime) throws Exception {
        MockHttpServletRequestBuilder req = json(post("/api/orders"), reqNo())
                .content("{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                        + ",\"customerName\":\"张三\"}");
        if (bizTime != null) {
            req.header("X-Biz-Time", bizTime);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void pickupAt(long orderId, String damagePhotos, String bizTime) throws Exception {
        MockHttpServletRequestBuilder req = json(post("/api/orders/" + orderId + "/pickup"), reqNo())
                .content("{\"fuelReading\":80,\"damagePhotos\":\"" + damagePhotos + "\"}");
        req.header("X-Biz-Time", bizTime);
        mockMvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void lastYearSamePeriodExtremesReported() throws Exception {
        String s1 = reqNo().substring(0, 8);
        long v1 = createVehicle("京H-" + s1);
        long o1 = createOrderAt("RO-LY1-" + s1, v1, "2025-09-20T10:00:00");
        // 去年同期窗口内：一次交车 5 张照片
        String five = "oss://p/a1.jpg,oss://p/a2.jpg,oss://p/a3.jpg,oss://p/a4.jpg,oss://p/a5.jpg";
        pickupAt(o1, five, "2025-09-20T10:05:00");

        String s2 = reqNo().substring(0, 8);
        long v2 = createVehicle("京I-" + s2);
        long o2 = createOrderAt("RO-LY2-" + s2, v2, "2025-09-26T10:00:00");
        pickupAt(o2, "oss://p/single.jpg", "2025-09-26T10:05:00");

        // 窗口外（2024 年初）不应计入
        String s3 = reqNo().substring(0, 8);
        long v3 = createVehicle("京J-" + s3);
        long o3 = createOrderAt("RO-LY3-" + s3, v3, "2024-01-02T10:00:00");
        pickupAt(o3, "oss://p/out-of-window-1.jpg,oss://p/out-of-window-2.jpg,"
                + "oss://p/out-of-window-3.jpg,oss://p/out-of-window-4.jpg,"
                + "oss://p/out-of-window-5.jpg,oss://p/out-of-window-6.jpg",
                "2024-01-02T10:05:00");

        // 新链路：在去年同期窗口造一张已处理照片（biz_time 用头打到 2025-09-21）
        byte[] jpeg = TestImages.jpeg(TestImages.solid(300, 200, 0x336699));
        mockMvc.perform(multipart("/api/orders/" + o1 + "/photos")
                        .file(new MockMultipartFile("files", "n.jpg",
                                MediaType.IMAGE_JPEG_VALUE, jpeg))
                        .header("X-Operator", "tester").header("X-Request-No", reqNo())
                        .header("X-Biz-Time", "2025-09-21T12:00:00"))
                .andExpect(status().isOk());

        // 默认：今天（2026-09-26）去年同期 ±15 天
        MvcResult result = mockMvc.perform(get("/api/orders/photo-boundaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samePeriodDate").value("2025-09-26"))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.orderCount").value(2))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.handoverCount").value(2))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.photoRefs").value(6))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.maxPhotoRefsPerHandover").value(5))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.maxPhotosStringLength").value(five.length()))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.fuelReadingMin").value(80))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.fuelReadingMax").value(80))
                .andExpect(jsonPath("$.data.assessment.filesPerRequestCoversHistory").value(true))
                .andExpect(jsonPath("$.data.assessment.uploadSizeCoversObserved").value(true))
                .andExpect(jsonPath("$.data.newChainObserved.photoCount").value(1))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        long maxBytes = root.get("data").get("newChainObserved").get("maxOriginalSizeBytes").asLong();
        long maxMs = root.get("data").get("newChainObserved").get("maxProcessingMs").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(maxBytes > 0, "新链路应有实测字节极值");
        org.junit.jupiter.api.Assertions.assertTrue(maxMs >= 0);
        org.junit.jupiter.api.Assertions.assertEquals(5,
                root.get("data").get("topPhotoHandovers").get(0).get("photo_refs").asInt());

        // 精确日 ±0：只看 2025-09-26 当天
        mockMvc.perform(get("/api/orders/photo-boundaries").param("windowDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastYearSamePeriod.handoverCount").value(1))
                .andExpect(jsonPath("$.data.lastYearSamePeriod.maxPhotoRefsPerHandover").value(1));

        // 指定参考日 2025-01-10 ±20 天覆盖 2024-01-02 的 6 张极端值
        mockMvc.perform(get("/api/orders/photo-boundaries")
                        .param("bizDate", "2025-01-10").param("windowDays", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastYearSamePeriod.maxPhotoRefsPerHandover").value(6));

        // windowDays 非法
        mockMvc.perform(get("/api/orders/photo-boundaries").param("windowDays", "999"))
                .andExpect(status().isBadRequest());
    }
}
