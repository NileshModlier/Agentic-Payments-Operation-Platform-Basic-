package com.apop.investigation.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class JavaServiceToolsTest {

    @Test
    void readsAreGetsAgainstTheOwningService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JavaServiceTools tools = new JavaServiceTools(builder, "http://payment", "http://fraud", "http://ledger", "http://settlement");
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo("http://payment/v1/payments/" + paymentId))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"FRAUD_REVIEW\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fraud/internal/v1/fraud-assessments/" + paymentId))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"decision\":\"REVIEW\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ledger/internal/v1/payments/" + paymentId + "/ledger"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"holds\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://settlement/v1/settlements/" + paymentId))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"POSTED\"}", MediaType.APPLICATION_JSON));

        assertThat(tools.payment(paymentId).path("status").asText()).isEqualTo("FRAUD_REVIEW");
        assertThat(tools.fraudAssessment(paymentId).path("decision").asText()).isEqualTo("REVIEW");
        assertThat(tools.ledger(paymentId).path("holds").isArray()).isTrue();
        assertThat(tools.settlement(paymentId).path("status").asText()).isEqualTo("POSTED");
        server.verify();
    }

    @Test
    void accountNumbersAreMaskedAndRemittanceIsDropped() throws Exception {
        var masked = AccountMask.apply(new ObjectMapper().readTree("""
                {"debtorAccountNumber":"123456789012","remittance":"pay the invoice","status":"FRAUD_REVIEW"}
                """));
        assertThat(masked.path("debtorAccountNumber").asText()).isEqualTo("****9012");
        assertThat(masked.has("remittance")).isFalse();
        assertThat(masked.path("status").asText()).isEqualTo("FRAUD_REVIEW");
    }
}
