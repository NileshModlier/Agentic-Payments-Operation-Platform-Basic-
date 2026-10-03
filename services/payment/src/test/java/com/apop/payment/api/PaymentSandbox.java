package com.apop.payment.api;

import com.apop.ledger.LedgerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

final class PaymentSandbox {

    private static PostgreSQLContainer<?> paymentDb;
    private static PostgreSQLContainer<?> ledgerDb;
    private static ConfigurableApplicationContext ledger;
    private static String ledgerBaseUrl;

    private PaymentSandbox() {
    }

    static synchronized void start() {
        if (paymentDb != null) {
            return;
        }
        paymentDb = new PostgreSQLContainer<>("postgres:16-alpine");
        ledgerDb = new PostgreSQLContainer<>("postgres:16-alpine");
        try {
            paymentDb.start();
            ledgerDb.start();
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("server.port", "0");
            properties.put("spring.datasource.url", ledgerDb.getJdbcUrl());
            properties.put("spring.datasource.username", ledgerDb.getUsername());
            properties.put("spring.datasource.password", ledgerDb.getPassword());
            properties.put("spring.flyway.locations", "classpath:db/migration");
            properties.put("spring.jpa.hibernate.ddl-auto", "validate");
            properties.put("ledger.account-number-pepper", "local-dev-pepper");
            ledger = new SpringApplicationBuilder(LedgerApplication.class)
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("ledger-sandbox", properties)))
                    .run();
            ledgerBaseUrl = "http://127.0.0.1:" + ledger.getEnvironment().getProperty("local.server.port");
        } catch (RuntimeException exception) {
            paymentDb = null;
            ledgerDb = null;
            ledger = null;
            ledgerBaseUrl = null;
            throw exception;
        }
    }

    static String paymentJdbcUrl() {
        start();
        return paymentDb.getJdbcUrl();
    }

    static String paymentUsername() {
        start();
        return paymentDb.getUsername();
    }

    static String paymentPassword() {
        start();
        return paymentDb.getPassword();
    }

    static String ledgerBaseUrl() {
        start();
        return ledgerBaseUrl;
    }
}
