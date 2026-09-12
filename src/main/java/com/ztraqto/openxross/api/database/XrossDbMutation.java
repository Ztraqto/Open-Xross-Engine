package com.ztraqto.openxross.api.database;

import com.fasterxml.jackson.databind.JsonNode;

public record XrossDbMutation(
        Operation operation,
        XrossDbKey key,
        JsonNode payload,
        long expectedRevision
) {

    public XrossDbMutation {
        if (operation == null || key == null) {
            throw new IllegalArgumentException("operation and key are required.");
        }
        if (operation == Operation.WRITE && payload == null) {
            throw new IllegalArgumentException("A write mutation requires a payload.");
        }
        if (operation == Operation.DELETE && payload != null) {
            throw new IllegalArgumentException("A delete mutation must not contain a payload.");
        }
    }

    public static XrossDbMutation write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        return new XrossDbMutation(Operation.WRITE, key, payload, expectedRevision);
    }

    public static XrossDbMutation delete(XrossDbKey key, long expectedRevision) {
        return new XrossDbMutation(Operation.DELETE, key, null, expectedRevision);
    }

    public enum Operation {
        WRITE,
        DELETE
    }
}
