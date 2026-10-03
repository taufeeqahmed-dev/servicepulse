package dev.taufeeqahmed.servicepulse.checking;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.alerting.WebhookNotifier;
import dev.taufeeqahmed.servicepulse.history.CheckHistoryService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckResultService {

    // Bound admitted database work without an executor queue or serializing independent services.
    private final Semaphore recordingSlots = new Semaphore(8, true);
    private final ExecutorService persistenceWorkers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("servicepulse-persistence-", 0).factory());
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
        record(service, result, false);
    }

    @Transactional(propagation = Propagation.NEVER)
    public void record(MonitoredService service, HealthCheckResponse result, boolean cancelled) {
        var transition = recordAndAwaitCleanup(service.getId(), result, cancelled);
        // Only this caller delivers, after cleanup and restoration of any remembered cancellation.
        transition.ifPresent(webhooks::notifyTransition);
    }

    private Optional<IncidentTransition> recordAndAwaitCleanup(long serviceId, HealthCheckResponse result,
            boolean interrupted) {
        boolean admitted = false;
        try {
            while (!admitted) {
                try {
                    recordingSlots.acquire();
                    admitted = true;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            var recording = persistenceWorkers.submit(() -> transaction.execute(status -> {
                // Load fresh state on the worker; no caller-bound entity manager or transaction crosses threads.
                var locked = services.findByIdForUpdate(serviceId).orElseThrow();
                history.record(locked, result);
                return incidents.accept(locked, result);
            }));
            while (true) {
                try {
                    // Completion includes TransactionTemplate commit/rollback and resource cleanup.
                    return recording.get();
                } catch (InterruptedException exception) {
                    // H2 may erase its own thread's interrupt flag. Keep caller intent independently,
                    // and drain the recording without interrupting/cancelling the database worker.
                    interrupted = true;
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof RuntimeException failure) {
                        throw failure;
                    }
                    if (exception.getCause() instanceof Error failure) {
                        throw failure;
                    }
                    throw new IllegalStateException("Unexpected persistence worker failure", exception.getCause());
                }
            }
        } finally {
            if (admitted) {
                recordingSlots.release();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    void shutdownPersistenceWorkers() {
        // Spring destroys this component before its injected persistence dependencies.
        persistenceWorkers.shutdown();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (persistenceWorkers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException exception) {
                    // ExecutorService.close() could force shutdownNow here; transactions must instead drain.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
