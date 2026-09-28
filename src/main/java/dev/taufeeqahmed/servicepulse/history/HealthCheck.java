package dev.taufeeqahmed.servicepulse.history;

import java.time.Instant;

import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "health_checks", indexes = @Index(
        name = "idx_health_checks_service_checked_at", columnList = "service_id, checked_at, id"))
public class HealthCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private MonitoredService monitoredService;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private HealthStatus status;

    private Integer httpStatus;

    @Column(nullable = false)
    private long responseTimeMs;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected HealthCheck() {
    }

    public HealthCheck(MonitoredService monitoredService, HealthStatus status,
            Integer httpStatus, long responseTimeMs, Instant checkedAt) {
        this.monitoredService = monitoredService;
        this.status = status;
        this.httpStatus = httpStatus;
        this.responseTimeMs = responseTimeMs;
        this.checkedAt = checkedAt;
    }

    public Long getId() {
        return id;
    }

    public MonitoredService getMonitoredService() {
        return monitoredService;
    }

    public HealthStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
