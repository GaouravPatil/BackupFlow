# BackupFlow V1 — Java Backup Job Management Service

> "A small enterprise-style Java service I built to practice the complete software engineering lifecycle — from REST API and database design through testing, containerization, CI and observability."

V1 implements a production-shaped REST service for creating and tracking backup jobs, persisting state in H2 (local) or PostgreSQL (Docker), testing, containerizing with Docker, CI with GitHub Actions, and metrics via Actuator → Prometheus → Grafana.

**future Implementation:** real file copy to S3, RabbitMQ, Redis, Kubernetes, JWT auth, scheduler, multi-region replication.

## Architecture

```
Client (curl)
  ↓ HTTP/JSON :8080
Spring Boot REST API (controller → service → JPA)
  ↓
H2 in-memory (local) / PostgreSQL :5432 (Docker)

Actuator /actuator/prometheus → Prometheus :9090 → Grafana :3000
```

Packages: `controller` (thin), `service` (business logic + state machine), `repository` (JPA), `entity`/`dto`, `exception`, `config`.

## How it works (beginner-friendly)

Think of a courier office:

| Office | BackupFlow |
|---|---|
| You fill a parcel form | `POST /api/backups` with JSON |
| Clerk checks the form | Validation (`source`/`destination` must not be blank) |
| Form is written in the register | Job row saved in `backup_jobs` |
| Worker processes the parcel | Status moves `PENDING → RUNNING → SUCCESS/FAILED` (simulated) |
| You ask where it is | `GET /api/backups/{id}` |
| Parcel failed, resend it | `POST /api/backups/{id}/retry` |

Request flow: `Client → BackupController → BackupService → BackupRepository → DB`.
The controller only handles HTTP; the service owns the rules/state machine; the repository handles database access.

| Layer | File | Responsibility |
|---|---|---|
| HTTP | `controller/BackupController.java` | URLs and status codes (201/200/202/204) |
| Business logic | `service/BackupService.java` | State machine, retry rules, metrics, simulation |
| Persistence | `repository/BackupRepository.java` | CRUD via Spring Data JPA |
| Data model | `entity/BackupJob.java`, `entity/BackupStatus.java` | Table + allowed statuses |
| JSON | `dto/CreateBackupRequest.java`, `dto/BackupResponse.java` | Input validation / output shape |
| Errors | `exception/GlobalExceptionHandler.java` | 400/404/409 JSON responses |
| Demo data | `config/DataSeeder.java` | Seeds 3 jobs when the table is empty |

## Job state machine

```
PENDING → RUNNING → SUCCESS
                  ↘ FAILED → (POST /retry) → PENDING → RUNNING ...
```

V1 has no real backup engine. `BackupService.simulateExecutionAsync` simulates execution asynchronously (`PENDING → RUNNING → SUCCESS/FAILED` with ~1.5s delay). Deterministic failure hook for demos/tests: if `destination` contains `fail` (case-insensitive) the job ends `FAILED`, otherwise `SUCCESS`.

## Tech

Java 21, Spring Boot 3.3.5, Spring Web / Data JPA / Validation, embedded H2 for local runs, PostgreSQL 16 for Docker Compose, Maven, JUnit 5 + Mockito + AssertJ, Docker Compose, GitHub Actions, Actuator + Micrometer Prometheus.

## Quickstart

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

Services:

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| Postgres | localhost:5432 (user/pass/db: `backupflow`) |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

### Option B — Local Maven (embedded H2, no Postgres needed)

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` with an in-memory H2 database. Open the H2 console at `http://localhost:8080/h2-console`:

| Field | Value |
|---|---|
| JDBC URL | `jdbc:h2:mem:backupflow` |
| User | `sa` |
| Password | *(blank)* |

Run `SELECT * FROM BACKUP_JOBS;` to inspect the data. H2 is in-memory, so data resets on every restart and is re-seeded automatically.

### Option C — Local Maven + local Postgres

```bash
createdb -U postgres backupflow
export DB_URL=jdbc:postgresql://localhost:5432/backupflow
export DB_USERNAME=backupflow
export DB_PASSWORD=backupflow
mvn spring-boot:run
```

Build / test:

```bash
mvn test
mvn package
java -jar target/*.jar
```

## Demo data

`config/DataSeeder.java` runs on startup and inserts demo jobs **only when `backup_jobs` is empty**:

| Source | Destination | Final status |
|---|---|---|
| `/data/customer-001` | `s3://backup-bucket/customer-001` | `SUCCESS` |
| `/data/customer-002` | `s3://backup-bucket/customer-002` | `SUCCESS` |
| `/data/customer-003` | `s3://backup-bucket/fail-demo` | `FAILED` |

