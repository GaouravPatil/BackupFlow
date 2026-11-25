package com.backupflow.config;

import com.backupflow.dto.CreateBackupRequest;
import com.backupflow.repository.BackupRepository;
import com.backupflow.service.BackupService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner seedBackupJobs(BackupRepository repository, BackupService service) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            service.create(new CreateBackupRequest(
                    "/data/customer-001", "s3://backup-bucket/customer-001"));
            service.create(new CreateBackupRequest(
                    "/data/customer-002", "s3://backup-bucket/customer-002"));
            service.create(new CreateBackupRequest(
                    "/data/customer-003", "s3://backup-bucket/fail-demo"));
        };
    }
}
