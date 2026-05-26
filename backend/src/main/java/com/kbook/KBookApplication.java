package com.kbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * KBook 智能阅读平台 - 后端服务启动类
 */
@SpringBootApplication
@EnableScheduling // 启用定时任务调度
@EnableAsync     // 启用异步方法执行
public class KBookApplication {

    /**
     * 应用程序入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(KBookApplication.class, args);
    }
}
