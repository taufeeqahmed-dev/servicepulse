package dev.taufeeqahmed.servicepulse.observability;

import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentStatus;
import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IncidentMetrics {

    private static final Logger logger = LoggerFactory.getLogger(IncidentMetrics.class);
    private final MeterRegistry registry;

    public IncidentMetrics(MeterRegistry registry, IncidentRepository incidents) {
        this.registry = registry;
        recordSafely(() -> {
            registry.counter("servicepulse.incidents");
            for (var event : IncidentTransition.Event.values()) {
                registry.counter("servicepulse.webhook.deliveries", "event", event.name(), "result", "SUCCESS");
                registry.counter("servicepulse.webhook.deliveries", "event", event.name(), "result", "FAILURE");
            }
            Gauge.builder("servicepulse.incidents.open", incidents, repository -> {
                try {
                    return repository.countByStatus(IncidentStatus.OPEN);
                } catch (RuntimeException exception) {
                    logger.warn("Could not read open incident metric");
                    return Double.NaN;
                }
            }).register(registry);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordTransition(IncidentTransition transition) {
        if (transition.event() == IncidentTransition.Event.INCIDENT_OPENED) {
            recordSafely(() -> registry.counter("servicepulse.incidents").increment());
        }
    }

    public void recordWebhookDelivery(IncidentTransition.Event event, boolean success) {
        recordSafely(() -> registry.counter("servicepulse.webhook.deliveries",
                "event", event.name(), "result", success ? "SUCCESS" : "FAILURE").increment());
    }

    private void recordSafely(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException exception) {
            logger.warn("Could not update incident metrics; monitoring will continue");
        }
    }
}
