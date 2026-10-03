package com.apop.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({PaymentProperties.class, MessagingProperties.class})
public class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
