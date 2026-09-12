package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.util.List;
import java.util.Optional;

public interface XrossDbRepository extends AutoCloseable {
    Optional<XrossDbRecord> read(XrossDbKey key);
    XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision);
    boolean delete(XrossDbKey key, long expectedRevision);
    XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit);
    XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations);

    @Override
    void close();
}
