package com.db.server;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI vectorDatabaseApi() {
        return new OpenAPI().info(new Info()
                .title("Vector database REST API")
                .description("Remote access to collections and vectors")
                .version("1.0"));
    }
}
