package dev.taufeeqahmed.servicepulse.history;

import java.nio.file.Path;
import java.time.Instant;

import dev.taufeeqahmed.servicepulse.ServicePulseApplication;
import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class CheckHistoryPersistenceTest {

    @TempDir
    Path directory;

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

    private ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ServicePulseApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/'),
                        "--servicepulse.monitoring.enabled=false");
    }
}
