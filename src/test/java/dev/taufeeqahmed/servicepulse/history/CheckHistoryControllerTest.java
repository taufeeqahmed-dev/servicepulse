package dev.taufeeqahmed.servicepulse.history;

import java.time.Instant;

import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = "servicepulse.monitoring.enabled=false")
@AutoConfigureMockMvc
@Transactional
class CheckHistoryControllerTest {

    private static final Instant EARLIER = Instant.parse("2026-09-28T20:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-28T21:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MonitoredServiceRepository services;

    @Autowired
    private HealthCheckRepository checks;

    @Test
    void returnsNewestFirstHistoryForOnlyTheRequestedServiceWithIdAsTieBreaker() throws Exception {
        var service = register("First");
        var otherService = register("Other");
        var latest = save(service, HealthStatus.UP, 200, 100, LATER);
        var oldest = save(service, HealthStatus.DOWN, 503, 200, EARLIER);
        var latestTie = save(service, HealthStatus.DOWN, null, 300, LATER);
        save(otherService, HealthStatus.UP, 204, 1, LATER.plusSeconds(1));

        mockMvc.perform(get("/services/{id}/checks", service.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(latestTie.getId()))
                .andExpect(jsonPath("$[1].id").value(latest.getId()))
                .andExpect(jsonPath("$[2].id").value(oldest.getId()))
                .andExpect(jsonPath("$[0].serviceId").value(service.getId()))
                .andExpect(jsonPath("$[0].status").value("DOWN"))
                .andExpect(jsonPath("$[0].httpStatus").value(nullValue()))
                .andExpect(jsonPath("$[0].responseTimeMs").value(300))
                .andExpect(jsonPath("$[0].checkedAt").value(LATER.toString()))
                .andExpect(jsonPath("$[0].monitoredService").doesNotExist());
    }

    @Test
    void returnsEmptyHistoryForARegisteredServiceWithNoChecks() throws Exception {
        var service = register("Unchecked");

        mockMvc.perform(get("/services/{id}/checks", service.getId()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void calculatesStatsAcrossAllOutcomesForOnlyTheRequestedService() throws Exception {
        var service = register("Measured");
        save(service, HealthStatus.UP, 200, 100, LATER);
        save(service, HealthStatus.UP, 302, 201, EARLIER);
        save(service, HealthStatus.DOWN, null, 300, EARLIER.plusSeconds(1));
        save(register("Other"), HealthStatus.DOWN, 500, 9_000, LATER.plusSeconds(1));

        mockMvc.perform(get("/services/{id}/stats", service.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serviceId").value(service.getId()))
                .andExpect(jsonPath("$.totalChecks").value(3))
                .andExpect(jsonPath("$.upChecks").value(2))
                .andExpect(jsonPath("$.downChecks").value(1))
                .andExpect(jsonPath("$.uptimePercentage").value(66.67))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(200.33))
                .andExpect(jsonPath("$.lastCheckedAt").value(LATER.toString()));
    }

    @ParameterizedTest
    @CsvSource({"UP, 200, 1, 0, 100.0", "DOWN, 503, 0, 1, 0.0"})
    void calculatesStatsWhenEveryCheckHasTheSameStatus(HealthStatus healthStatus, int httpStatus,
            int upChecks, int downChecks, double uptime) throws Exception {
        var service = register("Single outcome");
        save(service, healthStatus, httpStatus, 25, EARLIER);

        mockMvc.perform(get("/services/{id}/stats", service.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(1))
                .andExpect(jsonPath("$.upChecks").value(upChecks))
                .andExpect(jsonPath("$.downChecks").value(downChecks))
                .andExpect(jsonPath("$.uptimePercentage").value(uptime))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(25.0))
                .andExpect(jsonPath("$.lastCheckedAt").value(EARLIER.toString()));
    }

    @Test
    void returnsZeroCountsAndUnknownMetricsWhenThereAreNoChecks() throws Exception {
        var service = register("Unchecked");

        mockMvc.perform(get("/services/{id}/stats", service.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(service.getId()))
                .andExpect(jsonPath("$.totalChecks").value(0))
                .andExpect(jsonPath("$.upChecks").value(0))
                .andExpect(jsonPath("$.downChecks").value(0))
                .andExpect(jsonPath("$.uptimePercentage").value(nullValue()))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(nullValue()))
                .andExpect(jsonPath("$.lastCheckedAt").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"checks", "stats"})
    void returnsEmptyNotFoundForAnUnknownService(String endpoint) throws Exception {
        mockMvc.perform(get("/services/{id}/" + endpoint, Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"checks", "stats"})
    void rejectsNonNumericServiceIds(String endpoint) throws Exception {
        mockMvc.perform(get("/services/not-an-id/" + endpoint))
                .andExpect(status().isBadRequest());
    }

    private MonitoredService register(String name) {
        return services.saveAndFlush(new MonitoredService(name, "https://example.com"));
    }

    private HealthCheck save(MonitoredService service, HealthStatus status,
            Integer httpStatus, long responseTimeMs, Instant checkedAt) {
        return checks.saveAndFlush(new HealthCheck(service, status, httpStatus, responseTimeMs, checkedAt));
    }
}
