package com.apop.investigation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class InvestigationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
