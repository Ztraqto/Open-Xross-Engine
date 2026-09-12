package com.ztraqto.openxross.api.database;

import com.fasterxml.jackson.databind.JsonNode;

public record XrossDbRecord(
        XrossDbKey key,
        JsonNode payload,
        long revision,
        long updatedAt
) {
}
