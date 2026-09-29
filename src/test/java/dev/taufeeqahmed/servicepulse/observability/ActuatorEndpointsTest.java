package dev.taufeeqahmed.servicepulse.observability;

import java.time.Instant;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "servicepulse.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:servicepulse-actuator-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ActuatorEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServicePulseMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Test
    void actuatorHealthExposesStatusWithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "beans", "metrics"})
    void otherActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }

    @Test
    void prometheusExportsCountersAndDurationInSecondsWithoutServiceLabels() throws Exception {
        String before = scrape();
        assertThat(sample(before, "servicepulse_checks_total")).isZero();
        assertThat(sample(before, "servicepulse_checks_up_total")).isZero();
        assertThat(sample(before, "servicepulse_checks_down_total")).isZero();
        assertThat(sample(before, "servicepulse_check_duration_seconds_count")).isZero();
        assertThat(sample(before, "servicepulse_scheduled_batches_total")).isZero();

        metrics.recordCheck(new HealthCheckResponse(1L, "First", "http://localhost/up",
                HealthStatus.UP, 200, 125, Instant.parse("2026-09-29T12:00:00Z")));
        metrics.recordCheck(new HealthCheckResponse(2L, "Second", "http://localhost/down",
                HealthStatus.DOWN, null, 75, Instant.parse("2026-09-29T12:01:00Z")));
        metrics.recordScheduledBatch();

        String after = scrape();
        assertThat(sample(after, "servicepulse_checks_total")).isEqualTo(2);
        assertThat(sample(after, "servicepulse_checks_up_total")).isEqualTo(1);
        assertThat(sample(after, "servicepulse_checks_down_total")).isEqualTo(1);
        assertThat(sample(after, "servicepulse_check_duration_seconds_count")).isEqualTo(2);
        assertThat(sample(after, "servicepulse_check_duration_seconds_sum")).isCloseTo(0.2, within(0.000001));
        assertThat(sample(after, "servicepulse_scheduled_batches_total")).isEqualTo(1);
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("servicepulse.")))
                .hasSize(5).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus").accept("text/plain;version=0.0.4"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static double sample(String scrape, String name) {
        String line = scrape.lines().filter(value -> value.startsWith(name + " ")).findFirst().orElseThrow();
        return Double.parseDouble(line.substring(name.length() + 1));
    }
}
