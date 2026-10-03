package com.apop.payment.validation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class ScreeningList {

    private final Set<UUID> accountIds;
    private final Set<String> names;

    public ScreeningList() {
        this.accountIds = readIds("screening/deny-account-ids.txt");
        this.names = readNames("screening/deny-names.txt");
    }

    public boolean deniesAccount(UUID accountId) {
        return accountId != null && accountIds.contains(accountId);
    }

    public boolean deniesName(String name) {
        return name != null && names.contains(name.trim().toUpperCase(Locale.ROOT));
    }

    private static Set<UUID> readIds(String path) {
        Set<UUID> ids = new HashSet<>();
        for (String line : lines(path)) {
            ids.add(UUID.fromString(line));
        }
        return Set.copyOf(ids);
    }

    private static Set<String> readNames(String path) {
        Set<String> names = new HashSet<>();
        for (String line : lines(path)) {
            names.add(line.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    private static Set<String> lines(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            Set<String> values = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    values.add(trimmed);
                }
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("Screening fixture could not be read: " + path, exception);
        }
    }
}
