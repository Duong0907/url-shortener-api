package com.urlshortener;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

// Swagger UI: /swagger-ui.html   OpenAPI JSON: /v3/api-docs
@Configuration
@OpenAPIDefinition(info = @Info(title = "URL Shortener API", version = "v1",
        description = "Short links, redirects, accounts and link management."))
public class OpenApiConfig {
}
