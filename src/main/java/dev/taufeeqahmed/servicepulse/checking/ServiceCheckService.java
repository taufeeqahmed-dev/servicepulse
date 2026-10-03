package dev.taufeeqahmed.servicepulse.checking;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ServiceCheckService {

    private final MonitoredServiceRepository repository;
    private final HttpClient httpClient;
    private final CheckResultService results;
    private final ServicePulseMetrics metrics;
    private final Duration requestTimeout;

    public ServiceCheckService(MonitoredServiceRepository repository, HttpClient httpClient,
            CheckResultService results, ServicePulseMetrics metrics,
            @Value("${servicepulse.check.request-timeout}") Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive.");
        }
        this.repository = repository;
        this.httpClient = httpClient;
        this.results = results;
        this.metrics = metrics;
        this.requestTimeout = requestTimeout;
    }

    public Optional<HealthCheckResponse> check(long id) {
        // The repository's read transaction ends before the network request starts.
        return repository.findById(id).map(this::checkService);
    }

    private HealthCheckResponse checkService(MonitoredService service) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(service.getUrl()))
                .timeout(requestTimeout)
                .GET()
                .build();
        Instant checkedAt = Instant.now();
        long startedAt = System.nanoTime();
        Integer httpStatus = null;
        boolean interrupted = false;

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                // Only inspect headers; close the stream without reading or waiting for the body.
                httpStatus = response.statusCode();
            }
        } catch (IOException exception) {
            // Network failures before headers leave the HTTP status unset, producing DOWN.
        } catch (InterruptedException exception) {
            interrupted = true;
        }

        long responseTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        HealthStatus status = httpStatus != null && httpStatus >= 200 && httpStatus < 400
                ? HealthStatus.UP : HealthStatus.DOWN;
        var result = new HealthCheckResponse(service.getId(), service.getName(), service.getUrl(),
                status, httpStatus, responseTimeMs, checkedAt);
        try {
            // Count completed HTTP attempts even if the following history write fails.
            metrics.recordCheck(result);
            // Both entry points write once, in a short transaction after the HTTP request.
            results.record(service, result, interrupted);
            return result;
        } finally {
            // Restore cancellation after recording the interrupted attempt, even if saving fails.
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
