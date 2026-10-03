package dev.taufeeqahmed.servicepulse.incidents;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByMonitoredService_IdAndStatus(long serviceId, IncidentStatus status);

    @EntityGraph(attributePaths = "monitoredService")
    List<Incident> findAllByOrderByStartedAtDescIdDesc();

    @EntityGraph(attributePaths = "monitoredService")
    List<Incident> findByStatusOrderByStartedAtDescIdDesc(IncidentStatus status);

    @EntityGraph(attributePaths = "monitoredService")
    List<Incident> findByMonitoredService_IdOrderByStartedAtDescIdDesc(long serviceId);

    long countByStatus(IncidentStatus status);
}
