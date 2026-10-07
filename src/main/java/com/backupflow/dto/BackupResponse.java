package com.backupflow.dto;

import com.backupflow.entity.BackupJob;
import com.backupflow.entity.BackupStatus;

import java.time.Instant;

public class BackupResponse {

    private Long id;
    private String source;
    private String destination;
    private BackupStatus status;
    private int retryCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public BackupResponse() {
    }

    public static BackupResponse from(BackupJob job) {
        BackupResponse r = new BackupResponse();
        r.id = job.getId();
        r.source = job.getSource();
        r.destination = job.getDestination();
        r.status = job.getStatus();
        r.retryCount = job.getRetryCount();
        r.createdAt = job.getCreatedAt();
        r.updatedAt = job.getUpdatedAt();
        r.completedAt = job.getCompletedAt();
        return r;
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
