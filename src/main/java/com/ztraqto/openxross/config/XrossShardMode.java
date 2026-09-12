package com.ztraqto.openxross.config;

import java.util.Locale;

/** Strategy used to choose the total Discord Gateway shard count. */
public enum XrossShardMode {
    /** Use the configured total exactly. */
    FIXED,
    /** Resolve Discord's recommended total once during startup. */
    RECOMMENDED,
    /** Start at least at Discord's recommendation and automatically scale upward later. */
    AUTO_SCALE;

    public static XrossShardMode parse(String value) {
        if (value == null || value.isBlank()) return FIXED;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "fixed" -> FIXED;
            case "recommended", "recommend" -> RECOMMENDED;
            case "auto", "autoscale", "auto_scale" -> AUTO_SCALE;
            default -> throw new IllegalArgumentException("Unsupported shard mode: " + value);
        };
    }
}
