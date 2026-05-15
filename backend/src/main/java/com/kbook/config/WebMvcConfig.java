package com.kbook.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置 - 静态资源映射 + UTF-8 编码
 * 将上传的头像文件映射为可访问的 URL
 * 配置 StringHttpMessageConverter 使用 UTF-8，修复 SSE 中文乱码
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

    /**
     * 配置消息转换器：将 StringHttpMessageConverter 默认编码改为 UTF-8
     * 修复 SseEmitter 发送中文时被 ISO-8859-1 编码导致乱码的问题
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(c -> c instanceof StringHttpMessageConverter)
                .map(c -> (StringHttpMessageConverter) c)
                .forEach(c -> c.setDefaultCharset(StandardCharsets.UTF_8));
    }
}
