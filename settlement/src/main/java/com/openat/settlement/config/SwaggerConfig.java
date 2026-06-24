package com.openat.settlement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI settlementOpenAPI() {
        Server localServer = new Server()
                .url("http://localhost:9140")
                .description("Local settlement-service");

        return new OpenAPI()
                .servers(List.of(localServer))
                .info(new Info()
                        .title("OpenAt Settlement Service API")
                        .description("정산 주문, 판매자 정산 결과, 실패 정산 재처리 API 문서입니다.")
                        .version("v1"));
    }
}
