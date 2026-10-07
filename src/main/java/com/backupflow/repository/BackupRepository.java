package com.backupflow.repository;

import com.backupflow.entity.BackupJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupRepository extends JpaRepository<BackupJob, Long> {
}
