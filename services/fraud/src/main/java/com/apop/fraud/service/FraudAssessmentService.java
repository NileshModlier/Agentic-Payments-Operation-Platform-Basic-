package com.apop.fraud.service;

import com.apop.fraud.domain.PolicyDocument;
import com.apop.fraud.domain.SubjectHasher;
import com.apop.fraud.domain.UuidV7;
import com.apop.fraud.messaging.AssessmentOutbox;
import com.apop.fraud.persistence.BeneficiaryEntry;
import com.apop.fraud.persistence.BeneficiaryRepository;
import com.apop.fraud.persistence.CountryEntry;
import com.apop.fraud.persistence.CountryListRepository;
import com.apop.fraud.persistence.DeviceEntry;
import com.apop.fraud.persistence.DeviceListRepository;
import com.apop.fraud.persistence.FraudAssessment;
import com.apop.fraud.persistence.FraudAssessmentRepository;
import com.apop.fraud.persistence.FraudPolicy;
import com.apop.fraud.persistence.FraudPolicyRepository;
import com.apop.fraud.persistence.FraudReview;
import com.apop.fraud.persistence.FraudReviewRepository;
import com.apop.fraud.persistence.FraudRuleHit;
import com.apop.fraud.persistence.FraudRuleHitRepository;
import com.apop.fraud.redis.RedisWindows;
import com.apop.fraud.scoring.FraudScorer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class FraudAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(FraudAssessmentService.class);

    private final FraudAssessmentRepository assessments;
    private final FraudRuleHitRepository hits;
    private final FraudReviewRepository reviews;
    private final FraudPolicyRepository policies;
    private final BeneficiaryRepository beneficiaries;
    private final DeviceListRepository devices;
    private final CountryListRepository countries;
    private final RedisWindows windows;
    private final AssessmentOutbox outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String pepper;

    public FraudAssessmentService(
            FraudAssessmentRepository assessments,
            FraudRuleHitRepository hits,
            FraudReviewRepository reviews,
            FraudPolicyRepository policies,
            BeneficiaryRepository beneficiaries,
            DeviceListRepository devices,
            CountryListRepository countries,
            RedisWindows windows,
            AssessmentOutbox outbox,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            Clock clock,
            String fraudPepper) {
        this.assessments = assessments;
        this.hits = hits;
        this.reviews = reviews;
        this.policies = policies;
        this.beneficiaries = beneficiaries;
        this.devices = devices;
        this.countries = countries;
        this.windows = windows;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.clock = clock;
        this.pepper = fraudPepper;
    }

    public AssessmentView assess(AssessCommand command) {
        String creditorHash = creditorHash(command);
        String deviceHash = command.deviceId() == null || command.deviceId().isBlank()
                ? null
                : SubjectHasher.hash(pepper, command.deviceId().trim());
        String sessionCountry = normalizeCountry(command.sessionCountry());
        String requestHash = requestHash(command, creditorHash, deviceHash, sessionCountry);
        FraudAssessment existing = assessments.findByPaymentId(command.paymentId()).orElse(null);
        if (existing != null) {
            return replay(existing, requestHash);
        }
        assessments.findByIdempotencyKey(command.idempotencyKey()).ifPresent(row -> {
            throw FraudException.conflict("Idempotency key was already used for a different payment");
        });
        PolicyDocument policy = activePolicy();
        Instant now = clock.instant();
        try {
            warm(command.debtorAccountId(), policy, now);
            boolean creditorSeen = windows.seenCreditor(command.debtorAccountId(), creditorHash);
            boolean deviceSeen = windows.seenDevice(command.debtorAccountId(), deviceHash);
            boolean countrySeen = windows.seenCountry(command.debtorAccountId(), sessionCountry);
            String[] previous = windows.previousSession(command.debtorAccountId());
            List<FraudScorer.Signals.Window> readings = new ArrayList<>();
            for (PolicyDocument.Window window : policy.windows()) {
                readings.add(windows.readAndAdd(
                        command.debtorAccountId(),
                        creditorHash,
                        deviceHash,
                        command.paymentId(),
                        command.amountMinor(),
                        now,
                        window.seconds(),
                        window.name()));
            }
            FraudScorer.Result result = FraudScorer.score(policy, new FraudScorer.Signals(
                    command.channel().toUpperCase(Locale.ROOT),
                    command.rail(),
                    sessionCountry,
                    normalizeCountry(command.debtorCountry()),
                    normalizeCountry(command.creditorCountry()),
                    deniedCreditor(creditorHash, now),
                    deniedDevice(deviceHash, now),
                    deniedCountry(sessionCountry, now),
                    allowedCreditor(creditorHash, now),
                    creditorSeen,
                    deviceSeen,
                    deviceHash != null,
                    countrySeen,
                    previous == null ? null : previous[0],
                    previous == null ? null : Instant.ofEpochMilli(Long.parseLong(previous[1])),
                    now,
                    readings));
            AssessmentView saved = transactions.execute(status -> persist(
                    command, policy, result, creditorHash, deviceHash, sessionCountry, requestHash, readings, now));
            if ("APPROVE".equals(result.decision().name())) {
                windows.markApproved(command.debtorAccountId(), creditorHash, deviceHash, sessionCountry,
                        Duration.ofSeconds(policy.geo().lookbackSeconds()));
            }
            windows.rememberSession(command.debtorAccountId(), sessionCountry, now, Duration.ofSeconds(policy.geo().lookbackSeconds()));
            return saved;
        } catch (RedisConnectionFailureException | org.springframework.data.redis.RedisSystemException exception) {
            throw FraudException.unavailable();
        }
    }

    public AssessmentView get(UUID paymentId) {
        FraudAssessment assessment = assessments.findByPaymentId(paymentId).orElseThrow(() -> FraudException.notFound("Assessment was not found"));
        expireIfDue(assessment.getPaymentId());
        return toView(assessment, hits.findByAssessmentId(assessment.getAssessmentId()), false);
    }

    public ReviewView decide(UUID paymentId, String decision, String rationale, String actor) {
        if (actor == null || actor.isBlank()) {
            throw FraudException.badRequest("X-Actor-Id is required");
        }
        if (!java.util.Set.of("CONFIRM_DECLINE", "RELEASE_TO_AUTHORIZE", "ESCALATE").contains(decision)) {
            throw FraudException.badRequest("Unsupported review decision");
        }
        if (rationale == null || rationale.isBlank()) {
            throw FraudException.badRequest("A rationale is required");
        }
        return transactions.execute(status -> {
            FraudReview review = reviews.findByPaymentId(paymentId).orElseThrow(() -> FraudException.notFound("Review was not found"));
            if (review.getExpiresAt().isBefore(clock.instant()) && review.isOpenForDecision()) {
                review.expire(clock.instant());
                throw FraudException.illegal("The fraud review expired");
            }
            if (!review.isOpenForDecision()) {
                throw FraudException.illegal("The fraud review is already decided");
            }
            if (actor.equals(review.getSubmittedBy())) {
                throw FraudException.forbidden("The person who submitted the payment cannot decide it");
            }
            review.decide(decision, rationale, actor, clock.instant());
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("paymentId", paymentId);
            audit.put("decision", decision);
            audit.put("actor", actor);
            outbox.audit(paymentId, "InvestigationDecisionRecorded", audit);
            log.info("fraud.review.decided paymentId={} decision={}", paymentId, decision);
            return new ReviewView(paymentId, review.getStatus(), review.getDecision(), review.getDecidedBy(), review.getReasonCode());
        });
    }

    private void warm(UUID debtorAccountId, PolicyDocument policy, Instant now) {
        if (windows.warmed(debtorAccountId)) {
            return;
        }
        Instant cutoff = now.minusSeconds(policy.longestWindowSeconds());
        for (FraudAssessment prior : assessments.findByDebtorAccountIdAndCreatedAtAfter(debtorAccountId, cutoff)) {
            JsonNode snapshot = read(prior.getFeatureSnapshot());
            String creditorHash = snapshot.path("creditorHash").asText(null);
            String deviceHash = textOrNull(snapshot, "deviceHash");
            String sessionCountry = textOrNull(snapshot, "sessionCountry");
            RedisWindows.Prior row = new RedisWindows.Prior(
                    prior.getPaymentId(),
                    debtorAccountId,
                    creditorHash,
                    deviceHash,
                    snapshot.path("amountMinor").asLong(),
                    prior.getCreatedAt());
            for (PolicyDocument.Window window : policy.windows()) {
                windows.remember(row, now, window.seconds());
            }
            if ("APPROVE".equals(prior.getDecision())) {
                windows.markApproved(debtorAccountId, creditorHash, deviceHash, sessionCountry,
                        Duration.ofSeconds(policy.geo().lookbackSeconds()));
            }
            windows.rememberSession(debtorAccountId, sessionCountry, prior.getCreatedAt(), Duration.ofSeconds(policy.geo().lookbackSeconds()));
        }
        windows.markWarm(debtorAccountId);
    }

    private AssessmentView persist(
            AssessCommand command,
            PolicyDocument policy,
            FraudScorer.Result result,
            String creditorHash,
            String deviceHash,
            String sessionCountry,
            String requestHash,
            List<FraudScorer.Signals.Window> readings,
            Instant now) {
        UUID assessmentId = UuidV7.generate();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestHash", requestHash);
        snapshot.put("debtorAccountId", command.debtorAccountId());
        snapshot.put("creditorHash", creditorHash);
        snapshot.put("deviceHash", deviceHash);
        snapshot.put("sessionCountry", sessionCountry);
        snapshot.put("debtorCountry", normalizeCountry(command.debtorCountry()));
        snapshot.put("creditorCountry", normalizeCountry(command.creditorCountry()));
        snapshot.put("channel", command.channel().toUpperCase(Locale.ROOT));
        snapshot.put("rail", command.rail());
        snapshot.put("amountMinor", command.amountMinor());
        snapshot.put("currency", command.currency());
        snapshot.put("hits", result.hits().stream().map(hit -> Map.of("ruleId", hit.ruleId(), "points", hit.points())).toList());
        snapshot.put("windows", readings);
        FraudAssessment assessment = FraudAssessment.create(
                assessmentId,
                command.paymentId(),
                command.idempotencyKey(),
                command.debtorAccountId(),
                policies.findFirstByActiveTrue().getPolicyVersion(),
                result.decision().name(),
                result.score(),
                write(snapshot),
                now);
        try {
            assessments.saveAndFlush(assessment);
        } catch (DataIntegrityViolationException exception) {
            FraudAssessment stored = assessments.findByPaymentId(command.paymentId()).orElseThrow(() ->
                    FraudException.conflict("Idempotency key was already used for a different payment"));
            return replay(stored, requestHash);
        }
        for (FraudScorer.Hit hit : result.hits()) {
            hits.save(FraudRuleHit.create(assessmentId, hit.ruleId(), hit.points()));
        }
        if (result.decision() == FraudScorer.Decision.REVIEW) {
            reviews.save(FraudReview.open(
                    UuidV7.generate(),
                    assessmentId,
                    command.paymentId(),
                    command.submittedBy(),
                    now,
                    now.plusSeconds(policy.reviewTtlSeconds())));
        }
        List<String> ruleIds = result.hits().stream().map(FraudScorer.Hit::ruleId).toList();
        outbox.assessmentCompleted(command.paymentId(), assessmentId, result.decision().name(), result.score(),
                assessment.getPolicyVersion(), ruleIds);
        log.info("fraud.assessed paymentId={} decision={} score={}", command.paymentId(), result.decision(), result.score());
        return toView(assessment, hits.findByAssessmentId(assessmentId), false);
    }

    private AssessmentView replay(FraudAssessment existing, String requestHash) {
        JsonNode snapshot = read(existing.getFeatureSnapshot());
        if (!requestHash.equals(snapshot.path("requestHash").asText())) {
            throw FraudException.conflict("Idempotency key was already used for a different payment");
        }
        return toView(existing, hits.findByAssessmentId(existing.getAssessmentId()), true);
    }

    private void expireIfDue(UUID paymentId) {
        transactions.executeWithoutResult(status -> reviews.findByPaymentId(paymentId).ifPresent(review -> {
            if (review.isOpenForDecision() && review.getExpiresAt().isBefore(clock.instant())) {
                review.expire(clock.instant());
            }
        }));
    }

    private PolicyDocument activePolicy() {
        FraudPolicy policy = policies.findFirstByActiveTrue();
        if (policy == null) {
            throw FraudException.unavailable();
        }
        try {
            return objectMapper.readValue(policy.getDocument(), PolicyDocument.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Active fraud policy is not readable", exception);
        }
    }

    private boolean deniedCreditor(String hash, Instant now) {
        return beneficiaries.findByListTypeAndCreditorHash("DENY", hash).filter(entry -> entry.isEffective(now)).isPresent();
    }

    private boolean allowedCreditor(String hash, Instant now) {
        return beneficiaries.findByListTypeAndCreditorHash("ALLOW", hash).filter(entry -> entry.isEffective(now)).isPresent();
    }

    private boolean deniedDevice(String hash, Instant now) {
        return hash != null && devices.findByDeviceHash(hash).filter(entry -> entry.isEffective(now)).isPresent();
    }

    private boolean deniedCountry(String country, Instant now) {
        if (country == null) {
            return false;
        }
        return countries.findByCountryHash(SubjectHasher.hash(pepper, country)).filter(entry -> entry.isEffective(now)).isPresent();
    }

    private String creditorHash(AssessCommand command) {
        if (command.creditorAccountId() != null) {
            return SubjectHasher.hash(pepper, command.creditorAccountId().toString());
        }
        if (command.creditorIdentity() != null && !command.creditorIdentity().isBlank()) {
            return SubjectHasher.hash(pepper, command.creditorIdentity().trim());
        }
        throw FraudException.badRequest("A creditor account or creditor identity is required");
    }

    private String requestHash(AssessCommand command, String creditorHash, String deviceHash, String sessionCountry) {
        String canonical = String.join("|",
                command.paymentId().toString(),
                command.idempotencyKey(),
                Long.toString(command.amountMinor()),
                command.currency(),
                command.debtorAccountId().toString(),
                creditorHash,
                normalizeCountry(command.debtorCountry()),
                normalizeCountry(command.creditorCountry()),
                command.rail(),
                command.channel().toUpperCase(Locale.ROOT),
                sessionCountry == null ? "" : sessionCountry,
                deviceHash == null ? "" : deviceHash,
                command.submittedBy());
        return SubjectHasher.hash(pepper, canonical);
    }

    private AssessmentView toView(FraudAssessment assessment, List<FraudRuleHit> ruleHits, boolean replayed) {
        return new AssessmentView(
                assessment.getAssessmentId(),
                assessment.getPaymentId(),
                assessment.getDecision(),
                assessment.getScore(),
                assessment.getPolicyVersion(),
                ruleHits.stream().map(hit -> new AssessmentView.RuleHit(hit.getRuleId(), hit.getPoints())).toList(),
                read(assessment.getFeatureSnapshot()),
                replayed);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Feature snapshot is not readable", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Feature snapshot could not be written", exception);
        }
    }

    private static String normalizeCountry(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return country.trim().toUpperCase(Locale.ROOT);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    public record AssessCommand(
            UUID paymentId,
            String idempotencyKey,
            long amountMinor,
            String currency,
            UUID debtorAccountId,
            UUID creditorAccountId,
            String creditorIdentity,
            String debtorCountry,
            String creditorCountry,
            String rail,
            String channel,
            String sessionCountry,
            String deviceId,
            String submittedBy
    ) {
    }

    public record AssessmentView(
            UUID assessmentId,
            UUID paymentId,
            String decision,
            int score,
            int policyVersion,
            List<RuleHit> ruleIds,
            JsonNode featureSnapshot,
            boolean replayed
    ) {
        public record RuleHit(String ruleId, int points) {
        }
    }

    public record ReviewView(UUID paymentId, String status, String decision, String decidedBy, String reasonCode) {
    }
}
