package dev.taufeeqahmed.servicepulse.observability;

import java.time.Instant;

import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentStatus;
import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentMetricsTest {

    @Test
    void registryFailureDoesNotEscapeInitializationOrIncidentAndDeliveryRecording() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenThrow(new IllegalStateException("Unavailable"));
        when(registry.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("Unavailable"));
        assertThatCode(() -> {
            var metrics = new IncidentMetrics(registry, mock(IncidentRepository.class));
            metrics.recordTransition(new IncidentTransition(IncidentTransition.Event.INCIDENT_OPENED,
                    1, 2, "API", null, Instant.now(), null, 3));
            metrics.recordWebhookDelivery(IncidentTransition.Event.INCIDENT_OPENED, false);
        }).doesNotThrowAnyException();
    }

    @Test
    void openIncidentGaugeUsesDatabaseStateAndReportsUnknownIfDatabaseFails() {
        var incidents = mock(IncidentRepository.class);
        var registry = new SimpleMeterRegistry();
        try {
            new IncidentMetrics(registry, incidents);
            var gauge = registry.get("servicepulse.incidents.open").gauge();
            when(incidents.countByStatus(IncidentStatus.OPEN)).thenReturn(2L, 1L);
            assertThat(gauge.value()).isEqualTo(2);
            assertThat(gauge.value()).isEqualTo(1);
            when(incidents.countByStatus(IncidentStatus.OPEN)).thenThrow(new IllegalStateException("Unavailable"));
            assertThat(gauge.value()).isNaN();
        } finally {
            registry.close();
        }
    }
}
