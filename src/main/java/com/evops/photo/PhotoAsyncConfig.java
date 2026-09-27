package com.evops.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 交车照片后台处理线程池（rental-photo-1）。
 *
 * 上传请求线程只负责落原图与插记录，缩放/摆正/水印全部丢到本池，
 * 忙时任务排队、池满拒绝（拒绝后记录保持 UPLOADED，由启动自愈补齐），
 * 绝不让图片处理把上传接口拖住或把 Web 线程占满。
 */
@Configuration
public class PhotoAsyncConfig {

    public static final String EXECUTOR = "photoProcessingExecutor";

    private static final Logger log = LoggerFactory.getLogger(PhotoAsyncConfig.class);

    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor photoProcessingExecutor(PhotoProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("photo-process-");
        // 池满直接拒绝而不是让上传线程自己跑；记录留 UPLOADED，启动自愈/重处理兜底
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机：在途处理给一点时间收尾，避免应用关停造出僵死 PROCESSING
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();
        log.info("交车照片后台处理线程池已启动 core={} max={} queue={}",
                properties.getCorePoolSize(), properties.getMaxPoolSize(),
                properties.getQueueCapacity());
        return executor;
    }
}
