package com.basamak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BasamakApplication {
    public static void main(String[] args) {
        SpringApplication.run(BasamakApplication.class, args);
    }
}
