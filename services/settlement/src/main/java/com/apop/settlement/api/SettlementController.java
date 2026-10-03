package com.apop.settlement.api;

import com.apop.settlement.service.SettlementService;
import com.apop.settlement.service.SettlementView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class SettlementController {

    private final SettlementService settlement;

    public SettlementController(SettlementService settlement) {
        this.settlement = settlement;
    }

    @GetMapping("/v1/settlements/{paymentId}")
    public SettlementView get(@PathVariable UUID paymentId) {
        return settlement.view(paymentId);
    }

    @PostMapping("/internal/v1/settlement-items/{paymentId}/retries")
    public SettlementView retry(@PathVariable UUID paymentId) {
        SettlementView current = settlement.view(paymentId);
        settlement.settle(paymentId, null);
        return settlement.view(current.paymentId());
    }
}
