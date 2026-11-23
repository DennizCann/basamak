package com.basamak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// No in-memory default user: authentication is JWT-only (see SecurityConfig).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class BasamakApplication {
    public static void main(String[] args) {
        SpringApplication.run(BasamakApplication.class, args);
    }
}
