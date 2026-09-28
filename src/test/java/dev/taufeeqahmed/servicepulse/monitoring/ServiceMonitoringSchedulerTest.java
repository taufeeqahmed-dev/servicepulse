package dev.taufeeqahmed.servicepulse.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class ServiceMonitoringSchedulerTest {

    @Mock
    private MonitoredServiceRepository repository;

    @Mock
    private ServiceCheckService checkService;

    private ServiceMonitoringScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ServiceMonitoringScheduler(repository, checkService);
    }

    @Test
    void checksRegisteredService() {
        var services = List.of(registered(1L));
        when(repository.findAll(Sort.by("id"))).thenReturn(services);
        when(checkService.check(1L)).thenReturn(result(1L, HealthStatus.UP));

        scheduler.checkRegisteredServices();

        verify(checkService).check(1L);
    }

    @Test
    void checksMultipleServicesAndContinuesAfterDownResult() {
        var services = List.of(registered(1L), registered(2L));
        when(repository.findAll(Sort.by("id"))).thenReturn(services);
        when(checkService.check(1L)).thenReturn(result(1L, HealthStatus.DOWN));
        when(checkService.check(2L)).thenReturn(result(2L, HealthStatus.UP));

        scheduler.checkRegisteredServices();

        var order = inOrder(checkService);
        order.verify(checkService).check(1L);
        order.verify(checkService).check(2L);
    }

    @Test
    void continuesAfterUnexpectedServiceFailure() {
        var services = List.of(registered(1L), registered(2L));
        when(repository.findAll(Sort.by("id"))).thenReturn(services);
        when(checkService.check(1L)).thenThrow(new IllegalStateException("Simulated check failure"));
        when(checkService.check(2L)).thenReturn(result(2L, HealthStatus.UP));

        assertThatCode(scheduler::checkRegisteredServices).doesNotThrowAnyException();

        verify(checkService).check(2L);
    }

    @Test
    void recoversOnNextRunAfterRepositoryFailure() {
        var services = List.of(registered(1L));
        when(repository.findAll(Sort.by("id")))
                .thenThrow(new IllegalStateException("Simulated database failure"))
                .thenReturn(services);

        assertThatCode(scheduler::checkRegisteredServices).doesNotThrowAnyException();
        scheduler.checkRegisteredServices();

        verify(repository, times(2)).findAll(Sort.by("id"));
        verify(checkService).check(1L);
    }

    @Test
    void preventsOverlappingScheduledChecksAndAllowsNextRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var services = List.of(registered(1L));
        when(repository.findAll(Sort.by("id"))).thenReturn(services);
        when(checkService.check(1L)).thenAnswer(invocation -> {
            if (entered.getCount() > 0) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result(1L, HealthStatus.UP);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstRun = executor.submit(scheduler::checkRegisteredServices);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                scheduler.checkRegisteredServices();
                verify(checkService).check(1L);
                verify(repository).findAll(Sort.by("id"));
            } finally {
                release.countDown();
            }
            firstRun.get(5, TimeUnit.SECONDS);
        }

        scheduler.checkRegisteredServices();
        verify(checkService, times(2)).check(1L);
    }

    @Test
    void skipsWorkOnAnAlreadyInterruptedThread() {
        try {
            Thread.currentThread().interrupt();

            scheduler.checkRegisteredServices();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(repository, checkService);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopsBatchOnInterruptionAndReleasesRunGuard() {
        var services = List.of(registered(1L), registered(2L));
        when(repository.findAll(Sort.by("id"))).thenReturn(services);
        when(checkService.check(1L)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return result(1L, HealthStatus.DOWN);
        }).thenReturn(result(1L, HealthStatus.UP));

        try {
            scheduler.checkRegisteredServices();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(checkService, never()).check(2L);
        } finally {
            Thread.interrupted();
        }

        scheduler.checkRegisteredServices();
        verify(checkService, times(2)).check(1L);
        verify(checkService).check(2L);
    }

    private static MonitoredService registered(long id) {
        var service = mock(MonitoredService.class);
        when(service.getId()).thenReturn(id);
        return service;
    }

    private static Optional<HealthCheckResponse> result(long id, HealthStatus status) {
        return Optional.of(new HealthCheckResponse(id, "Service " + id, "http://localhost/health",
                status, status == HealthStatus.UP ? 200 : null, 1L, Instant.now()));
    }
}
