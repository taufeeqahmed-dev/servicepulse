package dev.taufeeqahmed.servicepulse.alerting;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.incidents.IncidentTransition;

@JsonInclude(JsonInclude.Include.NON_NULL)
record WebhookPayload(IncidentTransition.Event event, long incidentId, long serviceId, String serviceName,
        HealthStatus status, Instant startedAt, Instant resolvedAt, Long consecutiveFailures, Long durationSeconds) {

    static WebhookPayload from(IncidentTransition transition) {
        boolean opened = transition.event() == IncidentTransition.Event.INCIDENT_OPENED;
        return new WebhookPayload(transition.event(), transition.incidentId(), transition.serviceId(),
                transition.serviceName(), opened ? HealthStatus.DOWN : HealthStatus.UP,
                transition.startedAt(), transition.resolvedAt(), opened ? transition.consecutiveFailures() : null,
                opened ? null : Duration.between(transition.startedAt(), transition.resolvedAt()).toSeconds());
    }
}
