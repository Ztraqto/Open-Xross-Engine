package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.api.plugin.PluginMeta;
import com.ztraqto.openxross.api.plugin.PluginPermission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginApprovalServiceTest {

    private static final String FIRST = "a".repeat(64);
    private static final String UPDATE = "b".repeat(64);
    private static final String DENIED = "c".repeat(64);
    private static final String AFTER_DENIAL = "d".repeat(64);

    @Test
    void requiresApprovalForReplacementEvenWithSamePermissionCeiling() {
        InMemoryClient database = new InMemoryClient();
        PluginApprovalService approvals = new PluginApprovalService(database);
        PluginMeta meta = plugin("voice-activity-logger");

        approvals.approve(meta, FIRST, 42L);

        assertTrue(approvals.isApproved(meta.getId(), FIRST));
        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(meta, UPDATE));
        assertFalse(approvals.isApproved(meta.getId(), UPDATE));
    }

    @Test
    void requiresApprovalWhenReplacementAddsPermissions() {
        InMemoryClient database = new InMemoryClient();
        PluginApprovalService approvals = new PluginApprovalService(database);
        PluginMeta meta = plugin("permission-expansion");
        approvals.approve(meta, FIRST, 42L);
        meta.setPermissions(java.util.Set.of(
                PluginPermission.JDA_FULL_ACCESS,
                PluginPermission.XROSS_DB_FULL_ACCESS
        ));

        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(meta, UPDATE));
        assertFalse(approvals.isApproved(meta.getId(), UPDATE));
    }

    @Test
    void legacyApprovalDoesNotApproveDifferentFingerprint() {
        InMemoryClient database = new InMemoryClient();
        PluginMeta meta = plugin("legacy-approval");
        var legacy = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        legacy.put("pluginId", meta.getId());
        legacy.put("fingerprint", FIRST);
        legacy.putArray("permissions").add(PluginPermission.JDA_FULL_ACCESS.name());
        legacy.put("decision", "APPROVED");
        legacy.put("administratorId", 42L);
        legacy.put("decidedAt", 1L);
        database.write(new XrossDbKey("plugin-security", "approvals", meta.getId() + "@" + FIRST), legacy);

        PluginApprovalService approvals = new PluginApprovalService(database);
        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(meta, UPDATE));
        assertFalse(approvals.isApproved(meta.getId(), UPDATE));
    }

    @Test
    void everyNewFingerprintRequiresApprovalRegardlessOfPermissions() {
        InMemoryClient database = new InMemoryClient();
        PluginApprovalService approvals = new PluginApprovalService(database);
        PluginMeta meta = plugin("permission-ceiling");
        approvals.approve(meta, FIRST, 42L);

        meta.setPermissions(java.util.Set.of());
        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(meta, UPDATE));

        meta.setPermissions(java.util.Set.of(PluginPermission.JDA_FULL_ACCESS));
        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(meta, DENIED));
    }

    @Test
    void denialDoesNotApproveAnyLaterArtifact() {
        InMemoryClient database = new InMemoryClient();
        PluginApprovalService approvals = new PluginApprovalService(database);
        PluginMeta trusted = plugin("trusted-plugin");

        approvals.approve(trusted, FIRST, 42L);
        approvals.deny(trusted, DENIED, 42L);

        assertTrue(approvals.isDenied(trusted.getId(), DENIED));
        assertEquals(PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL,
                approvals.resolve(trusted, AFTER_DENIAL));
        assertFalse(approvals.isApproved(trusted.getId(), AFTER_DENIAL));
    }

    private static PluginMeta plugin(String id) {
        PluginMeta meta = new PluginMeta();
        meta.setId(id);
        meta.setName(id);
        meta.setVersion("1.0.0");
        meta.setMain("example.Plugin");
        meta.setPermissions(java.util.Set.of(PluginPermission.JDA_FULL_ACCESS));
        return meta;
    }

    private static final class InMemoryClient implements XrossDbClient {
        private final Map<XrossDbKey, XrossDbRecord> records = new LinkedHashMap<>();
        private long clock;

        @Override
        public Optional<XrossDbRecord> read(XrossDbKey key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
            XrossDbRecord previous = records.get(key);
            long revision = previous == null ? 1L : previous.revision() + 1L;
            XrossDbRecord record = new XrossDbRecord(key, payload.deepCopy(), revision, ++clock);
            records.put(key, record);
            return record;
        }

        @Override
        public boolean delete(XrossDbKey key, long expectedRevision) {
            return records.remove(key) != null;
        }

        @Override
        public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
            List<XrossDbRecord> result = records.values().stream()
                    .filter(record -> record.key().namespace().equals(namespace))
                    .filter(record -> record.key().collection().equals(collection))
                    .filter(record -> afterKey == null || record.key().key().compareTo(afterKey) > 0)
                    .sorted(Comparator.comparing(record -> record.key().key()))
                    .limit(limit)
                    .toList();
            return new XrossDbPage(result, null);
        }

        @Override
        public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verifyConnection() {
        }
    }
}
