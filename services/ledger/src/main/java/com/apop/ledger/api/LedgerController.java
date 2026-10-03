package com.apop.ledger.api;

import com.apop.ledger.domain.AccountRole;
import com.apop.ledger.domain.Direction;
import com.apop.ledger.domain.EntryType;
import com.apop.ledger.domain.NormalBalance;
import com.apop.ledger.service.AccountService;
import com.apop.ledger.service.AccountView;
import com.apop.ledger.service.HoldService;
import com.apop.ledger.service.HoldView;
import com.apop.ledger.service.OpenAccountCommand;
import com.apop.ledger.service.PlaceHoldCommand;
import com.apop.ledger.service.PostJournalCommand;
import com.apop.ledger.service.PostingService;
import com.apop.ledger.service.PostingView;
import com.apop.ledger.service.ReleaseHoldCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1")
public class LedgerController {

    private final AccountService accounts;
    private final HoldService holds;
    private final PostingService postings;

    public LedgerController(AccountService accounts, HoldService holds, PostingService postings) {
        this.accounts = accounts;
        this.holds = holds;
        this.postings = postings;
    }

    @PostMapping("/accounts")
    public AccountView open(@Valid @RequestBody OpenAccountRequest request) {
        return accounts.open(request.toCommand());
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView get(@PathVariable UUID accountId) {
        return accounts.get(accountId);
    }

    @PostMapping("/holds")
    public HoldView place(@Valid @RequestBody PlaceHoldRequest request) {
        return holds.place(request.toCommand());
    }

    @PostMapping("/hold-releases")
    public HoldView release(@Valid @RequestBody ReleaseHoldRequest request) {
        return holds.release(new ReleaseHoldCommand(request.idempotencyKey()));
    }

    @PostMapping("/postings")
    public PostingView post(@Valid @RequestBody PostJournalRequest request) {
        return postings.post(request.toCommand());
    }

    @GetMapping("/payments/{paymentId}/ledger")
    public PaymentLedgerResponse paymentLedger(@PathVariable UUID paymentId) {
        return new PaymentLedgerResponse(holds.findByPayment(paymentId), postings.findByPayment(paymentId));
    }

    public record PaymentLedgerResponse(List<HoldView> holds, List<PostingView> postings) {
    }

    public record OpenAccountRequest(
            @NotBlank @Size(max = 32) String legalEntityId,
            @NotNull AccountRole accountRole,
            @Size(max = 32) String glCode,
            @Size(max = 34) String accountNumber,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull NormalBalance normalBalance
    ) {
        OpenAccountCommand toCommand() {
            return new OpenAccountCommand(legalEntityId, accountRole, glCode, accountNumber, currency, normalBalance);
        }
    }

    public record PlaceHoldRequest(
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotNull UUID paymentId,
            @NotNull UUID accountId,
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull Instant expiresAt
    ) {
        PlaceHoldCommand toCommand() {
            return new PlaceHoldCommand(idempotencyKey, paymentId, accountId, amountMinor, currency, expiresAt);
        }
    }

    public record ReleaseHoldRequest(@NotBlank @Size(max = 128) String idempotencyKey) {
    }

    public record PostJournalRequest(
            @NotBlank @Size(max = 128) String idempotencyKey,
            UUID paymentId,
            @NotNull EntryType entryType,
            UUID reversalOfEntryId,
            @NotNull LocalDate valueDate,
            UUID holdId,
            @NotEmpty List<@Valid LineRequest> lines
    ) {
        PostJournalCommand toCommand() {
            return new PostJournalCommand(
                    idempotencyKey,
                    paymentId,
                    entryType,
                    reversalOfEntryId,
                    valueDate,
                    holdId,
                    lines.stream().map(LineRequest::toLine).toList());
        }
    }

    public record LineRequest(
            @NotNull UUID accountId,
            @NotNull Direction direction,
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency
    ) {
        PostJournalCommand.Line toLine() {
            return new PostJournalCommand.Line(accountId, direction, amountMinor, currency);
        }
    }
}
