package com.ztraqto.openxross.runtime.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.util.List;
import java.util.Optional;

/** Shared-DB backed coordination store used by OpenXross multi-machine sharding. */
final class XrossDbClusterStore {
    private static final String NAMESPACE = "xross-cluster";
    // Storage key retained from 1.2.0 for rolling-upgrade compatibility. Public name: Xross Orchestrator.
    private static final String ORCHESTRATOR_COLLECTION = "control";
    private final XrossDbClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String clusterId;

    XrossDbClusterStore(XrossDbClient client, String clusterId) {
        this.client = client;
        this.clusterId = clusterId;
    }

    XrossDbClient client() { return client; }
    static String orchestratorCollectionName() { return ORCHESTRATOR_COLLECTION; }

    Optional<XrossDbRecord> readRaw(String collection, String key) {
        return client.read(key(collection, key));
    }

    Optional<XrossClusterTopology> readTopology() {
        return readRaw("topology", "active").map(record -> mapper.convertValue(record.payload(), XrossClusterTopology.class));
    }

    XrossDbRecord writeTopology(XrossClusterTopology topology, long expectedRevision) {
        return client.write(key("topology", "active"), mapper.valueToTree(topology), expectedRevision);
    }

    Optional<XrossClusterLease> readLeader() {
        return readRaw(ORCHESTRATOR_COLLECTION, "leader").map(record -> mapper.convertValue(record.payload(), XrossClusterLease.class));
    }

    Optional<XrossDbRecord> readLeaderRaw() {
        return readRaw(ORCHESTRATOR_COLLECTION, "leader");
    }

    XrossDbRecord writeLeader(XrossClusterLease lease, long expectedRevision) {
        return client.write(key(ORCHESTRATOR_COLLECTION, "leader"), mapper.valueToTree(lease), expectedRevision);
    }

    Optional<XrossDbRecord> readStartupLeaseRaw() {
        return readRaw(ORCHESTRATOR_COLLECTION, "startup-lease");
    }

    Optional<XrossClusterLease> readStartupLease() {
        return readStartupLeaseRaw().map(record -> mapper.convertValue(record.payload(), XrossClusterLease.class));
    }

    XrossDbRecord writeStartupLease(XrossClusterLease lease, long expectedRevision) {
        return client.write(key(ORCHESTRATOR_COLLECTION, "startup-lease"), mapper.valueToTree(lease), expectedRevision);
    }

    void releaseStartupLease(String nodeId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<XrossDbRecord> current = readStartupLeaseRaw();
            if (current.isEmpty()) return;
            XrossClusterLease lease = mapper.convertValue(current.get().payload(), XrossClusterLease.class);
            if (!nodeId.equals(lease.nodeId())) return;
            try {
                client.delete(key(ORCHESTRATOR_COLLECTION, "startup-lease"), current.get().revision());
                return;
            } catch (XrossDbConflictException ignored) {
            }
        }
    }

    Optional<XrossClusterNodeRecord> readNode(String nodeId) {
        return client.read(key("nodes", nodeId)).map(record -> mapper.convertValue(record.payload(), XrossClusterNodeRecord.class));
    }

    void writeNode(XrossClusterNodeRecord node) {
        client.write(key("nodes", node.nodeId()), mapper.valueToTree(node));
    }

    List<XrossClusterNodeRecord> listNodes() {
        return client.scan(NAMESPACE, scopedCollection("nodes")).stream()
                .map(record -> mapper.convertValue(record.payload(), XrossClusterNodeRecord.class))
                .toList();
    }

    void deleteNode(String nodeId) {
        client.delete(key("nodes", nodeId));
    }

    void writeEvent(String key, String type, String nodeId, String detail) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", type);
        payload.put("nodeId", nodeId);
        payload.put("detail", detail == null ? "" : detail);
        payload.put("timestamp", System.currentTimeMillis());
        client.write(key("events", key), payload);
    }

    private XrossDbKey key(String collection, String key) {
        return new XrossDbKey(NAMESPACE, scopedCollection(collection), key);
    }

    private String scopedCollection(String collection) {
        return clusterId + "--" + collection;
    }
}
