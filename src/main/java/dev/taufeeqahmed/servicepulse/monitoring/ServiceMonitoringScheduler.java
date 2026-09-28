package dev.taufeeqahmed.servicepulse.monitoring;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;

public class ServiceMonitoringScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ServiceMonitoringScheduler.class);

    private final MonitoredServiceRepository repository;
    private final ServiceCheckService checkService;
    private final AtomicBoolean running = new AtomicBoolean();

    public ServiceMonitoringScheduler(MonitoredServiceRepository repository, ServiceCheckService checkService) {
        this.repository = repository;
        this.checkService = checkService;
    }

    @Scheduled(fixedDelayString = "${servicepulse.monitoring.poll-interval}",
            initialDelayString = "${servicepulse.monitoring.poll-interval}")
    public void checkRegisteredServices() {
        // Also reject concurrent invocations outside the normal fixed-delay trigger.
        if (Thread.currentThread().isInterrupted() || !running.compareAndSet(false, true)) {
            return;
        }

        try {
            for (MonitoredService service : repository.findAll(Sort.by("id"))) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    var result = checkService.check(service.getId());
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    result.ifPresent(check -> logger.info(
                            "Scheduled check: serviceId={}, status={}, httpStatus={}, responseTimeMs={}",
                            check.serviceId(), check.status(), check.httpStatus(), check.responseTimeMs()));
                } catch (RuntimeException exception) {
                    logger.warn("Scheduled check failed for service {}", service.getId(), exception);
                }
            }
        } catch (RuntimeException exception) {
            logger.error("Scheduled monitoring run failed; later runs will still execute", exception);
        } finally {
            running.set(false);
        }
    }
}
