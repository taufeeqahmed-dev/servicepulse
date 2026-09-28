package dev.taufeeqahmed.servicepulse.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckHistoryService {

    private final HealthCheckRepository checks;
    private final MonitoredServiceRepository services;

    public CheckHistoryService(HealthCheckRepository checks, MonitoredServiceRepository services) {
        this.checks = checks;
        this.services = services;
    }

    @Transactional
    public void record(MonitoredService service, HealthCheckResponse result) {
        checks.save(new HealthCheck(service, result.status(), result.httpStatus(),
                result.responseTimeMs(), result.checkedAt()));
    }

    @Transactional(readOnly = true)
    public Optional<List<CheckHistoryResponse>> history(long serviceId) {
        if (!services.existsById(serviceId)) {
            return Optional.empty();
        }
        return Optional.of(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(serviceId).stream()
                .map(CheckHistoryResponse::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public Optional<ServiceStatsResponse> stats(long serviceId) {
        if (!services.existsById(serviceId)) {
            return Optional.empty();
        }
        var summary = checks.summarizeByServiceId(serviceId, HealthStatus.UP, HealthStatus.DOWN);
        BigDecimal uptime = summary.getTotalChecks() == 0 ? null
                : BigDecimal.valueOf(summary.getUpChecks()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(summary.getTotalChecks()), 2, RoundingMode.HALF_UP);
        BigDecimal average = summary.getAverageResponseTimeMs() == null ? null
                : BigDecimal.valueOf(summary.getAverageResponseTimeMs()).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new ServiceStatsResponse(serviceId, summary.getTotalChecks(), summary.getUpChecks(),
                summary.getDownChecks(), uptime, average, summary.getLastCheckedAt()));
    }
}
