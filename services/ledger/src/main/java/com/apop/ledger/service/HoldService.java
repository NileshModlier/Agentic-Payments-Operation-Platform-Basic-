package com.apop.ledger.service;

import java.util.List;
import java.util.UUID;

public interface HoldService {

    HoldView place(PlaceHoldCommand command);

    HoldView release(ReleaseHoldCommand command);

    List<HoldView> findByPayment(UUID paymentId);
}
