package dev.taufeeqahmed.servicepulse.checking;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import dev.taufeeqahmed.servicepulse.history.CheckHistoryService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceCheckServiceTest {

    private final MonitoredService registered = new MonitoredService("Local API", "http://localhost/health");
    private final CheckHistoryService history = mock(CheckHistoryService.class);
    private ServiceCheckService service;

    @BeforeEach
    void setUp() throws Exception {
        var repository = mock(MonitoredServiceRepository.class);
        var httpClient = mock(HttpClient.class);
        when(repository.findById(1L)).thenReturn(Optional.of(registered));
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenThrow(new InterruptedException("Simulated interruption"));
        service = new ServiceCheckService(repository, httpClient, history, Duration.ofSeconds(5));
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
        } finally {
            Thread.interrupted();
        }
    }
}
