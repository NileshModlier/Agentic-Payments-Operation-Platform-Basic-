package com.apop.payment.service;

import java.util.UUID;

public interface PaymentService {

    PaymentSubmission create(CreatePaymentCommand command);

    PaymentView get(UUID paymentId);

    PaymentView applySettlement(UUID paymentId, SettlementUpdate update);
}
