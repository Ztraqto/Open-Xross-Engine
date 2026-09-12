package com.ztraqto.openxross.db.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.ztraqto.openxross.api.database.XrossDbMutation;

import java.util.List;

public final class XrossDbProtocol {

    public static final int VERSION = 1;

    private XrossDbProtocol() {
    }

    public record KeyRequest(String namespace, String collection, String key) {
    }

    public record WriteRequest(
            String namespace,
            String collection,
            String key,
            JsonNode payload,
            long expectedRevision
    ) {
    }

    public record DeleteRequest(
            String namespace,
            String collection,
            String key,
            long expectedRevision
    ) {
    }

    public record ScanRequest(String namespace, String collection, String afterKey, int limit) {
    }

    public record BatchRequest(List<XrossDbMutation> mutations) {
    }

    public record DeleteResponse(boolean deleted) {
    }

    public record HealthResponse(String status, int protocolVersion) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
