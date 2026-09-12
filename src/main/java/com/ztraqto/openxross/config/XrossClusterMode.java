package com.ztraqto.openxross.config;

import java.util.Locale;

/** Multi-process/multi-machine shard orchestration mode. */
public enum XrossClusterMode {
    OFF,
    STATIC,
    AUTO;

    public static XrossClusterMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "off", "disabled", "none" -> OFF;
            case "static", "manual" -> STATIC;
            case "auto", "automatic", "managed", "cluster" -> AUTO;
            default -> throw new IllegalArgumentException("Unsupported cluster mode: " + value);
        };
    }
}
