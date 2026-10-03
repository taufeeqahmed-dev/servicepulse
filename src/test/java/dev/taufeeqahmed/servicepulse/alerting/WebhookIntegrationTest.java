package dev.taufeeqahmed.servicepulse.alerting;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
    @Autowired private ServiceCheckService checking;
    @Autowired private ServicePulseMetrics checkMetrics;
    @Autowired private MeterRegistry metrics;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock clock;

    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicInteger targetStatus = new AtomicInteger(503);
    private final AtomicInteger webhookStatus = new AtomicInteger(204);
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final List<IncidentStatus> committedStates = new CopyOnWriteArrayList<>();

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
                exchange.getResponseHeaders().add("Location", baseUrl + "/hook");
                exchange.sendResponseHeaders(webhookStatus.get(), -1);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() throws Exception {
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

    @Test
    void failedRecoveryFlushRollsBackStateHistoryAndDeliveryThenAllowsRecovery() {
        var service = register(1, baseUrl + "/hook");
        checking.check(service.getId()).orElseThrow();
        targetStatus.set(500);
        checking.check(service.getId()).orElseThrow();
        long incidentId = incidents.findAll().getFirst().getId();
        var historyIds = checks.findAll().stream().map(check -> check.getId()).toList();
        double openings = openedCount();
        double recoverySuccess = deliveries("INCIDENT_RESOLVED", "SUCCESS");
        double recoveryFailure = deliveries("INCIDENT_RESOLVED", "FAILURE");
        double upAttempts = metrics.get("servicepulse.checks").tag("status", "UP").counter().count();
        // A real database constraint rejects the dirty incident UPDATE during the commit flush.
        jdbc.execute("alter table incidents add constraint test_reject_recovery check (status = 'OPEN')");
        targetStatus.set(200);
        try {
            assertThatThrownBy(() -> checking.check(service.getId())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(incidents.findById(incidentId)).get().satisfies(incident -> {
                assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
                assertThat(incident.getResolvedAt()).isNull();
                assertThat(incident.getInitialStatusCode()).isEqualTo(503);
                assertThat(incident.getLatestStatusCode()).isEqualTo(500);
            });
            assertThat(services.findById(service.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(2);
            assertThat(checks.findAll()).extracting(check -> check.getId()).containsExactlyElementsOf(historyIds);
            assertThat(received).hasSize(1);
            assertThat(openedCount()).isEqualTo(openings);
            assertThat(metrics.get("servicepulse.incidents.open").gauge().value()).isEqualTo(1);
            assertThat(deliveries("INCIDENT_RESOLVED", "SUCCESS")).isEqualTo(recoverySuccess);
            assertThat(deliveries("INCIDENT_RESOLVED", "FAILURE")).isEqualTo(recoveryFailure);
            // v0.7 check metrics count HTTP attempts even when the subsequent persistence fails.
            assertThat(metrics.get("servicepulse.checks").tag("status", "UP").counter().count())
                    .isEqualTo(upAttempts + 1);
        } finally {
            jdbc.execute("alter table incidents drop constraint test_reject_recovery");
        }
        checking.check(service.getId()).orElseThrow();
        assertThat(incidents.findById(incidentId).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(services.findById(service.getId()).orElseThrow().getConsecutiveFailures()).isZero();
        assertThat(checks.count()).isEqualTo(3);
        assertThat(received).hasSize(2);
        assertThat(deliveries("INCIDENT_RESOLVED", "SUCCESS")).isEqualTo(recoverySuccess + 1);
    }

    @Test
    void interruptedHealthRequestPersistsButDoesNotStartWebhookDelivery() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/cancelled-target", exchange -> {
            try (exchange) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        var service = services.save(new MonitoredService("Cancelled", baseUrl + "/cancelled-target", 1,
                baseUrl + "/hook"));
        var worker = new AtomicReference<Thread>();
        var interruptedOnReturn = new AtomicBoolean();
        double openings = openedCount();
        var attempt = executor.submit(() -> {
            worker.set(Thread.currentThread());
            try {
                return checking.check(service.getId()).orElseThrow();
            } finally {
                interruptedOnReturn.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.get().interrupt();
            var result = attempt.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
            assertThat(result.httpStatus()).isNull();
            assertThat(interruptedOnReturn).isTrue();
            assertThat(checks.count()).isEqualTo(1);
            assertThat(services.findById(service.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(1);
            assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
            assertThat(openedCount()).isEqualTo(openings + 1);
            assertThat(received).isEmpty();
        } finally {
            release.countDown();
        }
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
    void webhookTimeoutCancelsConnectionWithoutRemoteClosure() throws Exception {
        assertWebhookClosesConnection(false);
    }

    @Test
    void webhookClosesStalledResponseBodyWithoutReadingIt() throws Exception {
        assertWebhookClosesConnection(true);
    }

    private void assertWebhookClosesConnection(boolean sendHeaders) throws Exception {
        var arrived = new CountDownLatch(1);
        var accepted = new AtomicReference<Socket>();
        try (var listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            var disconnected = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    accepted.set(socket);
                    readWebhookRequest(socket.getInputStream());
                    if (sendHeaders) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    arrived.countDown();
                    // Never send a body, close the server connection, or impose a server-side timeout.
                    // EOF must come from client cancellation/response-stream closure.
                    return socket.getInputStream().read();
                }
            });
            var service = register(1, "http://127.0.0.1:" + listener.getLocalPort() + "/hook");
            String outcome = sendHeaders ? "SUCCESS" : "FAILURE";
            double before = deliveries("INCIDENT_OPENED", outcome);
            long started = System.nanoTime();
            var attempt = executor.submit(() -> checking.check(service.getId()).orElseThrow());
            try {
                assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(attempt.get(2, TimeUnit.SECONDS).status()).isEqualTo(HealthStatus.DOWN);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(elapsed).isLessThan(3_000);
                if (!sendHeaders) {
                    assertThat(elapsed).isGreaterThanOrEqualTo(750); // Configured client timeout is one second.
                }
                assertThat(disconnected.get(1, TimeUnit.SECONDS)).isEqualTo(-1);
                assertThat(checks.count()).isEqualTo(1);
                assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
                assertThat(deliveries("INCIDENT_OPENED", outcome)).isEqualTo(before + 1);
            } finally {
                if (accepted.get() != null) {
                    accepted.get().close();
                }
                attempt.cancel(true);
            }
        }
    }

    private static void readWebhookRequest(InputStream input) throws IOException {
        var header = new ByteArrayOutputStream();
        while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = input.read();
            if (next < 0 || header.size() > 16_384) {
                throw new IOException("Incomplete webhook request headers");
            }
            header.write(next);
        }
        int length = header.toString(StandardCharsets.US_ASCII).lines()
                .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                .mapToInt(line -> Integer.parseInt(line.substring(15).trim())).findFirst().orElseThrow();
        assertThat(input.readNBytes(length)).hasSize(length);
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
