package com.kbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * KBook 智能阅读平台 - 后端服务启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class KBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(KBookApplication.class, args);
    }
}
