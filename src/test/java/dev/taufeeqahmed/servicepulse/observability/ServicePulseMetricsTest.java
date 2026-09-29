package dev.taufeeqahmed.servicepulse.observability;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServicePulseMetricsTest {

    private final HealthCheckResponse result = new HealthCheckResponse(1L, "Test", "http://localhost/health",
            HealthStatus.UP, 200, 125, Instant.parse("2026-09-29T12:00:00Z"));

    @ParameterizedTest
    @EnumSource(HealthStatus.class)
    void eachCheckIncrementsOnlyItsOutcomeWithNoIndependentTotal(HealthStatus status) {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ServicePulseMetrics(registry);
            var up = registry.get("servicepulse.checks").tag("status", "UP").counter();
            var down = registry.get("servicepulse.checks").tag("status", "DOWN").counter();
            assertThat(up.count() + down.count()).isZero();

            // Verify cumulative recording as well as the first increment.
            for (int completed = 1; completed <= 3; completed++) {
                metrics.recordCheck(new HealthCheckResponse(1L, "Test", "http://localhost/health",
                        status, status == HealthStatus.UP ? 200 : 503, 125, result.checkedAt()));
                assertThat(up.count()).isEqualTo(status == HealthStatus.UP ? completed : 0);
                assertThat(down.count()).isEqualTo(status == HealthStatus.DOWN ? completed : 0);
                assertThat(up.count() + down.count()).isEqualTo(completed);
            }
            assertThat(registry.getMeters().stream()
                    .filter(meter -> meter.getId().getName().startsWith("servicepulse.checks")))
                    .hasSize(2).allSatisfy(meter -> {
                        assertThat(meter.getId().getName()).isEqualTo("servicepulse.checks");
                        assertThat(meter.getId().getTags()).containsExactly(
                                Tag.of("status", meter.getId().getTag("status")));
                        assertThat(meter.getId().getTag("status")).isIn("UP", "DOWN");
                    });
        } finally {
            registry.close();
        }
    }

    @Test
    void registryFailureDoesNotPreventInitializationOrEscapeRecording() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenThrow(new IllegalStateException("Registry unavailable"));
        when(registry.counter(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Registry unavailable"));

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
        when(registry.counter("servicepulse.checks", "status", "UP")).thenReturn(counter);
        doThrow(new IllegalStateException("Counter failed")).when(counter).increment();
        var metrics = new ServicePulseMetrics(registry);

        assertThatCode(() -> metrics.recordCheck(result)).doesNotThrowAnyException();
        assertThatCode(metrics::recordScheduledBatch).doesNotThrowAnyException();
        verify(registry, never()).counter("servicepulse.checks");
    }

    @Test
    void timerFailureDoesNotEscapeOrClearAnExistingInterrupt() {
        var registry = mock(MeterRegistry.class);
        when(registry.counter(anyString())).thenReturn(mock(Counter.class));
        var outcome = mock(Counter.class);
        when(registry.counter("servicepulse.checks", "status", "UP")).thenReturn(outcome);
        var timer = mock(Timer.class);
        when(registry.timer("servicepulse.check.duration")).thenReturn(timer);
        doThrow(new IllegalStateException("Timer failed")).when(timer).record(125, TimeUnit.MILLISECONDS);
        var metrics = new ServicePulseMetrics(registry);

        try {
            Thread.currentThread().interrupt();
            assertThatCode(() -> metrics.recordCheck(result)).doesNotThrowAnyException();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(outcome).increment();
        } finally {
            Thread.interrupted();
        }
    }
}
