package dev.taufeeqahmed.servicepulse.history;

import java.time.Instant;

import dev.taufeeqahmed.servicepulse.checking.HealthStatus;

public record CheckHistoryResponse(
        Long id,
        Long serviceId,
        HealthStatus status,
        Integer httpStatus,
        long responseTimeMs,
        Instant checkedAt) {

    public static CheckHistoryResponse from(HealthCheck check) {
        return new CheckHistoryResponse(check.getId(), check.getMonitoredService().getId(),
                check.getStatus(), check.getHttpStatus(), check.getResponseTimeMs(), check.getCheckedAt());
    }
}
