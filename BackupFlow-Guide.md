# BackupFlow V1 — Complete Guide

> Java Backup Job Management Service: REST API + PostgreSQL + Docker Compose + Prometheus + Grafana.

## 1. What This Project Is

Production-shaped REST service for creating and tracking backup jobs.

State machine:

```
PENDING → RUNNING → SUCCESS
                    ↘ FAILED → (POST /retry) → PENDING → RUNNING ...
```

V1 has **no real backup engine**. `BackupService.simulateExecutionAsync` simulates execution asynchronously (~1.5s delay). Deterministic failure hook: if `destination` contains `fail` (case-insensitive) → `FAILED`, else `SUCCESS`.

Tech: Java 21, Spring Boot 3.3.5, Spring Web / Data JPA / Validation, PostgreSQL 16, Maven, JUnit 5 + Mockito + AssertJ, Docker Compose, Actuator + Micrometer Prometheus.

Architecture:

```
Client (curl)
  ↓ HTTP/JSON :8080
Spring Boot REST API (controller → service → JPA)
  ↓
PostgreSQL :5432

Actuator /actuator/prometheus → Prometheus :9090 → Grafana :3000
```

---

## 2. How To Run

### Option A — Docker Compose (recommended)

`docker compose` alone does nothing — it needs a sub-command:

```bash
docker compose up --build
# detached:
docker compose up --build -d
```

First run is slow (Maven downloads dependencies).

If you get `permission denied on /var/run/docker.sock`:

```bash
sudo docker compose up --build
# permanent fix:
sudo usermod -aG docker $USER && newgrp docker
```

Services started from `docker-compose.yml`:

| Service | URL | Details |
|---|---|---|
| API | http://localhost:8080 | Spring Boot app |
| Postgres | localhost:5432 | user/pass/db: `backupflow` |
| Prometheus | http://localhost:9090 | metrics store |
| Grafana | http://localhost:3000 | login admin/admin |

If your host IP is e.g. `172.30.1.2` (interface `enp1s0`), use from your laptop browser:

- `http://172.30.1.2:3000` Grafana
- `http://172.30.1.2:8080` API
- `http://172.30.1.2:9090` Prometheus

Notes on `ip addr` output:
- `127.0.0.1 lo` = localhost only inside machine
- `172.30.1.2 enp1s0` = **your machine — use this**
- `172.18.0.1 br-*` = docker-compose bridge network
- `172.17.0.1 docker0` = unused if DOWN
- `cilium_* / lxc* / veth*` = Kubernetes / container veths — ignore

### Verify It's Up

```bash
docker compose ps
curl -s localhost:8080/actuator/health
curl -s -X POST localhost:8080/api/backups \
  -H 'Content-Type: application/json' \
  -d '{"source":"/data/customer-001","destination":"s3://backup-bucket/customer-001"}'
```

`curl localhost:3000` returning `<a href="/login">Found</a>` is **normal** — Grafana redirects to login. Use a browser, or `curl -L`.

### Stop / Reset

```bash
docker compose down        # stop
docker compose down -v     # stop + wipe pgdata
```

### Option B — Local Maven + Local Postgres

```bash
createdb -U postgres backupflow
export DB_URL=jdbc:postgresql://localhost:5432/backupflow
export DB_USERNAME=backupflow
export DB_PASSWORD=backupflow
export SERVER_PORT=8080
mvn spring-boot:run
```

Build / test:

```bash
mvn test
mvn package
java -jar target/*.jar
```

---

## 3. API Reference

| Method | Endpoint | Purpose | Status |
|---|---|---|---|
| POST | `/api/backups` | Create job | 201 |
| GET | `/api/backups` | List jobs | 200 |
| GET | `/api/backups/{id}` | Get one | 200 / 404 |
| POST | `/api/backups/{id}/retry` | Retry FAILED | 202 / 404 / 409 |
| DELETE | `/api/backups/{id}` | Delete | 204 / 404 |

Create:

