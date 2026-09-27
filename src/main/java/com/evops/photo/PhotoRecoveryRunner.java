package com.evops.photo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动自愈（rental-photo-1）：
 * 上次进程退出时可能留下 UPLOADED（未排上/排队丢失）与僵死 PROCESSING（处理到一半被杀）。
 * 启动时先把僵死 PROCESSING 一律复位为 UPLOADED（本进程线程池刚启动，不存在真在处理的行），
 * 再把所有 UPLOADED 重新排入后台处理。自愈失败不阻断启动，仅记录日志。
 */
@Component
@Order(10)
public class PhotoRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PhotoRecoveryRunner.class);

    private final HandoverPhotoMapper photoMapper;
    private final PhotoService photoService;

    public PhotoRecoveryRunner(HandoverPhotoMapper photoMapper, PhotoService photoService) {
        this.photoMapper = photoMapper;
        this.photoService = photoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int reset = photoMapper.update(null, new LambdaUpdateWrapper<HandoverPhoto>()
                    .eq(HandoverPhoto::getStatus, HandoverPhoto.PROCESSING)
                    .set(HandoverPhoto::getStatus, HandoverPhoto.UPLOADED));
            List<HandoverPhoto> pending = photoMapper.selectList(new LambdaQueryWrapper<HandoverPhoto>()
                    .eq(HandoverPhoto::getStatus, HandoverPhoto.UPLOADED));
            for (HandoverPhoto photo : pending) {
                photoService.scheduleProcessingFromBootstrap(photo.getId());
            }
            log.info("启动自愈：交车照片复位僵死处理中 {} 张，重新排队待处理 {} 张", reset, pending.size());
        } catch (Exception e) {
            log.error("启动自愈：交车照片遗留任务恢复失败，可通过重处理接口手工补齐", e);
        }
    }
}
