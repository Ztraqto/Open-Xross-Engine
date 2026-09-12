package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.util.List;
import java.util.Optional;

public final class XrossDbGateway {

    private final XrossDbRepository repository;

    public XrossDbGateway(XrossDbRepository repository) {
        this.repository = repository;
    }

    public Optional<XrossDbRecord> read(XrossDbKey key) {
        return repository.read(key);
    }

    public XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        return repository.write(key, payload, expectedRevision);
    }

    public boolean delete(XrossDbKey key, long expectedRevision) {
        return repository.delete(key, expectedRevision);
    }

    public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        return repository.scanPage(namespace, collection, afterKey, limit);
    }

    public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        return repository.applyBatch(mutations);
    }
}
