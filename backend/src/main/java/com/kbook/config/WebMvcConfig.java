package com.kbook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 - 静态资源映射
 * 将上传的头像文件映射为可访问的 URL
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${kbook.upload.avatar-dir:./uploads/avatars}")
    private String avatarDir;

    @Value("${kbook.upload.avatar-url-prefix:/api/uploads/avatars}")
    private String avatarUrlPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 头像文件静态资源映射
        registry.addResourceHandler(avatarUrlPrefix + "/**")
                .addResourceLocations("file:" + avatarDir + "/");
    }
}
