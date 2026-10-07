package com.backupflow.exception;

public class BackupNotFoundException extends RuntimeException {

    public BackupNotFoundException(Long id) {
        super("Backup job not found: " + id);
    }
}
