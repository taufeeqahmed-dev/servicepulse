package dev.taufeeqahmed.servicepulse.alerting;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import dev.taufeeqahmed.servicepulse.checking.CheckResultService;
import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentStatus;
import dev.taufeeqahmed.servicepulse.monitoring.ServiceMonitoringScheduler;
import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {"servicepulse.monitoring.enabled=false", "servicepulse.webhook.request-timeout=1s",
        "spring.datasource.url=jdbc:h2:mem:webhook-tests;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Timeout(20)
class WebhookIntegrationTest {

    private static final Instant OPENED = Instant.parse("2026-10-03T14:03:00Z");
    @Autowired private MonitoredServiceRepository services;
    @Autowired private IncidentRepository incidents;
    @Autowired private HealthCheckRepository checks;
    @Autowired private CheckResultService results;
    @Autowired private ServiceCheckService checking;
    @Autowired private ServicePulseMetrics checkMetrics;
    @Autowired private MeterRegistry metrics;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private Clock clock;

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger targetStatus = new AtomicInteger(503);
    private final AtomicInteger webhookStatus = new AtomicInteger(204);
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final List<IncidentStatus> committedStates = new CopyOnWriteArrayList<>();
    private CountDownLatch webhookGate;

    @BeforeEach
    void start() throws Exception {
        incidents.deleteAll();
        checks.deleteAll();
        services.deleteAll();
        when(clock.instant()).thenReturn(OPENED);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/target", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(targetStatus.get(), -1);
            }
        });
        server.createContext("/hook", exchange -> {
            try (exchange) {
                var payload = mapper.readTree(exchange.getRequestBody());
                received.add(payload);
                // The receiver uses a different thread/connection: it must see committed incident state.
                committedStates.add(incidents.findById(payload.get("incidentId").asLong()).orElseThrow().getStatus());
                if (webhookGate != null) {
                    try {
                        if (!webhookGate.await(5, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                exchange.getResponseHeaders().add("Location", baseUrl + "/hook");
                exchange.sendResponseHeaders(webhookStatus.get(), -1);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (webhookGate != null) {
            webhookGate.countDown();
        }
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        incidents.deleteAll();
        checks.deleteAll();
        services.deleteAll();
    }

    @Test
    void manualAndScheduledChecksSendOneOpenAndOneRecoveryAndRecordMetrics() throws Exception {
        var service = register(2, baseUrl + "/hook");
        var scheduler = new ServiceMonitoringScheduler(services, checking, checkMetrics);
        double openings = openedCount();
        double openedDeliveries = deliveries("INCIDENT_OPENED", "SUCCESS");
        double resolvedDeliveries = deliveries("INCIDENT_RESOLVED", "SUCCESS");

        mvc.perform(post("/services/{id}/check", service.getId())).andExpect(status().isOk());
        assertThat(received).isEmpty();
        scheduler.checkRegisteredServices();
        assertThat(received).hasSize(1);
        assertThat(metrics.get("servicepulse.incidents.open").gauge().value()).isEqualTo(1);
        scheduler.checkRegisteredServices();
        assertThat(received).hasSize(1);

        when(clock.instant()).thenReturn(OPENED.plusSeconds(240));
        targetStatus.set(200);
        mvc.perform(post("/services/{id}/check", service.getId())).andExpect(status().isOk());
        scheduler.checkRegisteredServices();
        assertThat(received).hasSize(2);
        var opened = received.getFirst();
        assertThat(opened.get("event").asString()).isEqualTo("INCIDENT_OPENED");
        assertThat(opened.get("serviceId").asLong()).isEqualTo(service.getId());
        assertThat(opened.get("serviceName").asString()).isEqualTo("Local API");
        assertThat(opened.get("status").asString()).isEqualTo("DOWN");
        assertThat(opened.get("consecutiveFailures").asInt()).isEqualTo(2);
        assertThat(opened.get("startedAt").asString()).isEqualTo(OPENED.toString());
        assertThat(opened.has("resolvedAt")).isFalse();
        var resolved = received.getLast();
        assertThat(resolved.get("event").asString()).isEqualTo("INCIDENT_RESOLVED");
        assertThat(resolved.get("incidentId").asLong()).isEqualTo(opened.get("incidentId").asLong());
        assertThat(resolved.get("status").asString()).isEqualTo("UP");
        assertThat(resolved.get("resolvedAt").asString()).isEqualTo(OPENED.plusSeconds(240).toString());
        assertThat(resolved.get("durationSeconds").asLong()).isEqualTo(240);
        assertThat(committedStates).containsExactly(IncidentStatus.OPEN, IncidentStatus.RESOLVED);
        assertThat(incidents.count()).isEqualTo(1);
        assertThat(checks.count()).isEqualTo(5);
        assertThat(openedCount()).isEqualTo(openings + 1);
        assertThat(metrics.get("servicepulse.incidents.open").gauge().value()).isZero();
        assertThat(deliveries("INCIDENT_OPENED", "SUCCESS")).isEqualTo(openedDeliveries + 1);
        assertThat(deliveries("INCIDENT_RESOLVED", "SUCCESS")).isEqualTo(resolvedDeliveries + 1);
        String scrape = mvc.perform(get("/actuator/prometheus").accept("text/plain;version=0.0.4"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(scrape).contains("servicepulse_incidents_total", "servicepulse_incidents_open 0.0",
                "servicepulse_webhook_deliveries_total{event=\"INCIDENT_OPENED\",result=\"SUCCESS\"}");
        assertThat(scrape).doesNotContain(baseUrl, "Local API");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void webhookWaitsForCommitAndIsSuppressedOnRollback(boolean rollback) {
        var service = register(1, baseUrl + "/hook");
        double openings = openedCount();
        when(clock.instant()).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    assertThat(received).isEmpty();
                    assertThat(openedCount()).isEqualTo(openings);
                    if (rollback) {
                        throw new IllegalStateException("Abort before commit");
                    }
                }
            });
            return OPENED;
        });
        if (rollback) {
            assertThatThrownBy(() -> checking.check(service.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Abort before commit");
        } else {
            checking.check(service.getId()).orElseThrow();
        }
        assertThat(received).hasSize(rollback ? 0 : 1);
        assertThat(incidents.count()).isEqualTo(rollback ? 0 : 1);
        assertThat(checks.count()).isEqualTo(rollback ? 0 : 1);
        assertThat(openedCount()).isEqualTo(openings + (rollback ? 0 : 1));
    }

    @Test
    void serviceWithoutWebhookDoesNotAttemptDelivery() {
        var service = register(1, null);
        double success = deliveries("INCIDENT_OPENED", "SUCCESS");
        double failure = deliveries("INCIDENT_OPENED", "FAILURE");
        checking.check(service.getId()).orElseThrow();
        assertThat(incidents.count()).isEqualTo(1);
        assertThat(received).isEmpty();
        assertThat(deliveries("INCIDENT_OPENED", "SUCCESS")).isEqualTo(success);
        assertThat(deliveries("INCIDENT_OPENED", "FAILURE")).isEqualTo(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 503})
    void rejectedWebhookDoesNotUndoMonitoringOrRetryOrStopOtherServices(int statusCode) throws Exception {
        webhookStatus.set(statusCode);
        var service = register(1, baseUrl + "/hook");
        double failures = deliveries("INCIDENT_OPENED", "FAILURE");
        mvc.perform(post("/services/{id}/check", service.getId())).andExpect(status().isOk());
        assertThat(received).hasSize(1);
        assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
        assertThat(deliveries("INCIDENT_OPENED", "FAILURE")).isEqualTo(failures + 1);
        var other = register(3, null);
        targetStatus.set(200);
        new ServiceMonitoringScheduler(services, checking, checkMetrics).checkRegisteredServices();
        assertThat(received).hasSize(2); // One rejected recovery attempt; no retries of either event.
        assertThat(incidents.countByStatus(IncidentStatus.RESOLVED)).isEqualTo(1);
        assertThat(checks.count()).isEqualTo(3);
        assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(other.getId())).hasSize(1);
    }

    @Test
    void webhookTimeoutLeavesIncidentAndHistoryCommitted() {
        webhookGate = new CountDownLatch(1);
        var service = register(1, baseUrl + "/hook");
        double failures = deliveries("INCIDENT_OPENED", "FAILURE");
        assertThat(checking.check(service.getId()).orElseThrow().status()).isEqualTo(HealthStatus.DOWN);
        assertThat(received).hasSize(1);
        assertThat(checks.count()).isEqualTo(1);
        assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
        assertThat(deliveries("INCIDENT_OPENED", "FAILURE")).isEqualTo(failures + 1);
        webhookGate.countDown();
    }

    private MonitoredService register(int threshold, String webhook) {
        return services.save(new MonitoredService("Local API", baseUrl + "/target", threshold, webhook));
    }

    private double openedCount() {
        return metrics.get("servicepulse.incidents").counter().count();
    }

    private double deliveries(String event, String result) {
        return metrics.get("servicepulse.webhook.deliveries").tags("event", event, "result", result).counter().count();
    }
}
