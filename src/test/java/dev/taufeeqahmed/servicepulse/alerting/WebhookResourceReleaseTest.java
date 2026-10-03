package dev.taufeeqahmed.servicepulse.alerting;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties = {"servicepulse.monitoring.enabled=false", "servicepulse.webhook.request-timeout=10s",
        "spring.datasource.url=jdbc:h2:mem:webhook-resource-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=500"})
@Timeout(20)
class WebhookResourceReleaseTest {

    @Autowired private ServiceCheckService checking;
    @Autowired private MonitoredServiceRepository services;
    @Autowired private IncidentRepository incidents;
    @Autowired private HealthCheckRepository checks;
    @Autowired private HikariDataSource dataSource;
    @Autowired private MeterRegistry metrics;

    @Test
    void blockedWebhookDoesNotRetainTheOnlyDatabaseConnection() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/target", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(503, -1); }
        });
        server.createContext("/hook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var service = services.save(new MonitoredService("Blocked hook", base + "/target", 1, base + "/hook"));
            var checkingTask = executor.submit(() -> checking.check(service.getId()).orElseThrow());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(checkingTask.isDone()).isFalse();
                assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
                var other = executor.submit(() -> services.save(new MonitoredService("Unrelated", base + "/target")));
                assertThat(other.get(1, TimeUnit.SECONDS).getId()).isPositive();
                assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
                assertThat(checks.count()).isEqualTo(1);
                assertThat(checkingTask.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            checkingTask.get(5, TimeUnit.SECONDS);
            assertThat(metrics.get("servicepulse.webhook.deliveries")
                    .tags("event", "INCIDENT_OPENED", "result", "SUCCESS").counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            incidents.deleteAll();
            checks.deleteAll();
            services.deleteAll();
        }
    }
}
