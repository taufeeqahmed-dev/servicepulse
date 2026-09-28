package dev.taufeeqahmed.servicepulse.registration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, Long> {
}
