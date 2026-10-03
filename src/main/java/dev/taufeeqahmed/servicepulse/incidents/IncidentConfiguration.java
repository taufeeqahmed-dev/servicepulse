package dev.taufeeqahmed.servicepulse.incidents;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IncidentConfiguration {

    @Bean
    Clock incidentClock() {
        return Clock.systemUTC();
    }
}
