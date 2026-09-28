package dev.taufeeqahmed.servicepulse.monitoring;

import dev.taufeeqahmed.servicepulse.checking.ServiceCheckService;
import dev.taufeeqahmed.servicepulse.registration.MonitoredServiceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "servicepulse.monitoring", name = "enabled", havingValue = "true")
public class MonitoringConfiguration {

    @Bean
    ServiceMonitoringScheduler serviceMonitoringScheduler(MonitoredServiceRepository repository,
            ServiceCheckService checkService) {
        return new ServiceMonitoringScheduler(repository, checkService);
    }
}
