package dev.taufeeqahmed.servicepulse.checking;

import dev.taufeeqahmed.servicepulse.alerting.WebhookNotifier;
import dev.taufeeqahmed.servicepulse.history.CheckHistoryService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckResultService {

    private final MonitoredServiceRepository services;
    private final CheckHistoryService history;
    private final IncidentService incidents;
    private final TransactionTemplate transaction;
    private final WebhookNotifier webhooks;

    public CheckResultService(MonitoredServiceRepository services, CheckHistoryService history,
            IncidentService incidents, PlatformTransactionManager transactions, WebhookNotifier webhooks) {
        this.services = services;
        this.history = history;
        this.incidents = incidents;
        this.transaction = new TransactionTemplate(transactions);
        this.webhooks = webhooks;
    }

    // Own the transaction: joining an outer transaction would make delivery before its cleanup possible.
    @Transactional(propagation = Propagation.NEVER)
    public void record(MonitoredService service, HealthCheckResponse result) {
        var transition = transaction.execute(status -> {
            // Lock the parent even when no incident exists, serializing all state transitions for this service.
            var locked = services.findByIdForUpdate(service.getId()).orElseThrow();
            history.record(locked, result);
            return incidents.accept(locked, result);
        });
        // execute returns only after commit and resource cleanup; a rollback/failure never reaches delivery.
        transition.ifPresent(webhooks::notifyTransition);
    }
}
