package dev.taufeeqahmed.servicepulse.checking;

import dev.taufeeqahmed.servicepulse.history.CheckHistoryService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckResultService {

    private final MonitoredServiceRepository services;
    private final CheckHistoryService history;
    private final IncidentService incidents;

    public CheckResultService(MonitoredServiceRepository services, CheckHistoryService history,
            IncidentService incidents) {
        this.services = services;
        this.history = history;
        this.incidents = incidents;
    }

    @Transactional
    public void record(MonitoredService service, HealthCheckResponse result) {
        // Lock the parent even when no incident exists, serializing all state transitions for this service.
        var locked = services.findByIdForUpdate(service.getId()).orElseThrow();
        history.record(locked, result);
        incidents.accept(locked, result);
    }
}
