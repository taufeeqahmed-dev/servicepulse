# ServicePulse

ServicePulse is a Java 21 and Spring Boot REST API for monitoring HTTP services. Manual and scheduled checks feed persistent history, uptime statistics, incident detection and optional webhook alerts, with Actuator/Prometheus observability.

**Current release: [v0.7.0](https://github.com/taufeeqahmed-dev/servicepulse/releases/tag/v0.7.0).** This branch implements **v0.8.0 — Incident Detection & Webhook Alerting**, pending release.

## Features

- Validated registration, HTTP checks, response times and UTC timestamps.
- Persistent history, uptime statistics and threshold-based incidents.
- Opening/recovery webhook attempts only after incident transitions commit.
- **113 automated tests**, plus Docker runtime and volume-persistence smoke tests in GitHub Actions.

## Tech stack

Java 21 · Spring Boot · Spring Web · Spring Data JPA · H2 · Maven · JUnit/Mockito · Actuator/Micrometer · Docker · GitHub Actions

## API

Base URL: `http://localhost:8080`. Existing paths are unchanged; incident APIs use `/api`.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Fixed `200 OK` / `UP`. |
| `POST /services` | Register a service; returns `201`. |
| `GET /services` | List services and configuration summary. |
| `POST /services/{id}/check` | Run/persist a check and evaluate incidents. |
| `GET /services/{id}/checks` | Check history, newest first. |
| `GET /services/{id}/stats` | Counts, uptime and response-time statistics. |
| `GET /api/incidents?status=OPEN` | Incident history; optional OPEN/RESOLVED filter. |
| `GET /api/incidents/{id}` | Incident details and resolved duration. |
| `GET /api/services/{serviceId}/incidents` | Service-specific incident history. |
| `GET /actuator/health` | Application health. |
| `GET /actuator/prometheus` | Prometheus metrics. |

HTTP 200–399 means UP; other responses and connection failures/timeouts mean DOWN. Redirects are not followed. Completed checks return API `200` even for DOWN; `httpStatus` is null without a response. Invalid input returns `400`; unknown IDs return `404`.

## Quick start

Install JDK 21 and set `JAVA_HOME`:

```sh
git clone https://github.com/taufeeqahmed-dev/servicepulse.git
cd servicepulse
git switch feature/v0.8-alerting-incidents
./mvnw clean verify
./mvnw spring-boot:run
```

PowerShell: replace `./mvnw` with `.\mvnw.cmd`. Open `pom.xml` with JDK 21 in your IDE. Focused lifecycle/webhook tests use local HTTP servers:

```sh
./mvnw "-Dtest=IncidentServiceTest,WebhookIntegrationTest" test
```

## Example

```http
POST /services
Content-Type: application/json

{"name":"Local API","url":"http://localhost:8080/health","failureThreshold":3}
```

```json
{"id":1,"name":"Local API","url":"http://localhost:8080/health","failureThreshold":3,"webhookConfigured":false}
```

Call `POST /services/1/check`. Add an optional HTTP/HTTPS `webhookUrl` during registration for alerts; its value is not returned by the API.

## Scheduled monitoring

Sequential batches default to a 30-second initial and fixed delay. Configure `servicepulse.monitoring.poll-interval`; disable with `servicepulse.monitoring.enabled=false`. Scheduled batches cannot overlap; manual checks may run concurrently. Both share incident detection and persistence.

## History, uptime and incidents

H2 persists data under `./data/`. Uptime is `UP / total × 100` per attempt; average response time includes failures/timeouts. With no checks, counts are zero and aggregate measurements are null.

The default threshold is **three consecutive failures**; `1` is valid, zero/negative values are rejected. Repeated failures retain one OPEN incident. UP resets the streak and resolves it. Failed checks still record DOWN below the threshold.

Streaks survive restarts. Per-service database locks serialize result processing. Incident start time is threshold detection time; resolved duration is derived from UTC timestamps. Existing registrations gain defaults without replaying history. Settings are supplied at registration; no editing/deletion API is provided.

## Webhooks

Example opening payload:

```json
{"event":"INCIDENT_OPENED","incidentId":10,"serviceId":1,"serviceName":"Local API","status":"DOWN","startedAt":"2026-10-03T14:03:00Z","consecutiveFailures":3}
```

Recovery sends `INCIDENT_RESOLVED`, `status: UP`, `resolvedAt` and `durationSeconds`. Delivery is synchronous **after commit**, bounded by `servicepulse.webhook.request-timeout` (default `3s`), and successful only for HTTP 2xx. Errors cannot roll back monitoring data.

There are no retries or replay: network failures/process crashes can lose alerts, and concurrent deliveries may arrive out of order. Use trusted URLs; validation is not SSRF protection, private networks remain reachable, and payloads are unsigned.

## Observability

Only Actuator health and Prometheus are exposed; health details are hidden.

| Prometheus metric | Measures |
| --- | --- |
| `servicepulse_checks_total{status}` | UP/DOWN attempts; total is their sum. |
| `servicepulse_check_duration_seconds_*` | Check duration count, sum and max. |
| `servicepulse_scheduled_batches_total` | Started batches. |
| `servicepulse_incidents_total` | Committed openings since startup. |
| `servicepulse_incidents_open` | Database count, including after restart. |
| `servicepulse_webhook_deliveries_total{event,result}` | Delivery outcomes. |

Webhook labels use `INCIDENT_OPENED`/`INCIDENT_RESOLVED` and `SUCCESS`/`FAILURE`. No service identities or URLs are labels. Counters reset on restart; check metrics precede persistence. Metric failures cannot interrupt monitoring. Prometheus/Grafana servers are not bundled.

## Docker and CI

```sh
docker build -t servicepulse:local .
docker volume create servicepulse-data
docker run -d --name servicepulse -p 127.0.0.1:8080:8080 --mount "type=volume,source=servicepulse-data,target=/app/data" servicepulse:local
```

The multi-stage Java 21 image runs **non-root**; `/app/data` preserves H2 across container replacement. Override the database location with `SPRING_DATASOURCE_URL`.

[CI](.github/workflows/ci.yml) verifies Maven tests, builds Docker, checks application/Actuator health and confirms registration persistence through a named volume. Webhook tests use no external services.

## Architecture

Thin controllers return DTOs. Manual requests and scheduling share `ServiceCheckService`. `CheckResultService` atomically records history and delegates lifecycle decisions to `IncidentService`. After-commit listeners handle webhooks and incident counters. Network calls stay outside database transactions.

## Roadmap

| Release | Delivered |
| --- | --- |
| v0.1.0 | Foundation and health endpoint. |
| v0.2.0 | Registration. |
| v0.3.0 | Manual checks. |
| v0.4.0 | Scheduling. |
| v0.5.0 | History and uptime. |
| v0.6.0–v0.6.1 | Docker, CI and smoke tests. |
| v0.7.0 — released | Actuator and Prometheus. |
| v0.8.0 — pending release | Incident detection and webhook alerts. |

[Prepared v0.8.0 release notes](docs/releases/v0.8.0.md)