```bash
curl -s -X POST localhost:8080/api/backups \
  -H 'Content-Type: application/json' \
  -d '{"source":"/data/customer-001","destination":"s3://backup-bucket/customer-001"}' | jq
# {"id":101,"source":"...","destination":"...","status":"PENDING","retryCount":0,...}
# poll after ~2s → SUCCESS
```

Force a failure (to demo retry):

```bash
curl -s -X POST localhost:8080/api/backups \
  -H 'Content-Type: application/json' \
  -d '{"source":"/data/x","destination":"s3://backup-bucket/fail-demo"}' | jq
sleep 3
curl -s localhost:8080/api/backups/1 | jq .status  # FAILED
curl -s -X POST localhost:8080/api/backups/1/retry | jq
```

List / get / delete:

```bash
curl -s localhost:8080/api/backups | jq
curl -s localhost:8080/api/backups/101 | jq
curl -s -X DELETE localhost:8080/api/backups/101 -v
```

Validation errors → `400` with field details. Retry of non-FAILED → `409`. Missing id → `404`.

---

## 4. Prometheus — What To Do

Open `http://<host>:9090`.

### 4.1 Check scrape is UP

`Status -> Targets` → job `backupflow` should be `UP` (scrapes `app:8080/actuator/prometheus` every 10s, config in `docker/prometheus/prometheus.yml`).

If DOWN, app isn't ready yet. Check:

```bash
docker compose logs app --tail=50
# look for: Started BackupFlowApplication
```

### 4.2 Generate data first

Prometheus is empty until you use the API:

```bash
curl -X POST localhost:8080/api/backups -H 'Content-Type: application/json' -d '{"source":"/data/x","destination":"s3://bkt/customer-001"}'
curl -X POST localhost:8080/api/backups -H 'Content-Type: application/json' -d '{"source":"/data/x","destination":"s3://bkt/fail-demo"}'
sleep 3
curl localhost:8080/api/backups
```

### 4.3 Queries to run in Graph -> Execute

Start with these (instant values):

```
backup_jobs_created_total
backup_jobs_succeeded_total
backup_jobs_failed_total
backup_jobs_running
backup_jobs_retries_total
```

Then rates / latency:

```
rate(backup_jobs_created_total[5m])
rate(http_server_requests_seconds_count[1m])
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
rate(backup_job_execution_duration_seconds_count[5m])
```

Verify raw endpoint anytime:

```bash
curl -s localhost:8080/actuator/prometheus | grep backup_jobs
curl -s localhost:9090/-/healthy
```

---

## 5. Grafana — Next Step

1. Open `http://<host>:3000` → login `admin/admin` → `Dashboards` → `BackupFlow V1`
2. Populate it (otherwise empty graphs / No data):
```bash
curl -X POST localhost:8080/api/backups -H 'Content-Type: application/json' -d '{"source":"/data/x","destination":"s3://bkt/fail-demo"}'
sleep 3
ID=$(curl -s localhost:8080/api/backups | jq -r '.[0].id')
curl -X POST localhost:8080/api/backups/$ID/retry
```
3. Refresh after ~15s (scrape interval 10s) — panels for `total / success / failed / running`, latency, 5xx rate should move.

Provisioning is automatic: `docker/grafana/provisioning/datasources/prometheus.yml` points to `http://prometheus:9090`, `docker/grafana/dashboards/backupflow.json` is auto-loaded.

Done = full loop proven: `API -> Postgres -> Prometheus -> Grafana`.

---

## 6. Complete Project Breakdown — File by File

### 6.1 Root / Infra

