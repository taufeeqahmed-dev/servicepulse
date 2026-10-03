package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Duration;
import java.time.Instant;

public record IncidentResponse(long id, long serviceId, String serviceName, IncidentStatus status,
        Instant startedAt, Instant resolvedAt, Long durationSeconds, String initialFailureReason,
        Integer initialStatusCode, Integer latestStatusCode) {

    static IncidentResponse from(Incident incident) {
        return new IncidentResponse(incident.getId(), incident.getMonitoredService().getId(),
                incident.getMonitoredService().getName(), incident.getStatus(), incident.getStartedAt(),
                incident.getResolvedAt(), incident.getResolvedAt() == null ? null
                        : Duration.between(incident.getStartedAt(), incident.getResolvedAt()).toSeconds(),
                incident.getInitialFailureReason(), incident.getInitialStatusCode(), incident.getLatestStatusCode());
    }
}
