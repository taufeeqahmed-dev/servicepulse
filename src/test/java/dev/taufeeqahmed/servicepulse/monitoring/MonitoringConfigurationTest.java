package dev.taufeeqahmed.servicepulse.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.observability.ServicePulseMetrics;
import dev.taufeeqahmed.servicepulse.registration.MonitoredService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitoringConfigurationTest {

    @ParameterizedTest
    @CsvSource({"5s, 5", "PT2M, 120"})
    void registersFixedDelayFromConfigurationAndRunsItsCallback(String interval, long seconds) {
        var repository = mock(MonitoredServiceRepository.class);
        var checkService = mock(ServiceCheckService.class);
        var metrics = mock(ServicePulseMetrics.class);
        var taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        var registered = mock(MonitoredService.class);
        when(registered.getId()).thenReturn(1L);
        when(repository.findAll(Sort.by("id"))).thenReturn(List.of(registered));

        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringConfiguration.class)
                .withBean(MonitoredServiceRepository.class, () -> repository)
                .withBean(ServiceCheckService.class, () -> checkService)
                .withBean(ServicePulseMetrics.class, () -> metrics)
                .withBean(TaskScheduler.class, () -> taskScheduler)
                .withPropertyValues("servicepulse.monitoring.enabled=true",
                        "servicepulse.monitoring.poll-interval=" + interval)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ServiceMonitoringScheduler.class);
                    var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    var task = tasks.iterator().next().getTask();
                    assertThat(task).isInstanceOf(FixedDelayTask.class);
                    var fixedDelay = (FixedDelayTask) task;
                    assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofSeconds(seconds));
                    assertThat(fixedDelay.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(seconds));
                    var callback = ArgumentCaptor.forClass(Runnable.class);
                    verify(taskScheduler).scheduleWithFixedDelay(callback.capture(),
                            any(Instant.class), eq(Duration.ofSeconds(seconds)));

                    callback.getValue().run();

                    verify(checkService).check(1L);
                    verify(metrics).recordScheduledBatch();
                });

        verify(future).cancel(anyBoolean());
    }

    @Test
    void disabledMonitoringRegistersNoScheduledTask() {
        var repository = mock(MonitoredServiceRepository.class);
        var checkService = mock(ServiceCheckService.class);
        var metrics = mock(ServicePulseMetrics.class);
        var taskScheduler = mock(TaskScheduler.class);

        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringConfiguration.class)
                .withBean(MonitoredServiceRepository.class, () -> repository)
                .withBean(ServiceCheckService.class, () -> checkService)
                .withBean(ServicePulseMetrics.class, () -> metrics)
                .withBean(TaskScheduler.class, () -> taskScheduler)
                .withPropertyValues("servicepulse.monitoring.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(ServiceMonitoringScheduler.class)
                            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    verifyNoInteractions(taskScheduler, repository, checkService, metrics);
                });
    }
}
