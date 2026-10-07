package com.backupflow.exception;

public class BackupConflictException extends RuntimeException {

    public BackupConflictException(String message) {
        super(message);
    }
}
