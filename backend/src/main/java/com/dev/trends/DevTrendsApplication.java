package com.dev.trends;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class DevTrendsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevTrendsApplication.class, args);
    }
}
