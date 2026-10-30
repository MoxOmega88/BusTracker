package com.mohith.bustracker.eta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EtaApplication {
    public static void main(String[] args) {
        SpringApplication.run(EtaApplication.class, args);
    }
}