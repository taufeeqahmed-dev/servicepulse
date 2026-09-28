package dev.taufeeqahmed.servicepulse.registration;

public record MonitoredServiceResponse(Long id, String name, String url) {

    static MonitoredServiceResponse from(MonitoredService service) {
        return new MonitoredServiceResponse(service.getId(), service.getName(), service.getUrl());
    }
}
