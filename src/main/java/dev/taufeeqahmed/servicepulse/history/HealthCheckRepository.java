package dev.taufeeqahmed.servicepulse.history;

import java.time.Instant;
import java.util.List;

import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, Long> {

    List<HealthCheck> findByMonitoredService_IdOrderByCheckedAtDescIdDesc(long serviceId);

    @Query("""
            select count(c) as totalChecks,
                   coalesce(sum(case when c.status = :up then 1 else 0 end), 0) as upChecks,
                   coalesce(sum(case when c.status = :down then 1 else 0 end), 0) as downChecks,
                   avg(c.responseTimeMs) as averageResponseTimeMs,
                   max(c.checkedAt) as lastCheckedAt
            from HealthCheck c
            where c.monitoredService.id = :serviceId
            """)
    Summary summarizeByServiceId(@Param("serviceId") long serviceId,
            @Param("up") HealthStatus up, @Param("down") HealthStatus down);

    interface Summary {
        long getTotalChecks();
        long getUpChecks();
        long getDownChecks();
        Double getAverageResponseTimeMs();
        Instant getLastCheckedAt();
    }
}
