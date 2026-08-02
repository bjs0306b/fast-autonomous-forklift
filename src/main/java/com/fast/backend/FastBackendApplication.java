package com.fast.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FastBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FastBackendApplication.class, args);
    }
}
