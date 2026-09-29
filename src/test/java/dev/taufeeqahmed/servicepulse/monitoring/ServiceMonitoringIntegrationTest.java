package dev.taufeeqahmed.servicepulse.monitoring;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "servicepulse.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:servicepulse-monitoring-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ServiceMonitoringIntegrationTest {

    @Autowired
    private MonitoredServiceRepository repository;

    @Autowired
    private ServiceCheckService checkService;

    @Autowired
    private HealthCheckRepository checks;

    @Autowired
    private ServicePulseMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void manualAndScheduledChecksShareMetricsAndPersistEachResultOnce() throws Exception {
        var failedRequests = new AtomicInteger();
        var healthyRequests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/down", exchange -> {
            try (exchange) {
                failedRequests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.createContext("/up", exchange -> {
            try (exchange) {
                healthyRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var down = repository.saveAndFlush(new MonitoredService("Unavailable", baseUrl + "/down"));
            var up = repository.saveAndFlush(new MonitoredService("Healthy", baseUrl + "/up"));
            var scheduler = new ServiceMonitoringScheduler(repository, checkService, metrics);

            mockMvc.perform(post("/services/{id}/check", up.getId())).andExpect(status().isOk());
            scheduler.checkRegisteredServices();

            assertThat(failedRequests.get()).isEqualTo(1);
            assertThat(healthyRequests.get()).isEqualTo(2);
            assertThat(repository.count()).isEqualTo(2);
            assertThat(checks.count()).isEqualTo(3);
            double upCount = registry.get("servicepulse.checks").tag("status", "UP").counter().count();
            double downCount = registry.get("servicepulse.checks").tag("status", "DOWN").counter().count();
            assertThat(upCount).isEqualTo(2);
            assertThat(downCount).isEqualTo(1);
            assertThat(upCount + downCount).isEqualTo(checks.count());
            assertThat(registry.get("servicepulse.check.duration").timer().count()).isEqualTo(3);
            assertThat(registry.get("servicepulse.scheduled.batches").counter().count()).isEqualTo(1);
            assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(down.getId()))
                    .singleElement().satisfies(check -> {
                        assertThat(check.getStatus()).isEqualTo(HealthStatus.DOWN);
                        assertThat(check.getHttpStatus()).isEqualTo(503);
                    });
            assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(up.getId()))
                    .hasSize(2).allSatisfy(check -> {
                        assertThat(check.getStatus()).isEqualTo(HealthStatus.UP);
                        assertThat(check.getHttpStatus()).isEqualTo(200);
                    });
        } finally {
            server.stop(0);
            checks.deleteAll();
            repository.deleteAll();
        }
    }
}
