package com.apop.investigation.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PolicyGuard {

    public static final String POLICY_VERSION = "investigation-guard-1";
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\b\\d{8,17}\\b");
    private static final Pattern EXECUTED_REVERSAL = Pattern.compile("(?i)reversal (has been|was) (posted|executed|applied)");
    private static final Set<String> FRAUD = Set.of("CONFIRM_DECLINE", "RELEASE_TO_AUTHORIZE", "ESCALATE");
    private static final Set<String> ROOT = Set.of("RETRY_TECHNICAL", "NO_ACTION", "ESCALATE");
    private static final Set<String> SETTLEMENT = Set.of("RETRY_TECHNICAL", "PROPOSE_REVERSAL", "NO_ACTION", "ESCALATE");
    private static final Set<String> SUPERVISOR = Set.of("ESCALATE");

    private PolicyGuard() {
    }

    public static Set<String> allowed(String role) {
        return switch (role) {
            case "SUPERVISOR" -> SUPERVISOR;
            case "FRAUD" -> FRAUD;
            case "ROOT_CAUSE" -> ROOT;
            case "SETTLEMENT" -> SETTLEMENT;
            default -> Set.of();
        };
    }

    public static GuardResult recommendation(String role, Draft draft, Set<String> retrieved, String paymentId) {
        if (draft == null || draft.disposition() == null || draft.narrative() == null || draft.evidenceIds() == null) {
            return GuardResult.refuse("SCHEMA");
        }
        if (draft.confidence() == null || draft.confidence().compareTo(BigDecimal.ZERO) < 0 || draft.confidence().compareTo(BigDecimal.ONE) > 0) {
            return GuardResult.refuse("CONFIDENCE");
        }
        if (draft.narrative().length() > 4000 || ACCOUNT_NUMBER.matcher(draft.narrative()).find()) {
            return GuardResult.refuse("NARRATIVE");
        }
        if (!allowed(role).contains(draft.disposition())) {
            return GuardResult.refuse("DISPOSITION");
        }
        for (String evidenceId : draft.evidenceIds()) {
            if (!retrieved.contains(evidenceId)) {
                return GuardResult.refuse("EVIDENCE");
            }
        }
        if ("RELEASE_TO_AUTHORIZE".equals(draft.disposition())) {
            boolean payment = draft.evidenceIds().contains("payment:" + paymentId);
            boolean assessment = draft.evidenceIds().stream().anyMatch(id -> id.startsWith("fraud-assessment:"));
            if (!payment || !assessment) {
                return GuardResult.refuse("RELEASE_CITATIONS");
            }
        }
        if ("PROPOSE_REVERSAL".equals(draft.disposition())) {
            boolean journal = draft.evidenceIds().stream().anyMatch(id -> id.startsWith("journal:"));
            if (!journal) {
                return GuardResult.refuse("REVERSAL_CITATION");
            }
        }
        return GuardResult.ACCEPT;
    }

    public static GuardResult report(String draft, Set<String> retrieved) {
        if (draft == null || draft.isBlank() || draft.length() > 4000 || ACCOUNT_NUMBER.matcher(draft).find()) {
            return GuardResult.refuse("NARRATIVE");
        }
        if (EXECUTED_REVERSAL.matcher(draft).find() && retrieved.stream().noneMatch(id -> id.startsWith("journal:"))) {
            return GuardResult.refuse("REPORT_REVERSAL_CLAIM");
        }
        String upper = draft.toUpperCase(Locale.ROOT);
        if (upper.contains("FEATURE SNAPSHOT") || upper.contains("REMITTANCE")) {
            return GuardResult.refuse("REPORT_RESTRICTED_DATA");
        }
        return GuardResult.ACCEPT;
    }

    public record Draft(String disposition, BigDecimal confidence, List<String> evidenceIds, String narrative) {
    }

    public record GuardResult(boolean accepted, String check) {
        public static final GuardResult ACCEPT = new GuardResult(true, null);

        public static GuardResult refuse(String check) {
            return new GuardResult(false, check);
        }
    }
}
