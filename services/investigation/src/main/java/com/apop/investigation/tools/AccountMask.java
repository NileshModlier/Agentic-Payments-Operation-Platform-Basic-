package com.apop.investigation.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

public final class AccountMask {

    private AccountMask() {
    }

    public static JsonNode apply(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = node.deepCopy();
            java.util.List<String> names = new java.util.ArrayList<>();
            copy.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = copy.get(name);
                String key = name.toLowerCase();
                if (key.contains("remittance")) {
                    copy.remove(name);
                } else if (key.contains("accountnumber") && child.isTextual()) {
                    copy.set(name, TextNode.valueOf(lastFour(child.asText())));
                } else if (child.isObject() || child.isArray()) {
                    copy.set(name, apply(child));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = node.deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) copy).set(i, apply(copy.get(i)));
            }
            return copy;
        }
        return node;
    }

    public static String lastFour(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }
}
