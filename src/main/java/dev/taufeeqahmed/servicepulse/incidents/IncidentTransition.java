package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Instant;

public record IncidentTransition(Event event, long incidentId, long serviceId, String serviceName,
        String webhookUrl, Instant startedAt, Instant resolvedAt, long consecutiveFailures) {

    public enum Event {
        INCIDENT_OPENED, INCIDENT_RESOLVED
    }

    static IncidentTransition from(Incident incident, Event event) {
        var service = incident.getMonitoredService();
        return new IncidentTransition(event, incident.getId(), service.getId(), service.getName(),
                service.getWebhookUrl(), incident.getStartedAt(), incident.getResolvedAt(),
                service.getConsecutiveFailures());
    }
}
