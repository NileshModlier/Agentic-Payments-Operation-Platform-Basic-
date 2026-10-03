package com.apop.settlement.payment;

import com.apop.settlement.config.SettlementProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class HttpPaymentClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpPaymentClient(RestClient.Builder builder, SettlementProperties properties, ObjectMapper mapper) {
        this.client = builder.baseUrl(properties.payment().baseUrl()).build();
        this.mapper = mapper;
    }

    public PaymentSnapshot get(UUID paymentId) {
        PaymentBody body = get("/v1/payments/{paymentId}", PaymentBody.class, paymentId);
        if (body == null || body.paymentId() == null) {
            throw new PaymentUnavailableException("Payment service did not return the payment");
        }
        return new PaymentSnapshot(
                body.paymentId(),
                body.status(),
                body.rail(),
                body.debtorAccountId(),
                body.creditorAccountId(),
                body.amount().minor(),
                body.amount().currency(),
                body.valueDate(),
                body.holdId(),
                body.journalEntryId());
    }

    public void progress(UUID paymentId, String targetStatus, UUID journalEntryId, String reasonCode) {
        try {
            client.post()
                    .uri("/internal/v1/payments/{paymentId}/settlement", paymentId)
                    .header("X-Correlation-Id", correlation())
                    .body(new Progress(targetStatus, journalEntryId, reasonCode))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw map(response.getStatusCode().value(), read(response));
                    })
                    .toBodilessEntity();
        } catch (PaymentBusinessException | PaymentUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new PaymentUnavailableException("Payment service is unavailable", exception);
        }
    }

    private <T> T get(String path, Class<T> type, Object uriVariable) {
        try {
            return client.get()
                    .uri(path, uriVariable)
                    .header("X-Correlation-Id", correlation())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw map(response.getStatusCode().value(), read(response));
                    })
                    .body(type);
        } catch (PaymentBusinessException | PaymentUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new PaymentUnavailableException("Payment service is unavailable", exception);
        }
    }

    private RuntimeException map(int status, String body) {
        ProblemBody problem = parse(body);
        String reason = problem == null ? null : problem.reasonCode();
        String detail = problem == null ? "Payment service rejected the request" : problem.detail();
        if (status == 409 || status == 422 || status == 404) {
            return new PaymentBusinessException(reason == null ? "ILLEGAL_STATE" : reason, detail);
        }
        return new PaymentUnavailableException(detail);
    }

    private ProblemBody parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, ProblemBody.class);
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    private static String read(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            return "";
        }
    }

    private static String correlation() {
        String current = MDC.get("correlationId");
        return current == null ? UUID.randomUUID().toString() : current;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentBody(
            UUID paymentId,
            String status,
            String rail,
            UUID debtorAccountId,
            UUID creditorAccountId,
            Money amount,
            LocalDate valueDate,
            UUID holdId,
            UUID journalEntryId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Money(long minor, String currency) {
    }

    private record Progress(String targetStatus, UUID journalEntryId, String reasonCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProblemBody(String detail, String reasonCode) {
    }
}
