package dev.taufeeqahmed.servicepulse.monitoring;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
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

    @Test
    void checksH2RegistrationsAndPersistsEachScheduledResultOnce() throws Exception {
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
            var scheduler = new ServiceMonitoringScheduler(repository, checkService);

            scheduler.checkRegisteredServices();

            assertThat(failedRequests.get()).isEqualTo(1);
            assertThat(healthyRequests.get()).isEqualTo(1);
            assertThat(repository.count()).isEqualTo(2);
            assertThat(checks.count()).isEqualTo(2);
            assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(down.getId()))
                    .singleElement().satisfies(check -> {
                        assertThat(check.getStatus()).isEqualTo(HealthStatus.DOWN);
                        assertThat(check.getHttpStatus()).isEqualTo(503);
                    });
            assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(up.getId()))
                    .singleElement().satisfies(check -> {
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
