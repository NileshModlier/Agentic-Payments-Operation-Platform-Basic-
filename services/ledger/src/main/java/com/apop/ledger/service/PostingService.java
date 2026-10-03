package com.apop.ledger.service;

import java.util.List;
import java.util.UUID;

public interface PostingService {

    PostingView post(PostJournalCommand command);

    List<PostingView> findByPayment(UUID paymentId);
}
