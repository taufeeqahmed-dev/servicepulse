package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Instant;

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
@Table(name = "incidents", indexes = {
        @Index(name = "idx_incidents_service_status", columnList = "service_id, status"),
        @Index(name = "idx_incidents_service_started_at", columnList = "service_id, started_at, id")
})
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private MonitoredService monitoredService;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private IncidentStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    private Instant resolvedAt;

    @Column(nullable = false, length = 64)
    private String initialFailureReason;

    private Integer initialStatusCode;
    private Integer latestStatusCode;

    protected Incident() {
    }

    Incident(MonitoredService service, Instant startedAt, Integer httpStatus) {
        this.monitoredService = service;
        this.status = IncidentStatus.OPEN;
        this.startedAt = startedAt;
        this.initialFailureReason = httpStatus == null ? "No HTTP response" : "HTTP " + httpStatus;
        this.initialStatusCode = httpStatus;
        this.latestStatusCode = httpStatus;
    }

    void continueOutage(Integer httpStatus) {
        latestStatusCode = httpStatus;
    }

    void resolve(Instant resolvedAt, Integer httpStatus) {
        status = IncidentStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        latestStatusCode = httpStatus;
    }

    public Long getId() {
        return id;
    }

    public MonitoredService getMonitoredService() {
        return monitoredService;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getInitialFailureReason() {
        return initialFailureReason;
    }

    public Integer getInitialStatusCode() {
        return initialStatusCode;
    }

    public Integer getLatestStatusCode() {
        return latestStatusCode;
    }
}
