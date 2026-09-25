package com.chris64233.cc.fisheries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CcFisheriesApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcFisheriesApplication.class, args);
    }
}
