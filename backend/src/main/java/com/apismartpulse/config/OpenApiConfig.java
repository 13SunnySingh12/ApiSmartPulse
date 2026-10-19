package com.apismartpulse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI at /swagger-ui, OpenAPI at /v3/api-docs (FR-082). Fixed server URL keeps the snapshot
 * stable.
 */
@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("ApiSmartPulse API")
                .version("v1")
                .description(
                    "App API (/api/v1, Bearer JWT) and read-only public API (/api/public/v1, X-API-Key)."
                        + " Errors use RFC 9457 problem+json with a stable `code`."))
        .servers(List.of(new Server().url("http://localhost:8080").description("Local")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")));
  }
}