This makes the API, H2 console and dashboards non-empty immediately. Restarting H2 clears the data and the seeder runs again.

## API

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

## Metrics / observability

- Actuator: `GET /actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Custom V1 metrics:
  - `backup_jobs_created_total`, `backup_jobs_succeeded_total`, `backup_jobs_failed_total`
  - `backup_jobs_retries_total`, `backup_jobs_running` (gauge)
  - `backup_job_execution_duration_seconds` (timer)
  - Standard `http_server_requests_*` (count/latency/status)
- Prometheus scrapes `app:8080/actuator/prometheus` (see `docker/prometheus/prometheus.yml`).
- Grafana auto-provisions Prometheus datasource + `BackupFlow V1` dashboard (`docker/grafana/...`). Suggested panels: total/success/failure/running, success-failure rate, p95 latency, 5xx rate, avg job duration.

Verify:

```bash
curl -s localhost:8080/actuator/prometheus | grep backup_jobs
# Prometheus UI → Status → Targets should show backupflow UP
# Grafana http://localhost:3000 → Dashboards → BackupFlow V1
```

## Configuration

Defaults to embedded H2; every value is env-overridable (see `application.properties`):

```
DB_URL=jdbc:h2:mem:backupflow;DB_CLOSE_DELAY=-1   # or jdbc:postgresql://postgres:5432/backupflow
DB_USERNAME=sa                                     # backupflow for Postgres
DB_PASSWORD=                                       # <local-secret> for Postgres
SERVER_PORT=8080
```

The H2 web console is enabled at `/h2-console` for local development.

## Testing

```bash
mvn test
```

- `BackupServiceTest`: create/fetch/delete/retry, retry conflict (SUCCESS/PENDING → 409), not-found, validation of retryCount increment.
- `BackupControllerTest` (`@WebMvcTest`): 201/200/202/204, 400 validation, 404, 409.
- Target: green before every push. Note: `pom.xml` pins `byte-buddy 1.17.5` so tests also pass on JDK 24/25 locally; CI uses Java 21.

## CI

`.github/workflows/ci.yml`: checkout → setup Java 21 → `mvn test` → `mvn package` → `docker build`. Fails on compile/test errors. Image publishing is V2.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Connection to localhost:5432 refused` | Only in Postgres mode: `docker compose up postgres`, check `DB_URL` (local: `localhost`, compose: `postgres`); unset `DB_URL` to use H2 |
| H2 console empty / no rows | Wait for startup seeding (~5s), refresh; use JDBC URL `jdbc:h2:mem:backupflow`, user `sa`, blank password |
| App starts before DB ready | Compose uses `service_healthy` + `pg_isready`; locally wait for postgres |
| `404` on retry/get | Job id doesn't exist or was deleted |
| `409` on retry | Only `FAILED` can be retried |
| `400` on create | `source`/`destination` must be non-blank |
| Prometheus target DOWN | `curl app:8080/actuator/prometheus` from prometheus net; check `docker/prometheus/prometheus.yml` uses `app:8080` |
| Grafana no data | Wait for scrape interval (10s), check datasource `http://prometheus:9090` |
| Mockito/ByteBuddy error on JDK 25 | Already fixed via ByteBuddy 1.17.5 pin; `mvn test` should pass |
| `mvn package` slow | First run downloads deps; `dependency:go-offline` in Dockerfile caches |

## Interview talking points

- Why Spring Boot over plain `HttpServer`? DI, validation, JPA, Actuator/metrics, test slices, ecosystem.
- Why Postgres for job state? Durable, transactional state transitions, queryable, fits job lifecycle; `retry_count`, timestamps.
- Why thin controller? HTTP mapping/validation in controller, state machine + persistence in service → testable.
- Async? Currently `@Async` simulation; real engine would use queue + worker pool.
- Backoff? V2: DB `next_retry_at` + exponential delay + jitter.
- Idempotent retry? Guard on `FAILED` only, `retryCount` increment in same transaction; V2: idempotency key / conditional update.
- Scale horizontally? Stateless API + shared DB; V2: partition jobs, queue, optimistic locking.
- Postgres down? Fail fast, health check DOWN, Prometheus alert, retries; no in-memory divergence.
- Monitoring? `http_server_requests` latency/errors + job counters/gauge/timer in Grafana.
- Secure? V2: JWT + RBAC, TLS, secrets via Secret manager.
- K8s later? Deployment + Service + ConfigMap/Secret + probes; HPA on CPU/latency/queue depth.

## V2 (planned, not in V1)

RabbitMQ async processing, Redis cache/rate-limit, JWT + RBAC, K8s manifests + probes, HPA + load tests, S3 integration, exponential backoff + DLQ, tracing.
# BackupFlow
