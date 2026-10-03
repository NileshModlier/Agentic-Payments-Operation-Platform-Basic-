package com.apop.ledger.service;

import java.util.UUID;

public interface AccountService {

    AccountView open(OpenAccountCommand command);

    AccountView get(UUID accountId);
}
