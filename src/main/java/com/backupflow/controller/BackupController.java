package com.backupflow.controller;

import com.backupflow.dto.BackupResponse;
import com.backupflow.dto.CreateBackupRequest;
import com.backupflow.service.BackupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BackupResponse> create(@Valid @RequestBody CreateBackupRequest request) {
        BackupResponse created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<BackupResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BackupResponse> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<BackupResponse> retry(@PathVariable Long id) {
        BackupResponse retried = service.retry(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(retried);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
