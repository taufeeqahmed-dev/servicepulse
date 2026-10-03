package dev.taufeeqahmed.servicepulse.registration;

public record MonitoredServiceResponse(Long id, String name, String url,
        int failureThreshold, boolean webhookConfigured) {

    static MonitoredServiceResponse from(MonitoredService service) {
        return new MonitoredServiceResponse(service.getId(), service.getName(), service.getUrl(),
                service.getFailureThreshold(), service.getWebhookUrl() != null);
    }
}
