package com.apop.investigation.agent;

import com.apop.investigation.domain.PolicyGuard;

import java.util.List;
import java.util.Map;

public interface RecommendationModel {

    ModelTurn next(ModelRequest request);

    record ModelRequest(String role, String systemPrompt, String untrustedContent, List<String> toolLog, int turn) {
    }

    record ModelTurn(String toolName, Map<String, String> arguments, PolicyGuard.Draft draft, String report) {
        public static ModelTurn tool(String toolName, Map<String, String> arguments) {
            return new ModelTurn(toolName, arguments, null, null);
        }

        public static ModelTurn draft(PolicyGuard.Draft draft) {
            return new ModelTurn(null, Map.of(), draft, null);
        }

        public static ModelTurn report(String report) {
            return new ModelTurn(null, Map.of(), null, report);
        }
    }
}
