package com.ztraqto.openxross.config;

import java.util.Locale;

public enum XrossDbBackend {
    POSTGRES,
    LOCAL,
    MEMORY;

    public static XrossDbBackend parse(String value) {
        if (value == null || value.isBlank()) return POSTGRES;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("database.backend must be postgres, local or memory.", exception);
        }
    }
}
