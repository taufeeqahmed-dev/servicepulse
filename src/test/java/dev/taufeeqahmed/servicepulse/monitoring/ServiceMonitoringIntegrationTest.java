package dev.taufeeqahmed.servicepulse.monitoring;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "servicepulse.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:servicepulse-monitoring-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ServiceMonitoringIntegrationTest {

    @Autowired
    private MonitoredServiceRepository repository;

    @Autowired
    private ServiceCheckService checkService;

    @Test
    void checksH2RegistrationsThroughTheExistingHttpCheckService() throws Exception {
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
            repository.saveAndFlush(new MonitoredService("Unavailable", baseUrl + "/down"));
            repository.saveAndFlush(new MonitoredService("Healthy", baseUrl + "/up"));
            var scheduler = new ServiceMonitoringScheduler(repository, checkService);

            scheduler.checkRegisteredServices();

            assertThat(failedRequests.get()).isEqualTo(1);
            assertThat(healthyRequests.get()).isEqualTo(1);
            assertThat(repository.count()).isEqualTo(2);
        } finally {
            server.stop(0);
            repository.deleteAll();
        }
    }
}
