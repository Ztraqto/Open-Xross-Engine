package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.util.List;
import java.util.Optional;

public final class InProcessXrossDbClient implements XrossDbClient {

    private final XrossDbGateway gateway;

    public InProcessXrossDbClient(XrossDbGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Optional<XrossDbRecord> read(XrossDbKey key) {
        return gateway.read(key);
    }

    @Override
    public XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        return gateway.write(key, payload, expectedRevision);
    }

    @Override
    public boolean delete(XrossDbKey key, long expectedRevision) {
        return gateway.delete(key, expectedRevision);
    }

    @Override
    public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        return gateway.scanPage(namespace, collection, afterKey, limit);
    }

    @Override
    public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        return gateway.applyBatch(mutations);
    }

    @Override
    public void verifyConnection() {
    }
}
