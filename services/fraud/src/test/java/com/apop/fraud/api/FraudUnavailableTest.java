package com.apop.fraud.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FraudUnavailableTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("apop.messaging.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.flyway.locations", () -> "classpath:db/fraud");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void anUnreachableRedisFailCloses() throws Exception {
        UUID debtor = UUID.randomUUID();
        mvc.perform(post("/internal/v1/fraud-assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentId": "%s",
                                  "idempotencyKey": "down-%s",
                                  "amountMinor": 1000,
                                  "currency": "USD",
                                  "debtorAccountId": "%s",
                                  "creditorAccountId": "%s",
                                  "debtorCountry": "US",
                                  "creditorCountry": "US",
                                  "rail": "BOOK",
                                  "channel": "API",
                                  "submittedBy": "teller"
                                }
                                """.formatted(UUID.randomUUID(), debtor, debtor, UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reasonCode").value("TECHNICAL_DEPENDENCY"));
    }
}
