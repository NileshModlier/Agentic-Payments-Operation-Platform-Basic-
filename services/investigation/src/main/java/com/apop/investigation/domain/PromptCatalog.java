package com.apop.investigation.domain;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptCatalog {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String systemPrompt(String role) {
        String file = switch (role) {
            case "SUPERVISOR" -> "prompts/supervisor.txt";
            case "FRAUD" -> "prompts/fraud.txt";
            case "ROOT_CAUSE" -> "prompts/root-cause.txt";
            case "SETTLEMENT" -> "prompts/settlement.txt";
            case "REPORTING" -> "prompts/reporting.txt";
            default -> throw new IllegalArgumentException("Unknown role");
        };
        return cache.computeIfAbsent(file, PromptCatalog::read);
    }

    public String runbook(String runbookId) {
        if (runbookId == null || !runbookId.matches("RB-[0-9]+")) {
            return null;
        }
        String path = "runbooks/" + runbookId + ".txt";
        if (!new ClassPathResource(path).exists()) {
            return null;
        }
        return cache.computeIfAbsent(path, PromptCatalog::read);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing prompt " + path, exception);
        }
    }
}
