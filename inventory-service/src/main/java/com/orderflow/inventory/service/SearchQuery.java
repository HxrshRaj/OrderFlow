package com.orderflow.inventory.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Normalised catalogue search input. Free text is lower-cased and reduced to letters, digits
 * and spaces so it is safe to hand to {@code to_tsquery} and to a SQL {@code ILIKE}: no
 * operators, wildcards or quotes can get through.
 *
 * @param text    cleaned text, e.g. {@code "usb c hub"}
 * @param tsQuery prefix tsquery, e.g. {@code "usb:* & c:* & hub:*"}; empty when there is nothing to search
 */
public record SearchQuery(String text, String tsQuery) {

    public static SearchQuery parse(String raw) {
        String cleaned = raw == null ? ""
                : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return new SearchQuery("", "");
        }
        String ts = Arrays.stream(cleaned.split(" "))
                .map(token -> token + ":*")
                .collect(Collectors.joining(" & "));
        return new SearchQuery(cleaned, ts);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
