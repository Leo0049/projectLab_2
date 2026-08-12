package com.example.demo.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/Brand/**")
                .addResourceLocations("file:frontend/Brand/")
                .setCachePeriod(0);

        registry.addResourceHandler("/Customer/**")
                .addResourceLocations("file:frontend/Customer/")
                .setCachePeriod(0);

        registry.addResourceHandler("/Store/**")
                .addResourceLocations("file:frontend/Store/")
                .setCachePeriod(0);

        registry.addResourceHandler("/auth/**")
                .addResourceLocations("file:frontend/")
                .setCachePeriod(0);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
