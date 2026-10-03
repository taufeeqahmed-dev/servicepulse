package dev.taufeeqahmed.servicepulse.registration;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select service from MonitoredService service where service.id = :id")
    Optional<MonitoredService> findByIdForUpdate(@Param("id") long id);
}
