package com.ztraqto.openxross.api.database;

import java.util.regex.Pattern;

public record XrossDbKey(String namespace, String collection, String key) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");
    private static final int MAX_KEY_LENGTH = 512;

    public XrossDbKey {
        namespace = validateName(namespace, "namespace");
        collection = validateName(collection, "collection");

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required.");
        }
        key = key.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key must not exceed " + MAX_KEY_LENGTH + " characters.");
        }
    }

    public static String validateName(String value, String fieldName) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsupported characters or has an invalid length.");
        }
        return value;
    }
}
