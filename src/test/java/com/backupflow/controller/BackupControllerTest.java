package com.backupflow.controller;

import com.backupflow.dto.BackupResponse;
import com.backupflow.dto.CreateBackupRequest;
import com.backupflow.entity.BackupJob;
import com.backupflow.entity.BackupStatus;
import com.backupflow.exception.BackupConflictException;
import com.backupflow.exception.BackupNotFoundException;
import com.backupflow.service.BackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackupController.class)
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BackupService service;

    private BackupJob job(Long id, BackupStatus status, int retries) {
        BackupJob j = new BackupJob("/data/customer-001", "s3://backup-bucket/customer-001");
        j.setId(id);
        j.setStatus(status);
        j.setRetryCount(retries);
        return j;
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(service.create(any(CreateBackupRequest.class)))
                .thenReturn(BackupResponse.from(job(101L, BackupStatus.PENDING, 0)));

        mockMvc.perform(post("/api/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBackupRequest("/data/customer-001", "s3://backup-bucket/customer-001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0));
    }

    @Test
    void create_invalidRequest_returns400() throws Exception {
        mockMvc.perform(post("/api/backups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"\",\"destination\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOne_existing_returns200() throws Exception {
        when(service.get(101L)).thenReturn(BackupResponse.from(job(101L, BackupStatus.PENDING, 0)));

        mockMvc.perform(get("/api/backups/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101));
    }

    @Test
    void getOne_missing_returns404() throws Exception {
        when(service.get(999L)).thenThrow(new BackupNotFoundException(999L));

        mockMvc.perform(get("/api/backups/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returns200() throws Exception {
        when(service.list()).thenReturn(List.of(BackupResponse.from(job(1L, BackupStatus.PENDING, 0))));

        mockMvc.perform(get("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void retry_failed_returns202() throws Exception {
        when(service.retry(5L)).thenReturn(BackupResponse.from(job(5L, BackupStatus.PENDING, 1)));

        mockMvc.perform(post("/api/backups/5/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.retryCount").value(1));
    }

    @Test
    void retry_nonFailed_returns409() throws Exception {
        when(service.retry(eq(6L))).thenThrow(new BackupConflictException("Only FAILED jobs can be retried"));

        mockMvc.perform(post("/api/backups/6/retry"))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_existing_returns204() throws Exception {
        mockMvc.perform(delete("/api/backups/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_missing_returns404() throws Exception {
        doThrow(new BackupNotFoundException(999L)).when(service).delete(999L);

        mockMvc.perform(delete("/api/backups/999"))
                .andExpect(status().isNotFound());
    }
}
