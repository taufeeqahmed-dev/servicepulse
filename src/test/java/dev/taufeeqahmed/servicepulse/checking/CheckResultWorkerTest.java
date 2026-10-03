package dev.taufeeqahmed.servicepulse.checking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import dev.taufeeqahmed.servicepulse.alerting.WebhookNotifier;
import dev.taufeeqahmed.servicepulse.history.CheckHistoryService;
import dev.taufeeqahmed.servicepulse.incidents.IncidentService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(15)
class CheckResultWorkerTest {

    private final MonitoredServiceRepository services = mock(MonitoredServiceRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final WebhookNotifier webhooks = mock(WebhookNotifier.class);
    private final MonitoredService service = mock(MonitoredService.class);
    private final HealthCheckResponse result = new HealthCheckResponse(1L, "API", "http://localhost/health",
            HealthStatus.DOWN, 503, 1, Instant.now());
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private CheckResultService recorder;

    @BeforeEach
    void start() {
        when(service.getId()).thenReturn(1L);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var incidents = mock(IncidentService.class);
        when(incidents.accept(any(), any())).thenReturn(Optional.empty());
        recorder = new CheckResultService(services, mock(CheckHistoryService.class), incidents, transactions, webhooks);
    }

    @AfterEach
    void stop() {
        recorder.shutdownPersistenceWorkers();
        assertThat(workerThreads).allSatisfy(thread -> assertThat(thread.isAlive()).isFalse());
        verifyNoInteractions(webhooks);
    }

    @Test
    void boundsConcurrentRecordingsAndRemembersCancellationWhileWaitingForAdmission() throws Exception {
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        holdRecordings(entered, release);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstEight = new ArrayList<Future<Boolean>>();
            try {
                for (int i = 0; i < 8; i++) {
                    firstEight.add(callers.submit(() -> {
                        recorder.record(service, result);
                        return Thread.currentThread().isInterrupted();
                    }));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var ninthCaller = new AtomicReference<Thread>();
                var ninth = callers.submit(() -> {
                    ninthCaller.set(Thread.currentThread());
                    recorder.record(service, result);
                    return Thread.currentThread().isInterrupted();
                });
                await(() -> ninthCaller.get() != null && ninthCaller.get().getState() == Thread.State.WAITING);
                ninthCaller.get().interrupt();
                await(() -> !ninthCaller.get().isInterrupted()
                        && ninthCaller.get().getState() == Thread.State.WAITING);
                assertThat(ninth.isDone()).isFalse();
                assertThat(workerThreads).hasSize(8);
                verify(services, times(8)).findByIdForUpdate(1L);
                release.countDown();
                for (var recording : firstEight) {
                    assertThat(recording.get(5, TimeUnit.SECONDS)).isFalse();
                }
                assertThat(ninth.get(5, TimeUnit.SECONDS)).isTrue();
                verify(transactions, times(9)).commit(any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shutdownDrainsWorkersEvenWhenShutdownWaiterIsInterruptedAndRejectsNewRecordings() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        holdRecordings(entered, release);
        var workers = (ExecutorService) ReflectionTestUtils.getField(recorder, "persistenceWorkers");
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var recording = callers.submit(() -> recorder.record(service, result));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var shutdownThread = new AtomicReference<Thread>();
                var shutdown = callers.submit(() -> {
                    shutdownThread.set(Thread.currentThread());
                    recorder.shutdownPersistenceWorkers();
                    return Thread.currentThread().isInterrupted();
                });
                await(workers::isShutdown);
                shutdownThread.get().interrupt();
                await(() -> !shutdownThread.get().isInterrupted());
                assertThat(shutdown.isDone()).isFalse();
                assertThat(recording.isDone()).isFalse();
                assertThat(workerThreads).singleElement()
                        .satisfies(thread -> assertThat(thread.isInterrupted()).isFalse());
                release.countDown();
                recording.get(5, TimeUnit.SECONDS);
                assertThat(shutdown.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(workers.isTerminated()).isTrue();
                verify(transactions).commit(any());
                assertThatThrownBy(() -> recorder.record(service, result))
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
        }
    }

    private void holdRecordings(CountDownLatch entered, CountDownLatch release) {
        when(services.findByIdForUpdate(1L)).thenAnswer(invocation -> {
            workerThreads.add(Thread.currentThread());
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return Optional.of(service);
        });
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
