package com.ztraqto.openxross.api.database;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

public interface XrossDbClient extends AutoCloseable {

    long ANY_REVISION = -1L;

    Optional<XrossDbRecord> read(XrossDbKey key);
    XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision);
    boolean delete(XrossDbKey key, long expectedRevision);
    XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit);
    XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations);
    void verifyConnection();

    default List<XrossDbRecord> scan(String namespace, String collection) {
        java.util.ArrayList<XrossDbRecord> records = new java.util.ArrayList<>();
        String afterKey = null;
        do {
            XrossDbPage page = scanPage(namespace, collection, afterKey, 500);
            records.addAll(page.records());
            afterKey = page.nextKey();
        } while (afterKey != null);
        return List.copyOf(records);
    }

    default XrossDbRecord write(XrossDbKey key, JsonNode payload) {
        return write(key, payload, ANY_REVISION);
    }

    default boolean delete(XrossDbKey key) {
        return delete(key, ANY_REVISION);
    }

    @Override
    default void close() {
    }
}
