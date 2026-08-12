package com.example.demo.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("JoinDrink API")
                        .description("JoinDrink 飲料訂購平台後端 API 文件\n\n" +
                                "**三種角色 JWT：**\n" +
                                "- CUSTOMER：使用者\n" +
                                "- BRAND：品牌後台\n" +
                                "- STORE：分店後台\n\n" +
                                "**使用方式：** 點右上角 Authorize，輸入 `Bearer <your_token>`")
                        .version("1.0"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
