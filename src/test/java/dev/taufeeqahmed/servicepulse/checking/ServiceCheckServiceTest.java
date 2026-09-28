package dev.taufeeqahmed.servicepulse.checking;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceCheckServiceTest {

    @Test
    void preservesInterruptFlagWhenHttpRequestIsInterrupted() throws Exception {
        var repository = mock(MonitoredServiceRepository.class);
        var httpClient = mock(HttpClient.class);
        var registered = new MonitoredService("Local API", "http://localhost/health");
        when(repository.findById(1L)).thenReturn(Optional.of(registered));
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new InterruptedException("Simulated interruption"));
        var service = new ServiceCheckService(repository, httpClient, Duration.ofSeconds(5));

        try {
            var result = service.check(1L).orElseThrow();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
            assertThat(result.httpStatus()).isNull();
        } finally {
            // Do not leak the test's interrupt flag to JUnit or another test.
            Thread.interrupted();
        }
    }
}
