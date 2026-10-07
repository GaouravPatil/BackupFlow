package com.backupflow.service;

import com.backupflow.dto.CreateBackupRequest;
import com.backupflow.entity.BackupJob;
import com.backupflow.entity.BackupStatus;
import com.backupflow.exception.BackupConflictException;
import com.backupflow.exception.BackupNotFoundException;
import com.backupflow.repository.BackupRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    @Mock
    private BackupRepository repository;

    private MeterRegistry meterRegistry;
    private BackupService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new BackupService(repository, meterRegistry) {
            // Disable async simulation in unit tests by overriding.
            @Override
            public void simulateExecutionAsync(Long jobId) {
                // no-op
            }
        };
    }

    @Test
    void create_persistsPendingJob() {
        CreateBackupRequest req = new CreateBackupRequest("/data/customer-001", "s3://backup-bucket/customer-001");
        BackupJob saved = new BackupJob(req.getSource(), req.getDestination());
        saved.setId(101L);

        when(repository.save(any(BackupJob.class))).thenReturn(saved);

        var response = service.create(req);

        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getStatus()).isEqualTo(BackupStatus.PENDING);
        assertThat(response.getRetryCount()).isZero();

        ArgumentCaptor<BackupJob> captor = ArgumentCaptor.forClass(BackupJob.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BackupStatus.PENDING);
    }

    @Test
    void get_existingJob_returnsResponse() {
        BackupJob job = new BackupJob("/data/a", "s3://b/a");
        job.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(job));

        var response = service.get(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getSource()).isEqualTo("/data/a");
    }

    @Test
    void get_missingJob_throwsNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(BackupNotFoundException.class);
    }

    @Test
    void list_returnsAll() {
        BackupJob job = new BackupJob("/data/a", "s3://b/a");
        job.setId(1L);
        when(repository.findAll()).thenReturn(List.of(job));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    void delete_existingJob_deletes() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    void delete_missingJob_throwsNotFound() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(BackupNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    void retry_failedJob_incrementsRetryAndResetsToPending() {
        BackupJob job = new BackupJob("/data/a", "s3://b/a");
        job.setId(5L);
        job.setStatus(BackupStatus.FAILED);
        job.setRetryCount(1);
        when(repository.findById(5L)).thenReturn(Optional.of(job));
        when(repository.save(any(BackupJob.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.retry(5L);

        assertThat(response.getRetryCount()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(BackupStatus.PENDING);
    }

    @Test
    void retry_successJob_throwsConflict() {
        BackupJob job = new BackupJob("/data/a", "s3://b/a");
        job.setId(6L);
        job.setStatus(BackupStatus.SUCCESS);
        when(repository.findById(6L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retry(6L))
                .isInstanceOf(BackupConflictException.class);
    }

    @Test
    void retry_pendingJob_throwsConflict() {
        BackupJob job = new BackupJob("/data/a", "s3://b/a");
        job.setId(7L);
        job.setStatus(BackupStatus.PENDING);
        when(repository.findById(7L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retry(7L))
                .isInstanceOf(BackupConflictException.class);
    }

    @Test
    void retry_missingJob_throwsNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(999L))
                .isInstanceOf(BackupNotFoundException.class);
    }
}
