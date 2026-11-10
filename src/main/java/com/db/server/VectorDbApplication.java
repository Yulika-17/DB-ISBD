package com.db.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.db")
public class VectorDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(VectorDbApplication.class, args);
    }
}
