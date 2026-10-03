package dev.taufeeqahmed.servicepulse.registration;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceRegistrationService {

    private final MonitoredServiceRepository repository;

    public ServiceRegistrationService(MonitoredServiceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MonitoredServiceResponse create(CreateServiceRequest request) {
        MonitoredService service = repository.save(new MonitoredService(request.name(), request.url(),
                request.failureThreshold() == null ? 3 : request.failureThreshold(), request.webhookUrl()));
        return MonitoredServiceResponse.from(service);
    }

    @Transactional(readOnly = true)
    public List<MonitoredServiceResponse> listAll() {
        return repository.findAll(Sort.by("id")).stream()
                .map(MonitoredServiceResponse::from)
                .toList();
    }
}
