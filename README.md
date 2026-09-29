# ServicePulse

ServicePulse is a Java 21 and Spring Boot REST API for monitoring HTTP services. It combines manual and scheduled checks with persistent history, uptime statistics, and Actuator/Prometheus observability.

**Current release: [v0.7.0](https://github.com/taufeeqahmed-dev/servicepulse/releases/tag/v0.7.0) — released**

## Features

- Validated service registration and HTTP checks with failure handling.
- UP/DOWN results, response times, UTC timestamps and per-service uptime.
- **73 automated tests**, plus Docker runtime and volume-persistence smoke tests in GitHub Actions.

## Tech stack

Java 21 · Spring Boot · Spring Web · Spring Data JPA · H2 · Maven · JUnit/Mockito · Actuator/Micrometer · Docker · GitHub Actions

## API

Base URL: `http://localhost:8080`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Fixed `200 OK` / `UP`. |
| `POST /services` | Register a service; returns `201`. |
| `GET /services` | List services. |
| `POST /services/{id}/check` | Run and persist a check. |
| `GET /services/{id}/checks` | History, newest first. |
| `GET /services/{id}/stats` | Counts, uptime and response-time statistics. |
| `GET /actuator/health` | Application health. |
| `GET /actuator/prometheus` | Prometheus metrics. |

HTTP 200–399 means UP; other responses, timeouts and connection failures mean DOWN. Redirects are not followed. A completed check returns API `200` even for DOWN; `httpStatus` is null without an HTTP response. Invalid registration returns `400`; unknown service IDs return `404`.

## Quick start

Install JDK 21 and set `JAVA_HOME`:

```sh
git clone https://github.com/taufeeqahmed-dev/servicepulse.git
cd servicepulse
./mvnw clean verify
./mvnw spring-boot:run
```

PowerShell: replace `./mvnw` with `.\mvnw.cmd`. For IDE use, open `pom.xml` with JDK 21.

## Example

Register a target:

```http
POST /services
Content-Type: application/json

{"name":"Local API","url":"http://localhost:8080/health"}
```

Call `POST /services/{returnedId}/check`. Example response:

```json
{
  "serviceId": 1,
  "name": "Local API",
  "url": "http://localhost:8080/health",
  "status": "UP",
  "httpStatus": 200,
  "responseTimeMs": 12,
  "checkedAt": "2026-09-29T15:00:00Z"
}
```

## Scheduled monitoring

Sequential batches run after an initial delay and a fixed delay between completed batches; both default to 30 seconds. Configure `servicepulse.monitoring.poll-interval`; disable with `servicepulse.monitoring.enabled=false`. Failures are isolated. Scheduled batches cannot overlap within one instance; manual checks may run concurrently.

## History and uptime

H2 persists registrations and results under `./data/`. Statistics use database aggregates: uptime is `UP / total × 100` per attempt; average response time includes failures and timeouts. With no checks, counts are zero; uptime, average and latest timestamp are null.

## Observability

Only Actuator health and Prometheus are exposed; health details are hidden.

| Metric | Measures |
| --- | --- |
| `servicepulse_checks_total{status="UP"}` | UP checks. |
| `servicepulse_checks_total{status="DOWN"}` | DOWN checks. |
| `servicepulse_check_duration_seconds_*` | Duration: count, sum and max. |
| `servicepulse_scheduled_batches_total` | Started batches. |

Total checks are the sum of UP and DOWN; no independent total exists. The only custom check label is `status`. Metrics count attempts before persistence and reset on restart. Recording failures cannot stop monitoring but may lose samples. Prometheus/Grafana servers are not bundled.

## Docker and CI

```sh
docker build -t servicepulse:local .
docker volume create servicepulse-data
docker run -d --name servicepulse -p 127.0.0.1:8080:8080 --mount "type=volume,source=servicepulse-data,target=/app/data" servicepulse:local
```

The multi-stage Java 21 image runs **non-root**. Its named volume preserves H2 data under `/app/data`.

[CI](.github/workflows/ci.yml) verifies tests before building Docker, checks application/Actuator health, and confirms registrations survive container replacement using the same volume.

## Architecture

Thin controllers return DTOs. Manual requests and `ServiceMonitoringScheduler` share `ServiceCheckService`, reusing Java's HTTP client, `ServicePulseMetrics` and `CheckHistoryService`. Each check writes history once; database transactions exclude HTTP calls.

## Roadmap

| Release | Delivered |
| --- | --- |
| v0.1.0 | Foundation and health endpoint. |
| v0.2.0 | Registration. |
| v0.3.0 | Manual checks. |
| v0.4.0 | Scheduling. |
| v0.5.0 | History and uptime. |
| v0.6.0–v0.6.1 | Docker, CI and smoke tests. |
| **v0.7.0 — released** | Actuator and Prometheus. |
