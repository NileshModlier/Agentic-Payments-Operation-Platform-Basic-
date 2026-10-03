package com.apop.settlement.api;

import com.apop.ledger.LedgerApplication;
import com.apop.payment.PaymentApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

final class SettlementSandbox {

    private static PostgreSQLContainer<?> paymentDb;
    private static PostgreSQLContainer<?> ledgerDb;
    private static PostgreSQLContainer<?> settlementDb;
    private static KafkaContainer kafka;
    private static ConfigurableApplicationContext ledger;
    private static ConfigurableApplicationContext payment;
    private static String ledgerBaseUrl;
    private static String paymentBaseUrl;

    private SettlementSandbox() {
    }

    static synchronized void start() {
        if (settlementDb != null) {
            return;
        }
        paymentDb = new PostgreSQLContainer<>("postgres:16-alpine");
        ledgerDb = new PostgreSQLContainer<>("postgres:16-alpine");
        settlementDb = new PostgreSQLContainer<>("postgres:16-alpine");
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.1"));
        try {
            paymentDb.start();
            ledgerDb.start();
            settlementDb.start();
            kafka.start();
            ledger = new SpringApplicationBuilder(LedgerApplication.class)
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("ledger-sandbox", ledgerProperties())))
                    .run();
            ledgerBaseUrl = "http://127.0.0.1:" + ledger.getEnvironment().getProperty("local.server.port");
            payment = new SpringApplicationBuilder(PaymentApplication.class)
                    .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("payment-sandbox", paymentProperties())))
                    .run();
            paymentBaseUrl = "http://127.0.0.1:" + payment.getEnvironment().getProperty("local.server.port");
        } catch (RuntimeException exception) {
            paymentDb = null;
            ledgerDb = null;
            settlementDb = null;
            kafka = null;
            ledger = null;
            payment = null;
            ledgerBaseUrl = null;
            paymentBaseUrl = null;
            throw exception;
        }
    }

    private static Map<String, Object> ledgerProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", ledgerDb.getJdbcUrl());
        properties.put("spring.datasource.username", ledgerDb.getUsername());
        properties.put("spring.datasource.password", ledgerDb.getPassword());
        properties.put("spring.flyway.locations", "classpath:db/migration");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("ledger.account-number-pepper", "local-dev-pepper");
        properties.put("spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        return properties;
    }

    private static Map<String, Object> paymentProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", paymentDb.getJdbcUrl());
        properties.put("spring.datasource.username", paymentDb.getUsername());
        properties.put("spring.datasource.password", paymentDb.getPassword());
        properties.put("spring.flyway.locations", "classpath:db/payment");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("apop.fraud.mode", "local");
        properties.put("apop.default-legal-entity", "SIM-BANK");
        properties.put("apop.ledger.base-url", ledgerBaseUrl);
        properties.put("apop.validation.max-amount-minor", "1000000000");
        properties.put("apop.validation.zone", "America/New_York");
        properties.put("apop.validation.cutoff", "17:00");
        properties.put("apop.remittance-key", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.put("apop.messaging.enabled", "true");
        properties.put("apop.messaging.topic", "payments.payment.lifecycle.v1");
        properties.put("apop.messaging.partitions", "12");
        properties.put("apop.messaging.replicas", "1");
        properties.put("apop.messaging.relay-delay-ms", "200");
        properties.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
        properties.put("spring.kafka.consumer.group-id", "transaction-history-projector");
        return properties;
    }

    static String settlementJdbcUrl() {
        start();
        return settlementDb.getJdbcUrl();
    }

    static String settlementUsername() {
        start();
        return settlementDb.getUsername();
    }

    static String settlementPassword() {
        start();
        return settlementDb.getPassword();
    }

    static String ledgerBaseUrl() {
        start();
        return ledgerBaseUrl;
    }

    static String paymentBaseUrl() {
        start();
        return paymentBaseUrl;
    }

    static String kafkaBootstrap() {
        start();
        return kafka.getBootstrapServers();
    }
}
