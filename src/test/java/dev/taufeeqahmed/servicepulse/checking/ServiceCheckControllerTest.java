package dev.taufeeqahmed.servicepulse.checking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "servicepulse.monitoring.enabled=false",
        "servicepulse.check.connect-timeout=300ms",
        "servicepulse.check.request-timeout=1s",
        "spring.datasource.url=jdbc:h2:mem:servicepulse-check-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@Timeout(10)
class ServiceCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MonitoredServiceRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HealthCheckRepository checks;

    @Autowired
    private MeterRegistry metrics;

    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        checks.deleteAll();
        repository.deleteAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        checks.deleteAll();
        repository.deleteAll();
    }

    @Test
    void checksRegisteredUrlAndReturnsTimingAndIdentity() throws Exception {
        var method = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        server.createContext("/ready", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200);
        });
        var registered = register("/ready?probe=true");
        Instant before = Instant.now();

        var result = check(registered)
                .andExpect(jsonPath("$.serviceId").value(registered.getId().intValue()))
                .andExpect(jsonPath("$.name").value("Local API"))
                .andExpect(jsonPath("$.url").value(registered.getUrl()))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.httpStatus").value(200))
                .andExpect(jsonPath("$.responseTimeMs").isNumber())
                .andReturn();

        var response = objectMapper.readValue(result.getResponse().getContentAsString(), HealthCheckResponse.class);
        assertThat(response.responseTimeMs()).isNotNegative();
        assertThat(response.checkedAt()).isBetween(before, Instant.now());
        assertThat(method.get()).isEqualTo("GET");
        assertThat(query.get()).isEqualTo("probe=true");
    }

    @ParameterizedTest
    @CsvSource({"204, UP", "399, UP", "400, DOWN", "500, DOWN"})
    void classifiesHttpStatus(int httpStatus, String expectedStatus) throws Exception {
        server.createContext("/status", exchange -> respond(exchange, httpStatus));

        check(register("/status"))
                .andExpect(jsonPath("$.httpStatus").value(httpStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    void reportsRedirectWithoutFollowingIt() throws Exception {
        var redirectedRequests = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", url("/destination"));
            respond(exchange, 302);
        });
        server.createContext("/destination", exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 500);
        });

        check(register("/redirect"))
                .andExpect(jsonPath("$.httpStatus").value(302))
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(redirectedRequests.get()).isZero();
    }

    @Test
    void returnsDownWhenResponseHeadersTimeOut() throws Exception {
        var release = new CountDownLatch(1);
        var received = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                received.countDown();
                await(release);
            }
        });

        try {
            var result = check(register("/slow"))
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.httpStatus").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                    .andReturn();

            var response = objectMapper.readValue(result.getResponse().getContentAsString(), HealthCheckResponse.class);
            assertThat(received.getCount()).isZero();
            assertThat(response.responseTimeMs()).isBetween(500L, 5_000L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void returnsAfterHeadersWithoutWaitingForResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/slow-body", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 1);
                exchange.getResponseBody().flush();
                await(release);
            }
        });

        try {
            check(register("/slow-body"))
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.httpStatus").value(200));
        } finally {
            release.countDown();
        }
    }

    @Test
    void returnsDownWhenConnectionIsRefused() throws Exception {
        var registered = register("/unreachable");
        server.stop(0);
        server = null;

        check(registered)
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.httpStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.responseTimeMs").isNumber())
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());
    }

    @Test
    void returnsNotFoundForUnknownService() throws Exception {
        double countBefore = metrics.get("servicepulse.checks").counter().count();
        mockMvc.perform(post("/services/{id}/check", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        assertThat(checks.count()).isZero();
        assertThat(metrics.get("servicepulse.checks").counter().count()).isEqualTo(countBefore);
    }

    @Test
    void rejectsNonNumericServiceId() throws Exception {
        mockMvc.perform(post("/services/not-an-id/check"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checksAgainWithoutChangingRegisteredService() throws Exception {
        var requests = new AtomicInteger();
        server.createContext("/changing", exchange -> respond(exchange, requests.getAndIncrement() == 0 ? 200 : 503));
        var registered = register("/changing");

        check(registered).andExpect(jsonPath("$.status").value("UP"));
        check(registered)
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.httpStatus").value(503));

        assertThat(requests.get()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1);
        var saved = repository.findById(registered.getId()).orElseThrow();
        assertThat(saved.getName()).isEqualTo(registered.getName());
        assertThat(saved.getUrl()).isEqualTo(registered.getUrl());
    }

    private MonitoredService register(String path) {
        return repository.saveAndFlush(new MonitoredService("Local API", url(path)));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private ResultActions check(MonitoredService registered) throws Exception {
        long countBefore = checks.count();
        double checksBefore = metrics.get("servicepulse.checks").counter().count();
        double upBefore = metrics.get("servicepulse.checks.up").counter().count();
        double downBefore = metrics.get("servicepulse.checks.down").counter().count();
        var duration = metrics.get("servicepulse.check.duration").timer();
        long durationsBefore = duration.count();
        double timeBefore = duration.totalTime(TimeUnit.MILLISECONDS);
        var result = mockMvc.perform(post("/services/{id}/check", registered.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        var response = objectMapper.readValue(result.andReturn().getResponse().getContentAsString(),
                HealthCheckResponse.class);
        assertThat(checks.count()).isEqualTo(countBefore + 1);
        var saved = checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(registered.getId()).getFirst();
        assertThat(saved.getId()).isPositive();
        assertThat(saved.getMonitoredService().getId()).isEqualTo(registered.getId());
        assertThat(saved.getStatus()).isEqualTo(response.status());
        assertThat(saved.getHttpStatus()).isEqualTo(response.httpStatus());
        assertThat(saved.getResponseTimeMs()).isEqualTo(response.responseTimeMs());
        assertThat(saved.getCheckedAt()).isCloseTo(response.checkedAt(), within(1, ChronoUnit.MICROS));
        assertThat(metrics.get("servicepulse.checks").counter().count()).isEqualTo(checksBefore + 1);
        assertThat(metrics.get("servicepulse.checks.up").counter().count())
                .isEqualTo(upBefore + (response.status() == HealthStatus.UP ? 1 : 0));
        assertThat(metrics.get("servicepulse.checks.down").counter().count())
                .isEqualTo(downBefore + (response.status() == HealthStatus.DOWN ? 1 : 0));
        assertThat(duration.count()).isEqualTo(durationsBefore + 1);
        assertThat(duration.totalTime(TimeUnit.MILLISECONDS))
                .isCloseTo(timeBefore + response.responseTimeMs(), within(0.001));
        return result;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
