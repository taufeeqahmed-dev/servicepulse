package dev.taufeeqahmed.servicepulse.observability;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServicePulseMetricsTest {

    private final HealthCheckResponse result = new HealthCheckResponse(1L, "Test", "http://localhost/health",
            HealthStatus.UP, 200, 125, Instant.parse("2026-09-29T12:00:00Z"));

    @Test
    void registryFailureDoesNotPreventInitializationOrEscapeRecording() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenThrow(new IllegalStateException("Registry unavailable"));

        assertThatCode(() -> {
            var metrics = new ServicePulseMetrics(registry);
            metrics.recordCheck(result);
            metrics.recordScheduledBatch();
        }).doesNotThrowAnyException();
    }

    @Test
    void counterFailureDoesNotEscapeCheckOrBatchRecording() {
        var registry = mock(MeterRegistry.class);
        var counter = mock(Counter.class);
        when(registry.counter(anyString())).thenReturn(counter);
        doThrow(new IllegalStateException("Counter failed")).when(counter).increment();
        var metrics = new ServicePulseMetrics(registry);

        assertThatCode(() -> metrics.recordCheck(result)).doesNotThrowAnyException();
        assertThatCode(metrics::recordScheduledBatch).doesNotThrowAnyException();
    }

    @Test
    void timerFailureDoesNotEscapeOrClearAnExistingInterrupt() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenReturn(mock(Counter.class));
        var timer = mock(Timer.class);
        when(registry.timer("servicepulse.check.duration")).thenReturn(timer);
        doThrow(new IllegalStateException("Timer failed")).when(timer).record(125, TimeUnit.MILLISECONDS);
        var metrics = new ServicePulseMetrics(registry);

        try {
            Thread.currentThread().interrupt();
            assertThatCode(() -> metrics.recordCheck(result)).doesNotThrowAnyException();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
