package dev.taufeeqahmed.servicepulse.history;

import java.math.BigDecimal;
import java.time.Instant;

public record ServiceStatsResponse(
        long serviceId,
        long totalChecks,
        long upChecks,
        long downChecks,
        BigDecimal uptimePercentage,
        BigDecimal averageResponseTimeMs,
        Instant lastCheckedAt) {
}
