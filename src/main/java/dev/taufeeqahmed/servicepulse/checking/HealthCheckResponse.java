package dev.taufeeqahmed.servicepulse.checking;

import java.time.Instant;

public record HealthCheckResponse(
        Long serviceId,
        String name,
        String url,
        HealthStatus status,
        Integer httpStatus,
        long responseTimeMs,
        Instant checkedAt) {
}
