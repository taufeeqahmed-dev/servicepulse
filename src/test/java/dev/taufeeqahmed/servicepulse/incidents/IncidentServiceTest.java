package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import dev.taufeeqahmed.servicepulse.checking.CheckResultService;
import dev.taufeeqahmed.servicepulse.checking.HealthCheckResponse;
import dev.taufeeqahmed.servicepulse.checking.HealthStatus;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {"servicepulse.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incident-tests;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Import(IncidentServiceTest.EventConfiguration.class)
@Timeout(30)
class IncidentServiceTest {

    private static final Instant OPENED = Instant.parse("2026-10-03T14:03:00Z");
    @Autowired private MonitoredServiceRepository services;
    @Autowired private IncidentRepository incidents;
    @Autowired private HealthCheckRepository checks;
    @Autowired private CheckResultService results;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private CommittedEvents events;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock clock;

    @BeforeEach
    void reset() {
        incidents.deleteAll();
        checks.deleteAll();
        services.deleteAll();
        events.received.clear();
        when(clock.instant()).thenReturn(OPENED);
    }

    @Test
    void defaultThresholdOpensOnlyOnThirdFailureAndContinuesSameIncident() {
        var service = services.save(new MonitoredService("Default", "http://localhost/health"));
        record(service, 503);
        assertThat(incidents.count()).isZero();
        record(service, null);
        assertThat(incidents.count()).isZero();
        assertThat(failures(service)).isEqualTo(2);
        record(service, 502);
        long id = incidents.findAll().getFirst().getId();
        record(service, 500);
        assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
            assertThat(incident.getId()).isEqualTo(id);
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(incident.getStartedAt()).isEqualTo(OPENED);
            assertThat(incident.getInitialFailureReason()).isEqualTo("HTTP 502");
            assertThat(incident.getInitialStatusCode()).isEqualTo(502);
            assertThat(incident.getLatestStatusCode()).isEqualTo(500);
        });
        assertThat(failures(service)).isEqualTo(4);
        assertThat(checks.count()).isEqualTo(4);
        assertThat(events.received).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void customThresholdOpensOnExactlyTheConfiguredFailure(int threshold) {
        var service = register(threshold);
        for (int attempt = 1; attempt < threshold; attempt++) {
            record(service, 503);
            assertThat(incidents.count()).isZero();
        }
        record(service, null);
        assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(incident.getInitialStatusCode()).isNull();
            assertThat(incident.getInitialFailureReason()).isEqualTo("No HTTP response");
        });
    }

    @Test
    void recoveryResolvesOnceResetsStreakAndAllowsANewOutage() {
        var service = register(1);
        record(service, 503);
        long firstId = incidents.findAll().getFirst().getId();
        when(clock.instant()).thenReturn(OPENED.plusSeconds(240));
        record(service, 200);
        record(service, 200);
        assertThat(incidents.findById(firstId)).get().satisfies(incident -> {
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getResolvedAt()).isEqualTo(OPENED.plusSeconds(240));
            assertThat(incident.getLatestStatusCode()).isEqualTo(200);
        });
        assertThat(failures(service)).isZero();
        assertThat(events.received).extracting(IncidentTransition::event)
                .containsExactly(IncidentTransition.Event.INCIDENT_OPENED, IncidentTransition.Event.INCIDENT_RESOLVED);
        record(service, 500);
        assertThat(incidents.count()).isEqualTo(2);
        assertThat(incidents.countByStatus(IncidentStatus.OPEN)).isEqualTo(1);
    }

    @Test
    void successBelowThresholdResetsTheStreakWithoutCreatingAnIncident() {
        var service = register(3);
        record(service, 200);
        record(service, 503);
        record(service, 503);
        record(service, 200);
        assertThat(failures(service)).isZero();
        record(service, 503);
        assertThat(failures(service)).isEqualTo(1);
        assertThat(incidents.count()).isZero();
        assertThat(events.received).isEmpty();
    }

    @Test
    void rollbackRestoresHistoryStreakAndIncidentAndDoesNotDeliverTransition() {
        var service = register(1);
        when(clock.instant()).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    assertThat(incidents.count()).isEqualTo(1);
                    assertThat(events.received).isEmpty();
                    throw new IllegalStateException("Abort before commit");
                }
            });
            return OPENED;
        });
        assertThatThrownBy(() -> record(service, 503)).isInstanceOf(IllegalStateException.class);
        assertThat(incidents.count()).isZero();
        assertThat(checks.count()).isZero();
        assertThat(failures(service)).isZero();
        assertThat(events.received).isEmpty();
    }

    @Test
    void resultCoordinatorRejectsAnOuterTransactionBeforeWritingAnything() {
        var service = register(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(transaction -> record(service, 503)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(checks.count()).isZero();
        assertThat(incidents.count()).isZero();
        assertThat(failures(service)).isZero();
        assertThat(events.received).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false, 503", "true, 503", "false, 200", "true, 200"})
    void contendingWritersObserveCommittedStateAfterCommitOrRollback(boolean rollback, int secondStatus)
            throws Exception {
        var service = register(2);
        record(service, 503); // One failure below the threshold.
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holdFirst = new AtomicBoolean(true);
        when(clock.instant()).thenAnswer(invocation -> {
            // Incident time is requested after the parent write lock and history insert, inside the transaction.
            if (holdFirst.compareAndSet(true, false)) {
                locked.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                if (rollback) {
                    throw new IllegalStateException("Roll back the lock holder");
                }
            }
            return OPENED;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> record(service, 503));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> record(service, secondStatus));
                awaitDatabaseLockWait();
                assertThat(second.isDone()).isFalse();
                assertThat(checks.count()).isEqualTo(1); // The first writer's insert is still uncommitted.
                assertThat(events.received).isEmpty();
                release.countDown();
                if (rollback) {
                    assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(IllegalStateException.class);
                } else {
                    first.get(5, TimeUnit.SECONDS);
                }
                second.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        boolean recovered = secondStatus == 200;
        assertThat(failures(service)).isEqualTo(recovered ? 0 : rollback ? 2 : 3);
        assertThat(checks.count()).isEqualTo(rollback ? 2 : 3);
        assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(service.getId()))
                .extracting(check -> check.getStatus()).containsExactlyElementsOf(recovered
                        ? rollback ? List.of(HealthStatus.UP, HealthStatus.DOWN)
                                   : List.of(HealthStatus.UP, HealthStatus.DOWN, HealthStatus.DOWN)
                        : rollback ? List.of(HealthStatus.DOWN, HealthStatus.DOWN)
                                   : List.of(HealthStatus.DOWN, HealthStatus.DOWN, HealthStatus.DOWN));
        if (rollback && recovered) {
            assertThat(incidents.count()).isZero();
            assertThat(events.received).isEmpty();
        } else {
            assertThat(incidents.findAll()).singleElement().satisfies(incident -> {
                assertThat(incident.getStatus()).isEqualTo(recovered ? IncidentStatus.RESOLVED : IncidentStatus.OPEN);
                assertThat(incident.getLatestStatusCode()).isEqualTo(secondStatus);
            });
            assertThat(events.received).extracting(IncidentTransition::event).containsExactlyInAnyOrderElementsOf(recovered
                    ? List.of(IncidentTransition.Event.INCIDENT_OPENED, IncidentTransition.Event.INCIDENT_RESOLVED)
                    : List.of(IncidentTransition.Event.INCIDENT_OPENED));
        }
    }

    @Test
    void incidentForeignKeyRestrictsDeletionWithoutCascadingHistory() {
        var service = register(1);
        record(service, 503);
        checks.deleteAll(); // Isolate the incident FK from the pre-existing check-history FK.
        assertThatThrownBy(() -> services.deleteById(service.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(services.existsById(service.getId())).isTrue();
        assertThat(incidents.count()).isEqualTo(1);
    }

    @Test
    void incidentApiReturnsDtosNewestFirstAndSupportsStatusAndServiceFilters() throws Exception {
        var first = register(1);
        record(first, 503);
        long firstId = incidents.findAll().getFirst().getId();
        when(clock.instant()).thenReturn(OPENED.plusSeconds(240));
        record(first, 200);
        var second = register(1);
        record(second, null);
        mvc.perform(get("/api/incidents"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].serviceId").value(second.getId()))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].durationSeconds").isEmpty())
                .andExpect(jsonPath("$[0].monitoredService").doesNotExist())
                .andExpect(jsonPath("$[0].webhookUrl").doesNotExist());
        mvc.perform(get("/api/incidents/{id}", firstId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.serviceName").value("API"))
                .andExpect(jsonPath("$.startedAt").value(OPENED.toString()))
                .andExpect(jsonPath("$.resolvedAt").value(OPENED.plusSeconds(240).toString()))
                .andExpect(jsonPath("$.durationSeconds").value(240));
        mvc.perform(get("/api/incidents").param("status", "OPEN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].serviceId").value(second.getId()));
        mvc.perform(get("/api/services/{id}/incidents", first.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstId));
    }

    @Test
    void incidentApiHandlesEmptyHistoryUnknownIdsAndInvalidFilters() throws Exception {
        var service = register(3);
        mvc.perform(get("/api/incidents")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/services/{id}/incidents", service.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/incidents/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/services/999999/incidents")).andExpect(status().isNotFound());
        mvc.perform(get("/api/incidents?status=INVALID")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/incidents/not-a-number")).andExpect(status().isBadRequest());
    }

    private MonitoredService register(int threshold) {
        return services.save(new MonitoredService("API", "http://localhost/health", threshold, null));
    }

    private long failures(MonitoredService service) {
        return services.findById(service.getId()).orElseThrow().getConsecutiveFailures();
    }

    private void record(MonitoredService service, Integer httpStatus) {
        results.record(service, new HealthCheckResponse(service.getId(), service.getName(), service.getUrl(),
                httpStatus != null && httpStatus < 400 ? HealthStatus.UP : HealthStatus.DOWN,
                httpStatus, 25, OPENED.minusSeconds(1)));
    }

    private void awaitDatabaseLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("""
                    select count(*) from information_schema.sessions
                    where blocker_id is not null and lower(executing_statement) like '%monitored_services%'
                    """, Integer.class) == 1) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("The second writer never waited on the parent's database lock");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfiguration {
        @Bean CommittedEvents committedEvents() { return new CommittedEvents(); }
    }

    static class CommittedEvents {
        final List<IncidentTransition> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        public void afterCommit(IncidentTransition transition) {
            received.add(transition);
        }
    }
}
