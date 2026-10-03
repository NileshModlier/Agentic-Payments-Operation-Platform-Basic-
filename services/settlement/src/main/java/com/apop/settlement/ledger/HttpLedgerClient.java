package com.apop.settlement.ledger;

import com.apop.settlement.config.SettlementProperties;
import com.apop.settlement.domain.JournalLine;
import com.apop.settlement.domain.SettlementItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class HttpLedgerClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpLedgerClient(RestClient.Builder builder, SettlementProperties properties, ObjectMapper mapper) {
        this.client = builder.baseUrl(properties.ledger().baseUrl()).build();
        this.mapper = mapper;
    }

    public UUID post(SettlementItem item, List<JournalLine> lines) {
        try {
            PostingBody body = client.post()
                    .uri("/internal/v1/postings")
                    .header("X-Correlation-Id", correlation())
                    .body(new PostingRequest(
                            item.getLedgerIdempotencyKey(),
                            item.getPaymentId(),
                            "POSTING",
                            item.getValueDate().toString(),
                            item.getHoldId(),
                            lines.stream().map(line -> new LineRequest(
                                    line.accountId(), line.direction(), line.amountMinor(), line.currency())).toList()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw map(response.getStatusCode().value(), read(response));
                    })
                    .body(PostingBody.class);
            if (body == null || body.journalEntryId() == null) {
                throw new LedgerUnavailableException("Ledger did not return a journal entry");
            }
            return body.journalEntryId();
        } catch (LedgerBusinessException | LedgerUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new LedgerUnavailableException("Ledger is unavailable", exception);
        }
    }

    public UUID findPostedJournal(UUID paymentId, String idempotencyKey) {
        try {
            LedgerBody body = client.get()
                    .uri("/internal/v1/payments/{paymentId}/ledger", paymentId)
                    .header("X-Correlation-Id", correlation())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw map(response.getStatusCode().value(), read(response));
                    })
                    .body(LedgerBody.class);
            if (body == null || body.postings() == null) {
                return null;
            }
            return body.postings().stream()
                    .filter(posting -> idempotencyKey.equals(posting.idempotencyKey()))
                    .map(PostingBody::journalEntryId)
                    .findFirst()
                    .orElse(null);
        } catch (LedgerBusinessException exception) {
            return null;
        } catch (LedgerUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new LedgerUnavailableException("Ledger is unavailable", exception);
        }
    }

    private RuntimeException map(int status, String body) {
        ProblemBody problem = parse(body);
        String reason = problem == null ? null : problem.reasonCode();
        String detail = problem == null ? "Ledger rejected the posting" : problem.detail();
        if (status == 409 || status == 422 || status == 404) {
            return new LedgerBusinessException(reason, detail);
        }
        return new LedgerUnavailableException(detail);
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

    private record PostingRequest(
            String idempotencyKey,
            UUID paymentId,
            String entryType,
            String valueDate,
            UUID holdId,
            List<LineRequest> lines
    ) {
    }

    private record LineRequest(UUID accountId, String direction, long amountMinor, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PostingBody(UUID journalEntryId, String idempotencyKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LedgerBody(List<PostingBody> postings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProblemBody(String detail, String reasonCode) {
    }
}
