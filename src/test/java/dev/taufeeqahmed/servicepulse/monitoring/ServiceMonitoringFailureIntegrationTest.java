package dev.taufeeqahmed.servicepulse.monitoring;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.history.HealthCheckRepository;
import dev.taufeeqahmed.servicepulse.incidents.IncidentRepository;
import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties = {"servicepulse.monitoring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:monitoring-failure-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000"})
@ExtendWith(OutputCaptureExtension.class)
@Timeout(20)
class ServiceMonitoringFailureIntegrationTest {

    private static final String SECRET = "DISTINCTIVE_WEBHOOK_SECRET_08";
    @Autowired private MonitoredServiceRepository services;
    @Autowired private HealthCheckRepository checks;
    @Autowired private IncidentRepository incidents;
    @Autowired private ServiceCheckService checking;
    @Autowired private ServicePulseMetrics metrics;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private HttpServer server;
    private ExecutorService executor;
    private String webhookUrl;
    private MonitoredService first;
    private MonitoredService next;
    private final AtomicInteger firstRequests = new AtomicInteger();
    private final AtomicInteger nextRequests = new AtomicInteger();
    private final AtomicInteger webhooks = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/first", exchange -> {
            try (exchange) { firstRequests.incrementAndGet(); exchange.sendResponseHeaders(503, -1); }
        });
        server.createContext("/next", exchange -> {
            try (exchange) { nextRequests.incrementAndGet(); exchange.sendResponseHeaders(503, -1); }
        });
        server.createContext("/hook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                webhooks.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        webhookUrl = base + "/hook?token=" + SECRET;
        first = services.save(new MonitoredService("Contended", base + "/first", 1, webhookUrl));
        next = services.save(new MonitoredService("Next", base + "/next", 1, base + "/hook"));
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        incidents.deleteAll();
        checks.deleteAll();
        services.deleteAll();
    }

    @Test
    void realLockTimeoutLogsOnlySafeDiagnosticsAndContinuesTheBatch(CapturedOutput output) throws Exception {
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("select * from monitored_services where id=? for update")) {
                lock.setLong(1, first.getId());
                lock.executeQuery().close();
                var batch = executor.submit(() -> {
                    new ServiceMonitoringScheduler(services, checking, metrics).checkRegisteredServices();
                    return Thread.currentThread().isInterrupted();
                });
                awaitDatabaseLockWait();
                assertThat(batch.get(8, TimeUnit.SECONDS)).isFalse();
            } finally {
                holder.rollback();
            }
        }
        assertThat(firstRequests).hasValue(1);
        assertThat(nextRequests).hasValue(1);
        assertThat(webhooks).hasValue(1);
        assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(first.getId())).isEmpty();
        assertThat(checks.findByMonitoredService_IdOrderByCheckedAtDescIdDesc(next.getId())).hasSize(1);
        assertThat(incidents.count()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(SECRET, webhookUrl, "Map entry <", "MVStoreException");
        assertThat(output.getAll()).contains("Scheduled check failed: serviceId=" + first.getId(),
                "failureType=org.springframework.dao.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerCancellationSurvivesLockWaitAndStopsBatchAfterCommitOrRollback(boolean lockTimesOut,
            CapturedOutput output) throws Exception {
        var caller = new AtomicReference<Thread>();
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("select * from monitored_services where id=? for update")) {
                lock.setLong(1, first.getId());
                lock.executeQuery().close();
                var batch = executor.submit(() -> {
                    caller.set(Thread.currentThread());
                    new ServiceMonitoringScheduler(services, checking, metrics).checkRegisteredServices();
                    return Thread.currentThread().isInterrupted();
                });
                awaitDatabaseLockWait();
                caller.get().interrupt();
                // The caller must consume/remember cancellation and still await persistence cleanup.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime() < deadline && !batch.isDone()
                        && (caller.get().isInterrupted() || caller.get().getState() != Thread.State.WAITING)) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                assertThat(batch.isDone()).isFalse();
                assertThat(caller.get().isInterrupted()).isFalse();
                assertThat(caller.get().getState()).isEqualTo(Thread.State.WAITING);
                assertThat(databaseLockWaiters()).isEqualTo(1);
                assertThat(nextRequests).hasValue(0);
                assertThat(webhooks).hasValue(0);
                if (!lockTimesOut) {
                    holder.rollback(); // Let the already-started recording commit normally.
                }
                assertThat(batch.get(8, TimeUnit.SECONDS)).isTrue();
            } finally {
                holder.rollback();
            }
        }
        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(firstRequests).hasValue(1);
        assertThat(nextRequests).hasValue(0);
        assertThat(webhooks).hasValue(0);
        assertThat(checks.count()).isEqualTo(lockTimesOut ? 0 : 1);
        assertThat(incidents.count()).isEqualTo(lockTimesOut ? 0 : 1);
        assertThat(services.findById(first.getId()).orElseThrow().getConsecutiveFailures())
                .isEqualTo(lockTimesOut ? 0 : 1);
        assertThat(services.findById(next.getId()).orElseThrow().getConsecutiveFailures()).isZero();
        assertThat(output.getAll()).doesNotContain(SECRET, webhookUrl, "Map entry <", "MVStoreException");
    }

    private void awaitDatabaseLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (databaseLockWaiters() == 1) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("The scheduled writer never waited on the service database lock");
    }

    private int databaseLockWaiters() {
        return jdbc.queryForObject("""
                select count(*) from information_schema.sessions
                where blocker_id is not null and lower(executing_statement) like '%monitored_services%'
                """, Integer.class);
    }
}
