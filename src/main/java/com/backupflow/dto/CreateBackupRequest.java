package com.backupflow.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateBackupRequest {

    @NotBlank(message = "source must not be blank")
    private String source;

    @NotBlank(message = "destination must not be blank")
    private String destination;

    public CreateBackupRequest() {
    }

    public CreateBackupRequest(String source, String destination) {
        this.source = source;
        this.destination = destination;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }
}
