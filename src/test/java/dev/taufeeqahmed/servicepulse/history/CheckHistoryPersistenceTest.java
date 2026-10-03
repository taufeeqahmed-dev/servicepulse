package dev.taufeeqahmed.servicepulse.history;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import dev.taufeeqahmed.servicepulse.ServicePulseApplication;
import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.checking.CheckResultService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import dev.taufeeqahmed.servicepulse.registration.CreateServiceRequest;
import dev.taufeeqahmed.servicepulse.registration.ServiceRegistrationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CheckHistoryPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void upgradesV07DataAndContinuesRegistrationMonitoringAndRecoveryAcrossRestarts() throws Exception {
        Path database = directory.resolve("legacy");
        String url = "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/');
        long legacyId;
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/v0.7-schema.sql"));
            try (var insert = connection.prepareStatement(
                    "insert into monitored_services(name,url) values ('Existing','http://localhost/health')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    legacyId = keys.getLong(1);
                }
            }
            try (var insert = connection.prepareStatement("""
                    insert into health_checks(service_id,status,http_status,response_time_ms,checked_at)
                    values (?,'DOWN',503,25,TIMESTAMP WITH TIME ZONE '2026-10-03 10:00:00Z')
                    """)) {
                insert.setLong(1, legacyId);
                for (int i = 0; i < 3; i++) { insert.executeUpdate(); }
            }
        }
        long newId;
        long incidentId;
        try (var upgraded = start(database)) {
            var service = upgraded.getBean(MonitoredServiceRepository.class).findById(legacyId).orElseThrow();
            assertThat(service.getFailureThreshold()).isEqualTo(3);
            assertThat(service.getConsecutiveFailures()).isZero();
            assertThat(service.getWebhookUrl()).isNull();
            assertThat(upgraded.getBean(CheckHistoryService.class).history(legacyId).orElseThrow())
                    .hasSize(3).allSatisfy(check -> {
                        assertThat(check.status()).isEqualTo(HealthStatus.DOWN);
                        assertThat(check.checkedAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
                    });
            assertThat(upgraded.getBean(IncidentRepository.class).count()).isZero(); // No replay of old failures.
            var created = upgraded.getBean(ServiceRegistrationService.class).create(
                    new CreateServiceRequest("After upgrade", "http://localhost/new", null, null));
            newId = created.id();
            assertThat(newId).isGreaterThan(legacyId);
            assertThat(created.failureThreshold()).isEqualTo(3);
            assertThat(created.webhookConfigured()).isFalse();
            record(upgraded, newId, HealthStatus.UP);
            record(upgraded, legacyId, HealthStatus.DOWN);
            record(upgraded, legacyId, HealthStatus.DOWN);
            assertThat(upgraded.getBean(IncidentRepository.class).count()).isZero();
            record(upgraded, legacyId, HealthStatus.DOWN);
            var incident = upgraded.getBean(IncidentRepository.class).findAll().getFirst();
            incidentId = incident.getId();
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(upgraded.getBean(HealthCheckRepository.class).findAll())
                    .hasSize(7).extracting(HealthCheck::getId).doesNotHaveDuplicates();
        }
        try (var restarted = start(database)) {
            var metrics = restarted.getBean(MeterRegistry.class);
            assertThat(metrics.get("servicepulse.incidents.open").gauge().value()).isEqualTo(1);
            assertThat(metrics.get("servicepulse.incidents").counter().count()).isZero();
            assertThat(restarted.getBean(MonitoredServiceRepository.class).findById(legacyId).orElseThrow()
                    .getConsecutiveFailures()).isEqualTo(3);
            record(restarted, legacyId, HealthStatus.DOWN);
            assertThat(restarted.getBean(IncidentRepository.class).findAll()).singleElement()
                    .satisfies(incident -> assertThat(incident.getId()).isEqualTo(incidentId));
            record(restarted, legacyId, HealthStatus.UP);
            record(restarted, newId, HealthStatus.DOWN);
            var afterRestart = restarted.getBean(ServiceRegistrationService.class).create(
                    new CreateServiceRequest("After restart", "http://localhost/later", null, null));
            assertThat(afterRestart.id()).isGreaterThan(newId);
            assertThat(metrics.get("servicepulse.incidents.open").gauge().value()).isZero();
        }
        try (var verified = start(database)) {
            var services = verified.getBean(MonitoredServiceRepository.class);
            assertThat(services.count()).isEqualTo(3);
            assertThat(services.findById(legacyId).orElseThrow().getConsecutiveFailures()).isZero();
            assertThat(services.findById(newId).orElseThrow().getConsecutiveFailures()).isEqualTo(1);
            assertThat(verified.getBean(IncidentRepository.class).findById(incidentId)).get().satisfies(incident -> {
                assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
                assertThat(incident.getResolvedAt()).isAfterOrEqualTo(incident.getStartedAt());
            });
            record(verified, newId, HealthStatus.UP);
            var history = verified.getBean(CheckHistoryService.class);
            assertThat(history.history(legacyId).orElseThrow()).hasSize(8);
            assertThat(history.history(newId).orElseThrow()).hasSize(3);
            assertThat(history.stats(legacyId).orElseThrow().totalChecks()).isEqualTo(8);
            assertThat(verified.getBean(HealthCheckRepository.class).findAll())
                    .hasSize(11).extracting(HealthCheck::getId).doesNotHaveDuplicates();
            assertThat(services.findById(newId).orElseThrow().getConsecutiveFailures()).isZero();
        }
    }

    @Test
    void retainsRegistrationsAndHistoryAcrossApplicationRestarts() {
        Path database = directory.resolve("servicepulse");
        Instant checkedAt = Instant.parse("2026-09-28T22:30:00Z");
        long serviceId;
        try (var first = start(database)) {
            var service = first.getBean(MonitoredServiceRepository.class)
                    .save(new MonitoredService("Stored service", "https://example.com"));
            serviceId = service.getId();
            first.getBean(CheckHistoryService.class).record(service,
                    new HealthCheckResponse(serviceId, service.getName(), service.getUrl(),
                            HealthStatus.UP, 200, 123, checkedAt));
        }

        try (var restarted = start(database)) {
            assertThat(restarted.getBean(MonitoredServiceRepository.class).findById(serviceId))
                    .isPresent();
            var history = restarted.getBean(CheckHistoryService.class);
            assertThat(history.history(serviceId).orElseThrow()).singleElement().satisfies(check -> {
                assertThat(check.serviceId()).isEqualTo(serviceId);
                assertThat(check.status()).isEqualTo(HealthStatus.UP);
                assertThat(check.httpStatus()).isEqualTo(200);
                assertThat(check.responseTimeMs()).isEqualTo(123);
                assertThat(check.checkedAt()).isEqualTo(checkedAt);
            });
            var stats = history.stats(serviceId).orElseThrow();
            assertThat(stats.totalChecks()).isEqualTo(1);
            assertThat(stats.uptimePercentage()).isEqualByComparingTo("100.00");
        }
    }

    @Test
    void retainsFailureStreakAndIncidentLifecycleAcrossRestarts() {
        Path database = directory.resolve("incidents");
        long serviceId;
        long incidentId;
        try (var first = start(database)) {
            var service = first.getBean(MonitoredServiceRepository.class)
                    .save(new MonitoredService("Persistent", "http://localhost/health"));
            serviceId = service.getId();
            record(first, serviceId, HealthStatus.DOWN);
            record(first, serviceId, HealthStatus.DOWN);
            assertThat(first.getBean(IncidentRepository.class).count()).isZero();
        }
        try (var second = start(database)) {
            assertThat(second.getBean(MonitoredServiceRepository.class).findById(serviceId).orElseThrow()
                    .getConsecutiveFailures()).isEqualTo(2);
            record(second, serviceId, HealthStatus.DOWN);
            var incident = second.getBean(IncidentRepository.class).findAll().getFirst();
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            incidentId = incident.getId();
        }
        try (var third = start(database)) {
            record(third, serviceId, HealthStatus.DOWN);
            assertThat(third.getBean(IncidentRepository.class).count()).isEqualTo(1);
            record(third, serviceId, HealthStatus.UP);
        }
        try (var fourth = start(database)) {
            assertThat(fourth.getBean(IncidentRepository.class).findById(incidentId)).get().satisfies(incident -> {
                assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
                assertThat(incident.getResolvedAt()).isAfterOrEqualTo(incident.getStartedAt());
            });
            assertThat(fourth.getBean(MonitoredServiceRepository.class).findById(serviceId).orElseThrow()
                    .getConsecutiveFailures()).isZero();
            assertThat(fourth.getBean(CheckHistoryService.class).history(serviceId).orElseThrow()).hasSize(5);
        }
    }

    private void record(ConfigurableApplicationContext context, long serviceId, HealthStatus status) {
        var service = context.getBean(MonitoredServiceRepository.class).findById(serviceId).orElseThrow();
        context.getBean(CheckResultService.class).record(service,
                new HealthCheckResponse(serviceId, service.getName(), service.getUrl(), status,
                        status == HealthStatus.UP ? 200 : 503, 10, Instant.now()));
    }

    private ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ServicePulseApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/'),
                        "--servicepulse.monitoring.enabled=false");
    }
}
