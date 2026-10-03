package com.apop.investigation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class JavaServiceTools {

    private final RestClient http;
    private final String paymentBaseUrl;
    private final String fraudBaseUrl;
    private final String ledgerBaseUrl;
    private final String settlementBaseUrl;

    public JavaServiceTools(
            RestClient.Builder builder,
            @Value("${investigation.tools.payment-base-url}") String paymentBaseUrl,
            @Value("${investigation.tools.fraud-base-url}") String fraudBaseUrl,
            @Value("${investigation.tools.ledger-base-url}") String ledgerBaseUrl,
            @Value("${investigation.tools.settlement-base-url}") String settlementBaseUrl) {
        this.http = builder.build();
        this.paymentBaseUrl = paymentBaseUrl;
        this.fraudBaseUrl = fraudBaseUrl;
        this.ledgerBaseUrl = ledgerBaseUrl;
        this.settlementBaseUrl = settlementBaseUrl;
    }

    public JsonNode payment(UUID paymentId) {
        return get(paymentBaseUrl + "/v1/payments/" + paymentId);
    }

    public JsonNode fraudAssessment(UUID paymentId) {
        return get(fraudBaseUrl + "/internal/v1/fraud-assessments/" + paymentId);
    }

    public JsonNode ledger(UUID paymentId) {
        return get(ledgerBaseUrl + "/internal/v1/payments/" + paymentId + "/ledger");
    }

    public JsonNode settlement(UUID paymentId) {
        return get(settlementBaseUrl + "/v1/settlements/" + paymentId);
    }

    private JsonNode get(String url) {
        return http.get().uri(url).retrieve().body(JsonNode.class);
    }
}
