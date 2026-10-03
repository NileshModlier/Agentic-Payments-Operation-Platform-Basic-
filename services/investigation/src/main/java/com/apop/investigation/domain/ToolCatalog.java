package com.apop.investigation.domain;

import java.util.Set;

public final class ToolCatalog {

    public static final Set<String> READS = Set.of(
            "get_payment",
            "get_timeline",
            "get_fraud_assessment",
            "get_ledger_movements",
            "get_settlement_item",
            "get_break",
            "get_failure_case",
            "summarize_similar",
            "get_runbook",
            "get_case_summary"
    );

    public static final Set<String> FORBIDDEN = Set.of(
            "place_hold",
            "release_hold",
            "authorize_payment",
            "settle_payment",
            "release_fraud_review",
            "post_journal",
            "post_reversal",
            "sql",
            "shell",
            "http"
    );

    private ToolCatalog() {
    }

    public static boolean allowed(String role, String toolName) {
        if (toolName == null || FORBIDDEN.contains(toolName) || !READS.contains(toolName)) {
            return false;
        }
        return switch (role) {
            case "FRAUD" -> Set.of("get_payment", "get_timeline", "get_fraud_assessment", "summarize_similar").contains(toolName);
            case "ROOT_CAUSE" -> Set.of("get_payment", "get_timeline", "get_ledger_movements", "get_failure_case", "get_runbook").contains(toolName);
            case "SETTLEMENT" -> Set.of("get_payment", "get_timeline", "get_ledger_movements", "get_settlement_item", "get_break", "get_runbook").contains(toolName);
            case "REPORTING" -> Set.of("get_timeline", "get_case_summary").contains(toolName);
            default -> false;
        };
    }
}
