package com.apop.investigation.agent;

import com.apop.investigation.domain.PolicyGuard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DeterministicRecommendationModel implements RecommendationModel {

    @Override
    public ModelTurn next(ModelRequest request) {
        for (String tool : required(request.role())) {
            if (request.toolLog().stream().noneMatch(line -> line.startsWith(tool + "|OK|"))) {
                if ("get_runbook".equals(tool)) {
                    String runbook = "ROOT_CAUSE".equals(request.role()) ? "RB-4" : "RB-5";
                    return ModelTurn.tool(tool, Map.of("runbookId", runbook));
                }
                return ModelTurn.tool(tool, Map.of());
            }
        }
        if ("REPORTING".equals(request.role())) {
            return ModelTurn.report(report(request));
        }
        return ModelTurn.draft(draft(request));
    }

    private static List<String> required(String role) {
        return switch (role) {
            case "FRAUD" -> List.of("get_payment", "get_fraud_assessment");
            case "ROOT_CAUSE" -> List.of("get_payment", "get_failure_case", "get_runbook");
            case "SETTLEMENT" -> List.of("get_break", "get_ledger_movements", "get_runbook");
            case "REPORTING" -> List.of("get_case_summary");
            default -> List.of();
        };
    }

    private static PolicyGuard.Draft draft(ModelRequest request) {
        String evidence = String.join("\n", request.toolLog());
        List<String> ids = evidenceIds(request.toolLog());
        String upper = evidence.toUpperCase(Locale.ROOT);
        return switch (request.role()) {
            case "FRAUD" -> fraud(upper, ids);
            case "ROOT_CAUSE" -> new PolicyGuard.Draft(
                    upper.contains("UNCERTAIN") || upper.contains("TIMEOUT") ? "RETRY_TECHNICAL" : "NO_ACTION",
                    new BigDecimal("0.740"),
                    ids,
                    "The failure evidence points at a technical dependency. This is a recommendation, not a retry.");
            case "SETTLEMENT" -> new PolicyGuard.Draft(
                    upper.contains("AMOUNT_MISMATCH") && ids.stream().anyMatch(id -> id.startsWith("journal:"))
                            ? "PROPOSE_REVERSAL" : "NO_ACTION",
                    new BigDecimal("0.810"),
                    ids,
                    "The break is an amount mismatch. A human must approve any reversal. The journal is unchanged.");
            default -> new PolicyGuard.Draft("ESCALATE", new BigDecimal("1.000"), List.of(),
                    "No specialist is assigned for this case.");
        };
    }

    private static PolicyGuard.Draft fraud(String upper, List<String> ids) {
        int score = score(upper);
        String disposition;
        if (upper.contains("LIST_") || upper.contains("DENY") || score >= 500) {
            disposition = "CONFIRM_DECLINE";
        } else if (score < 250) {
            disposition = "RELEASE_TO_AUTHORIZE";
        } else {
            disposition = "ESCALATE";
        }
        return new PolicyGuard.Draft(disposition, new BigDecimal("0.860"), ids,
                "Fraud assessment evidence supports " + disposition + ". The payment stays unchanged until a human decides.");
    }

    private static int score(String upper) {
        int marker = upper.indexOf("\"SCORE\":");
        if (marker < 0) {
            return 0;
        }
        int start = marker + "\"SCORE\":".length();
        int end = start;
        while (end < upper.length() && Character.isDigit(upper.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        return Integer.parseInt(upper.substring(start, end));
    }

    private static List<String> evidenceIds(List<String> toolLog) {
        List<String> ids = new ArrayList<>();
        for (String line : toolLog) {
            String[] parts = line.split("\\|", 4);
            if (parts.length < 3 || !"OK".equals(parts[1])) {
                continue;
            }
            for (String id : parts[2].split(",")) {
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String report(ModelRequest request) {
        return """
                Scope
                One investigation case from the untrusted case channel.
                Outcome
                Dispositions below were recorded by humans. The agent did not execute them.
                Evidence
                Evidence ids already stored on the case.
                Open items
                None proposed by this draft.
                Money movement
                None recorded by the agent. A hold or journal entry is quoted only when the case evidence already has one.
                """ + String.join("\n", request.toolLog());
    }
}
