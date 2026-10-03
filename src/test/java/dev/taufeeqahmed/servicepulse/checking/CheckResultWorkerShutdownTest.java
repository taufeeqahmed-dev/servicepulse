package dev.taufeeqahmed.servicepulse.checking;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.zaxxer.hikari.HikariDataSource;
import dev.taufeeqahmed.servicepulse.ServicePulseApplication;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class CheckResultWorkerShutdownTest {

    @Test
    void applicationShutdownDrainsRecordingBeforeClosingJpaAndConnectionPool() throws Exception {
        try (var context = new SpringApplicationBuilder(ServicePulseApplication.class)
                .web(WebApplicationType.NONE)
                .run("--servicepulse.monitoring.enabled=false", "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.datasource.url=jdbc:h2:mem:worker-shutdown-test;LOCK_TIMEOUT=10000");
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var services = context.getBean(MonitoredServiceRepository.class);
            var recorder = context.getBean(CheckResultService.class);
            var dataSource = context.getBean(HikariDataSource.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var workers = (ExecutorService) ReflectionTestUtils.getField(recorder, "persistenceWorkers");
            var service = services.save(new MonitoredService("Shutdown", "http://localhost/health", 1, null));
            var result = new HealthCheckResponse(service.getId(), service.getName(), service.getUrl(),
                    HealthStatus.DOWN, 503, 1, Instant.now());
            Future<?> recording;
            Future<?> shutdown;
            try (var holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (var lock = holder.prepareStatement("select * from monitored_services where id=? for update")) {
                    lock.setLong(1, service.getId());
                    lock.executeQuery().close();
                    recording = callers.submit(() -> recorder.record(service, result));
                    await(() -> jdbc.queryForObject("""
                            select count(*) from information_schema.sessions where blocker_id is not null
                            """, Integer.class) == 1);
                    shutdown = callers.submit(context::close);
                    await(workers::isShutdown); // Spring has invoked the recorder's destruction callback.
                    assertThat(shutdown.isDone()).isFalse();
                    assertThat(recording.isDone()).isFalse();
                    assertThat(dataSource.isClosed()).isFalse();
                } finally {
                    holder.rollback();
                }
            }
            recording.get(5, TimeUnit.SECONDS); // The real JPA transaction can still commit during shutdown.
            shutdown.get(5, TimeUnit.SECONDS);
            assertThat(workers.isTerminated()).isTrue();
            assertThat(dataSource.isClosed()).isTrue();
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
