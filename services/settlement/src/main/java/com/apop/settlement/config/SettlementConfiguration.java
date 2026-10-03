package com.apop.settlement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
