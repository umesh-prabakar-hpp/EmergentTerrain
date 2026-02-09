package com.emergentterrain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmergentTerrainApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmergentTerrainApplication.class, args);
    }
}
