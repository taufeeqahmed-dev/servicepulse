package dev.taufeeqahmed.servicepulse.checking;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceCheckServiceTest {

    private final MonitoredService registered = new MonitoredService("Local API", "http://localhost/health");
    private final CheckResultService history = mock(CheckResultService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MonitoredServiceRepository repository = mock(MonitoredServiceRepository.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private ServiceCheckService service;

    @BeforeEach
    void setUp() throws Exception {
        when(repository.findById(1L)).thenReturn(Optional.of(registered));
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new InterruptedException("Simulated interruption"));
        service = new ServiceCheckService(repository, httpClient, history,
                new ServicePulseMetrics(registry), Duration.ofSeconds(5));
    }

    @Test
    void recordsInterruptedAttemptAndPreservesInterruptFlag() {
        doAnswer(invocation -> {
            // Avoid carrying the HTTP interruption into the database transaction.
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(history).record(any(), any());

        try {
            var result = service.check(1L).orElseThrow();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
            assertThat(result.httpStatus()).isNull();
            verify(history).record(registered, result);
            assertThat(registry.get("servicepulse.checks").tag("status", "DOWN").counter().count()).isEqualTo(1);
        } finally {
            // Do not leak the test's interrupt flag to JUnit or another test.
            Thread.interrupted();
        }
    }

    @Test
    void preservesInterruptFlagEvenWhenSavingTheInterruptedAttemptFails() {
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            throw new IllegalStateException("Simulated persistence failure");
        }).when(history).record(any(), any());

        try {
            assertThatThrownBy(() -> service.check(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Simulated persistence failure");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(history).record(any(), any());
            assertThat(registry.get("servicepulse.checks").tag("status", "UP").counter().count()).isZero();
            assertThat(registry.get("servicepulse.checks").tag("status", "DOWN").counter().count()).isEqualTo(1);
            assertThat(registry.get("servicepulse.check.duration").timer().count()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(HealthStatus.class)
    @SuppressWarnings("unchecked")
    void outcomeCounterFailureDoesNotPreventTheCheckResultOrHistory(HealthStatus status) throws Exception {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        int httpStatus = status == HealthStatus.UP ? 200 : 503;
        when(response.statusCode()).thenReturn(httpStatus);
        when(response.body()).thenReturn(InputStream.nullInputStream());
        doReturn(response).when(httpClient).send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        var failingRegistry = mock(MeterRegistry.class);
        var counter = mock(Counter.class);
        when(failingRegistry.counter("servicepulse.checks", "status", status.name())).thenReturn(counter);
        doThrow(new IllegalStateException("Outcome counter unavailable")).when(counter).increment();
        var checking = new ServiceCheckService(repository, httpClient, history,
                new ServicePulseMetrics(failingRegistry), Duration.ofSeconds(5));

        var result = checking.check(1L).orElseThrow();

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.httpStatus()).isEqualTo(httpStatus);
        verify(counter).increment();
        verify(history).record(registered, result);
        verify(failingRegistry, never()).counter("servicepulse.checks");
    }
}