- **`docker-compose.yml`**: 4 services: `postgres:16` (5432, vol `pgdata`, `pg_isready` healthcheck), `app` (build `.`, waits `service_healthy`, env `DB_URL=jdbc:postgresql://postgres:5432/backupflow`), `prometheus` (9090, mounts `./docker/prometheus/prometheus.yml:ro`), `grafana` (3000, admin/admin, mounts provisioning+dashboards).
- **`Dockerfile`**: 2-stage. Stage 1 `maven:3.9-eclipse-temurin-21`: copy `pom.xml` → `mvn dependency:go-offline` (cache) → copy `src` → `mvn -DskipTests package`. Stage 2 `eclipse-temurin:21-jre`: copy `target/*.jar` → `app.jar`, `EXPOSE 8080`, `ENTRYPOINT java -jar app.jar`.
- **`pom.xml`**: Spring Boot 3.3.5, Java 21, deps `web, data-jpa, validation, actuator, micrometer-registry-prometheus, postgresql (runtime), starter-test + h2 (test)`.
- **`docker/prometheus/prometheus.yml`**: `job_name: backupflow`, `metrics_path: /actuator/prometheus`, `targets: ["app:8080"]`, `scrape_interval: 10s`.
- **`docker/grafana/`**: `provisioning/datasources/prometheus.yml` → `http://prometheus:9090`, `provisioning/dashboards/dashboards.yml` + `dashboards/backupflow.json` → `BackupFlow V1` dashboard.
- **`src/main/resources/application.properties`**: `server.port=${SERVER_PORT:8080}`, `spring.datasource.url=${DB_URL:...}`, `username=${DB_USERNAME:...}`, `password=${DB_PASSWORD:...}`, `ddl-auto=update`, exposes `health,info,metrics,prometheus`, `management.metrics.tags.application=backupflow`.

### 6.2 `src/main/java/com/backupflow/`

**`BackupFlowApplication.java`** — Entry point, `@SpringBootApplication + @EnableAsync` (enables `@Async`).

**`controller/BackupController.java` — Thin HTTP layer, no business logic:**
`@RestController @RequestMapping("/api/backups")`, constructor-injects `BackupService`. Methods: `create(@Valid @RequestBody)` → 201, `list()` → 200, `getOne(id)` → 200, `retry(id)` → 202, `delete(id)` → 204.

**`dto/CreateBackupRequest.java`** — Input: `source, destination` both `@NotBlank`. Bad input → 400.

**`dto/BackupResponse.java`** — Output: `id,source,destination,status,retryCount,createdAt,updatedAt,completedAt`. Static `from(BackupJob)` mapper so entity never leaks.

**`entity/BackupJob.java` + `BackupStatus.java`** — `@Entity @Table(backup_jobs)`: `id IDENTITY, source, destination, status ENUM(PENDING,RUNNING,SUCCESS,FAILED), retry_count, created_at, updated_at, completed_at`. `@PrePersist/@PreUpdate` manage timestamps.

**`repository/BackupRepository.java`** — `extends JpaRepository<BackupJob,Long>`. Provides `save/findAll/findById/existsById/deleteById` with no SQL.

**`exception/`** — `BackupNotFoundException` → 404, `BackupConflictException` → 409, `GlobalExceptionHandler (@RestControllerAdvice)` maps them + `MethodArgumentNotValidException` → 400 `{fields:{...}}`.

**`config/AsyncConfig.java`** — `ThreadPoolTaskExecutor` bean `taskExecutor` (core 2 / max 4 / queue 100, prefix `backup-sim-`). Runs simulation off request thread.

### 6.3 Core Logic: `service/BackupService.java`

This is the state machine + metrics — the major part of the API:

```java
create(req):
  save new BackupJob(PENDING) + createdCounter++
  simulateExecutionAsync(id)   // fire-and-forget
  return PENDING snapshot

simulateExecutionAsync(id):  // @Async @Transactional
  sleep 0.5s                  // make PENDING observable
  find job; if status != PENDING return  // don't clobber deleted
  status=RUNNING; runningJobs++; save
  sleep 1.0s
  if destination.contains("fail") -> FAILED + failedCounter++
  else -> SUCCESS + succeededCounter++
  completedAt=now; save; runningJobs--; executionTimer.record(duration)

retry(id):
  find or throw 404
  if status != FAILED throw 409
  retryCount++; status=PENDING; completedAt=null; save
  retryCounter++; simulateExecutionAsync(id)
  return snapshot (controller sets 202)

list/get/delete: JPA wrappers + 404 guard
```

