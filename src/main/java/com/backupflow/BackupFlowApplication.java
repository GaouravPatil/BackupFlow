package com.backupflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BackupFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackupFlowApplication.class, args);
    }
}
