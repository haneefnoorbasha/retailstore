package com.retailstore.catalog.infrastructure.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.*;
import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Catalog Service API")
                .description("Product catalog — browse, filter, and retrieve product information")
                .version("1.0.0")
                .contact(new Contact().name("RetailStore Platform").email("platform@retailstore.com"))
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(new Server().url("/").description("Current server")));
    }
}
