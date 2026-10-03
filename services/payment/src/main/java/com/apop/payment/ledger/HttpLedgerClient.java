package com.apop.payment.ledger;

import com.apop.payment.config.PaymentProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class HttpLedgerClient implements LedgerClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpLedgerClient(RestClient.Builder builder, PaymentProperties properties, ObjectMapper mapper) {
        this.client = builder.baseUrl(properties.ledger().baseUrl()).build();
        this.mapper = mapper;
    }

    @Override
    public AccountSnapshot getAccount(UUID accountId) {
        AccountBody body = get("/internal/v1/accounts/{accountId}", AccountBody.class, accountId);
        return new AccountSnapshot(body.accountId(), body.currency(), body.status());
    }

    @Override
    public HoldSnapshot placeHold(HoldRequest request) {
        HoldBody body = client.post()
                .uri("/internal/v1/holds")
                .header("X-Correlation-Id", correlationId())
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw map(response.getStatusCode().value(), read(response));
                })
                .body(HoldBody.class);
        if (body == null || body.holdId() == null) {
            throw new LedgerUnavailableException("Ledger did not return a hold");
        }
        return new HoldSnapshot(body.holdId(), body.status());
    }

    @Override
    public HoldSnapshot findHold(UUID paymentId) {
        LedgerBody body = get("/internal/v1/payments/{paymentId}/ledger", LedgerBody.class, paymentId);
        if (body == null || body.holds() == null || body.holds().isEmpty()) {
            return null;
        }
        HoldBody hold = body.holds().getFirst();
        return new HoldSnapshot(hold.holdId(), hold.status());
    }

    private <T> T get(String path, Class<T> type, Object uriVariable) {
        return client.get()
                .uri(path, uriVariable)
                .header("X-Correlation-Id", correlationId())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw map(response.getStatusCode().value(), read(response));
                })
                .body(type);
    }

    private RuntimeException map(int status, String body) {
        ProblemBody problem = parse(body);
        if (status == 404) {
            return new LedgerNotFoundException();
        }
        if (status == 422) {
            return new LedgerBusinessException(
                    problem == null ? null : problem.reasonCode(),
                    problem == null ? "Ledger rejected the request" : problem.detail());
        }
        return new LedgerUnavailableException("Ledger returned HTTP " + status);
    }

    private ProblemBody parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, ProblemBody.class);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String read(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AccountBody(UUID accountId, String currency, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HoldBody(UUID holdId, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LedgerBody(List<HoldBody> holds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProblemBody(String reasonCode, String detail) {
    }
}
