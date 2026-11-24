package com.basamak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

// No in-memory default user: authentication is JWT-only (see SecurityConfig).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@OpenAPIDefinition(info = @Info(title = "Basamak API", version = "0.1.0",
        description = "Üye uygulaması (/api/**), CMS (/api/cms/**, TOTP'li ayrı oturum) ve webhook (/api/hooks/**) uçları. "
                + "Hatalar RFC 7807 problem+json: code, detail (Türkçe), requestId."),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class BasamakApplication {
    public static void main(String[] args) {
        SpringApplication.run(BasamakApplication.class, args);
    }
}