Metrics registered in constructor via `MeterRegistry`:
- `backup_jobs_created_total`, `backup_jobs_succeeded_total`, `backup_jobs_failed_total`, `backup_jobs_retries_total` (Counters)
- `backup_jobs_running` (Gauge on `AtomicInteger`)
- `backup_job_execution_duration_seconds` (Timer)
- Plus standard `http_server_requests_*`.

Design choices: thin controller (HTTP only), service owns state machine + persistence (testable), transaction guards `retryCount` increment, `PENDING` check prevents race with delete, async keeps `POST` instant while result lands in ~1.5s.

### 6.4 Tests `src/test/java/com/backupflow/`

- `service/BackupServiceTest`: create/fetch/delete/retry, retry conflict (SUCCESS/PENDING → 409), not-found, retryCount increment.
- `controller/BackupControllerTest (@WebMvcTest)`: 201/200/202/204, 400 validation, 404, 409.

Run: `mvn test`.

---

## 7. Configuration (Env Vars)

```
DB_URL=jdbc:postgresql://postgres:5432/backupflow   # localhost locally, postgres in compose
DB_USERNAME=backupflow
DB_PASSWORD=backupflow
SERVER_PORT=8080
```

See `application.properties`.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `permission denied docker.sock` | `sudo docker compose ...` or `sudo usermod -aG docker $USER && newgrp docker` |
| `curl localhost:3000` → `<a href="/login">Found</a>` | Normal, open in browser |
| Page buffering / hanging | App still building (`mvn package` 2-5 min). `docker compose logs app --tail=50`, wait for `Started BackupFlowApplication` |
| `Connection to localhost:5432 refused` | `docker compose up postgres`, check `DB_URL` (local: `localhost`, compose: `postgres`) |
| App starts before DB ready | Compose uses `service_healthy` + `pg_isready`; locally wait for postgres |
| `404` on retry/get | Job id doesn't exist or was deleted |
| `409` on retry | Only `FAILED` can be retried |
| `400` on create | `source`/`destination` must be non-blank |
| Prometheus target DOWN | `curl app:8080/actuator/prometheus` from prometheus net; check `prometheus.yml` uses `app:8080` |
| Grafana no data | Wait scrape 10-15s, check datasource `http://prometheus:9090`, generate traffic via POST |
| Mockito/ByteBuddy error on JDK 25 | Already fixed via ByteBuddy 1.17.5 pin; `mvn test` should pass |
| `mvn package` slow | First run downloads deps; `dependency:go-offline` in Dockerfile caches |

---

## 9. Interview Talking Points

- Why Spring Boot over plain `HttpServer`? DI, validation, JPA, Actuator/metrics, test slices, ecosystem.
- Why Postgres for job state? Durable, transactional state transitions, queryable, `retry_count` + timestamps.
- Why thin controller? HTTP mapping/validation in controller, state machine + persistence in service → testable.
- Async? Currently `@Async` simulation; real engine would use queue + worker pool.
- Backoff? V2: DB `next_retry_at` + exponential delay + jitter.
- Idempotent retry? Guard on `FAILED` only, `retryCount` in same transaction; V2: idempotency key / conditional update.
- Scale horizontally? Stateless API + shared DB; V2: partition jobs, queue, optimistic locking.
- Postgres down? Fail fast, health DOWN, Prometheus alert, no in-memory divergence.
- Monitoring? `http_server_requests` latency/errors + job counters/gauge/timer in Grafana.
- Secure? V2: JWT + RBAC, TLS, secrets via Secret manager.
- K8s later? Deployment + Service + ConfigMap/Secret + probes; HPA on CPU/latency/queue depth.

V2 planned (not in V1): RabbitMQ, Redis, JWT + RBAC, K8s manifests, HPA + load tests, S3, exponential backoff + DLQ, tracing.
