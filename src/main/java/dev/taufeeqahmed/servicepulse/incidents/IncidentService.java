package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final MonitoredServiceRepository services;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, MonitoredServiceRepository services,
            ApplicationEventPublisher events, Clock clock) {
        this.incidents = incidents;
        this.services = services;
        this.events = events;
        this.clock = clock;
    }

    // Only the shared result recorder calls this, with the service row locked in its transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IncidentTransition> accept(MonitoredService service, HealthCheckResponse result) {
        var open = incidents.findByMonitoredService_IdAndStatus(service.getId(), IncidentStatus.OPEN);
        if (result.status() == HealthStatus.UP) {
            service.resetFailures();
            return open.map(incident -> {
                // Guard against a backwards wall-clock adjustment; check timestamps remain unchanged.
                var now = clock.instant();
                incident.resolve(now.isBefore(incident.getStartedAt()) ? incident.getStartedAt() : now,
                        result.httpStatus());
                return publish(IncidentTransition.from(incident, IncidentTransition.Event.INCIDENT_RESOLVED));
            });
        } else {
            service.recordFailure();
            if (open.isPresent()) {
                open.get().continueOutage(result.httpStatus());
            } else if (service.getConsecutiveFailures() >= service.getFailureThreshold()) {
                var incident = incidents.save(new Incident(service, clock.instant(), result.httpStatus()));
                return Optional.of(publish(IncidentTransition.from(incident, IncidentTransition.Event.INCIDENT_OPENED)));
            }
        }
        return Optional.empty();
    }

    private IncidentTransition publish(IncidentTransition transition) {
        events.publishEvent(transition);
        return transition;
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list(IncidentStatus status) {
        var found = status == null ? incidents.findAllByOrderByStartedAtDescIdDesc()
                : incidents.findByStatusOrderByStartedAtDescIdDesc(status);
        return found.stream().map(IncidentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<IncidentResponse> find(long id) {
        return incidents.findById(id).map(IncidentResponse::from);
    }

    @Transactional(readOnly = true)
    public Optional<List<IncidentResponse>> history(long serviceId) {
        if (!services.existsById(serviceId)) {
            return Optional.empty();
        }
        return Optional.of(incidents.findByMonitoredService_IdOrderByStartedAtDescIdDesc(serviceId)
                .stream().map(IncidentResponse::from).toList());
    }
}
