package com.backupflow.service;

import com.backupflow.dto.BackupResponse;
import com.backupflow.dto.CreateBackupRequest;
import com.backupflow.entity.BackupJob;
import com.backupflow.entity.BackupStatus;
import com.backupflow.exception.BackupConflictException;
import com.backupflow.exception.BackupNotFoundException;
import com.backupflow.repository.BackupRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final BackupRepository repository;
    private final Counter createdCounter;
    private final Counter succeededCounter;
    private final Counter failedCounter;
    private final Counter retryCounter;
    private final Timer executionTimer;
    private final java.util.concurrent.atomic.AtomicInteger runningJobs = new java.util.concurrent.atomic.AtomicInteger(0);

    public BackupService(BackupRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.createdCounter = Counter.builder("backup_jobs_created_total")
                .description("Total backup jobs created")
                .register(meterRegistry);
        this.succeededCounter = Counter.builder("backup_jobs_succeeded_total")
                .description("Total backup jobs succeeded")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("backup_jobs_failed_total")
                .description("Total backup jobs failed")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("backup_jobs_retries_total")
                .description("Total backup job retries")
                .register(meterRegistry);
        this.executionTimer = Timer.builder("backup_job_execution_duration_seconds")
                .description("Backup job execution duration")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("backup_jobs_running", runningJobs, java.util.concurrent.atomic.AtomicInteger::get)
                .description("Backup jobs currently running")
                .register(meterRegistry);
    }

    @Transactional
    public BackupResponse create(CreateBackupRequest request) {
        BackupJob job = new BackupJob(request.getSource().trim(), request.getDestination().trim());
        job.setStatus(BackupStatus.PENDING);
        BackupJob saved = repository.save(job);
        createdCounter.increment();
        log.info("Created backup job id={} source={} destination={}",
                saved.getId(), saved.getSource(), saved.getDestination());
        // Simulate async execution: PENDING -> RUNNING -> SUCCESS/FAILED
        simulateExecutionAsync(saved.getId());
        return BackupResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<BackupResponse> list() {
        return repository.findAll().stream().map(BackupResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BackupResponse get(Long id) {
        BackupJob job = repository.findById(id).orElseThrow(() -> new BackupNotFoundException(id));
        return BackupResponse.from(job);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new BackupNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("Deleted backup job id={}", id);
    }

    /**
     * Retry a FAILED job. Any other status results in 409 Conflict.
     * Increments retryCount, moves back to RUNNING (via PENDING) and re-simulates execution.
     */
    @Transactional
    public BackupResponse retry(Long id) {
        BackupJob job = repository.findById(id).orElseThrow(() -> new BackupNotFoundException(id));
        if (job.getStatus() != BackupStatus.FAILED) {
            throw new BackupConflictException(
                    "Only FAILED jobs can be retried. Current status: " + job.getStatus());
        }
        job.setRetryCount(job.getRetryCount() + 1);
        job.setStatus(BackupStatus.PENDING);
        job.setCompletedAt(null);
        BackupJob saved = repository.save(job);
        retryCounter.increment();
        log.info("Retrying backup job id={} attempt={}", saved.getId(), saved.getRetryCount());
        simulateExecutionAsync(saved.getId());
        // 202 Accepted semantics are set in the controller; return current snapshot.
        return BackupResponse.from(saved);
    }

    /**
     * V1 simulation of a backup engine. Runs async so the API stays responsive.
     * Deterministic failure hook: if destination contains "fail" -> FAILED, else SUCCESS.
     * This keeps the demo + tests hermetic without S3 or a scheduler.
     */
    @Async
    @Transactional
    public void simulateExecutionAsync(Long jobId) {
        Instant start = Instant.now();
        try {
            // Small delay so PENDING -> RUNNING transition is observable.
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        repository.findById(jobId).ifPresent(job -> {
            // Only simulate jobs still in PENDING (fresh or retried). Don't clobber user-deleted jobs.
            if (job.getStatus() != BackupStatus.PENDING) {
                return;
            }
            job.setStatus(BackupStatus.RUNNING);
            runningJobs.incrementAndGet();
            repository.save(job);
            log.info("Backup job id={} RUNNING", job.getId());

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.setStatus(BackupStatus.FAILED);
                job.setCompletedAt(Instant.now());
                repository.save(job);
                failedCounter.increment();
                runningJobs.decrementAndGet();
                return;
            }

            boolean shouldFail = job.getDestination() != null
                    && job.getDestination().toLowerCase().contains("fail");
            if (shouldFail) {
                job.setStatus(BackupStatus.FAILED);
                failedCounter.increment();
                log.info("Backup job id={} FAILED (simulated)", job.getId());
            } else {
                job.setStatus(BackupStatus.SUCCESS);
                succeededCounter.increment();
                log.info("Backup job id={} SUCCESS (simulated)", job.getId());
            }
            job.setCompletedAt(Instant.now());
            repository.save(job);
            runningJobs.decrementAndGet();
            executionTimer.record(Duration.between(start, Instant.now()));
        });
    }
}
