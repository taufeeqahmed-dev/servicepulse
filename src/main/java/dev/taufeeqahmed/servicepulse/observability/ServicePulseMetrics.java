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
    private static final String UP_CHECKS = "servicepulse.checks.up";
    private static final String DOWN_CHECKS = "servicepulse.checks.down";
    private static final String CHECK_DURATION = "servicepulse.check.duration";
    private static final String SCHEDULED_BATCHES = "servicepulse.scheduled.batches";

    private final MeterRegistry registry;

    public ServicePulseMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Publish zero-valued meters before the first check, without any service labels.
        recordSafely(() -> {
            registry.counter(CHECKS);
            registry.counter(UP_CHECKS);
            registry.counter(DOWN_CHECKS);
            registry.timer(CHECK_DURATION);
            registry.counter(SCHEDULED_BATCHES);
        });
    }

    public void recordCheck(HealthCheckResponse result) {
        recordSafely(() -> {
            registry.counter(CHECKS).increment();
            registry.counter(result.status() == HealthStatus.UP ? UP_CHECKS : DOWN_CHECKS).increment();
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
