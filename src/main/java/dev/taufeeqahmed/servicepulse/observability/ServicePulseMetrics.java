package dev.taufeeqahmed.servicepulse.observability;

import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ServicePulseMetrics {

    private static final Logger logger = LoggerFactory.getLogger(ServicePulseMetrics.class);
    private static final String CHECKS = "servicepulse.checks";
    private static final String CHECK_DURATION = "servicepulse.check.duration";
    private static final String SCHEDULED_BATCHES = "servicepulse.scheduled.batches";

    private final MeterRegistry registry;

    public ServicePulseMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Publish both outcomes at zero; status is the only check label.
        recordSafely(() -> {
            registry.counter(CHECKS, "status", HealthStatus.UP.name());
            registry.counter(CHECKS, "status", HealthStatus.DOWN.name());
            registry.timer(CHECK_DURATION);
            registry.counter(SCHEDULED_BATCHES);
        });
    }

    public void recordCheck(HealthCheckResponse result) {
        recordSafely(() -> {
            // Record one outcome. The completed-check total is derived by summing both series.
            registry.counter(CHECKS, "status", result.status().name()).increment();
            registry.timer(CHECK_DURATION).record(result.responseTimeMs(), TimeUnit.MILLISECONDS);
        });
    }

    public void recordScheduledBatch() {
        recordSafely(() -> registry.counter(SCHEDULED_BATCHES).increment());
    }

    private void recordSafely(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException exception) {
            // Observability is best-effort: registry failures must not stop checks or history writes.
            logger.warn("Could not update ServicePulse metrics; monitoring will continue", exception);
        }
    }
}
