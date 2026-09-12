package com.ztraqto.openxross.core.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Centralized JSON parser limits for untrusted or remotely supplied JSON.
 *
 * <p>The limits are intentionally conservative for OpenXross control/config
 * payloads and protect against excessive nesting, strings and numeric tokens.</p>
 */
public final class SecureJson {

    public static final int MAX_NESTING_DEPTH = 128;
    public static final int MAX_STRING_LENGTH = 1_000_000;
    public static final int MAX_NUMBER_LENGTH = 1_000;

    private SecureJson() {
    }

    public static ObjectMapper newMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        return new ObjectMapper(factory)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
