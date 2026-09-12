package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.plugin.PluginMeta;

import java.time.Instant;
import java.util.Optional;

public final class PluginApprovalService implements IService {

    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PluginApprovalService(XrossDbClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public void init(XrossEngine engine) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public String getName() {
        return "PluginApprovalService";
    }

    public Optional<ApprovalRecord> find(String pluginId, String fingerprint) {
        return databaseClient.read(key(pluginId, fingerprint))
                .map(record -> mapper.convertValue(record.payload(), ApprovalRecord.class));
    }

    public boolean isApproved(String pluginId, String fingerprint) {
        return find(pluginId, fingerprint).map(record -> record.decision() == Decision.APPROVED).orElse(false);
    }

    public boolean isDenied(String pluginId, String fingerprint) {
        return find(pluginId, fingerprint).map(record -> record.decision() == Decision.DENIED).orElse(false);
    }

    /**
     * Resolves approval for the exact plugin artifact.
     *
     * <p>OpenXrossEngine intentionally does not inherit approval across JAR
     * fingerprints. A new artifact can execute arbitrary JVM code even when
     * its plugin id and requested permission set are unchanged, therefore every
     * fingerprint change requires an explicit Bot-administrator approval.</p>
     */
    public synchronized ApprovalResolution resolve(PluginMeta meta, String fingerprint) {
        Optional<ApprovalRecord> exact = find(meta.getId(), fingerprint);
        if (exact.isEmpty()) return ApprovalResolution.REQUIRES_APPROVAL;
        return exact.get().decision() == Decision.APPROVED
                ? ApprovalResolution.EXACT_APPROVAL
                : ApprovalResolution.DENIED;
    }

    public void approve(PluginMeta meta, String fingerprint, long administratorId) {
        save(meta, fingerprint, administratorId, Decision.APPROVED);
        saveTrustState(meta, fingerprint, administratorId, Decision.APPROVED);
    }

    public void deny(PluginMeta meta, String fingerprint, long administratorId) {
        save(meta, fingerprint, administratorId, Decision.DENIED);
        saveTrustState(meta, fingerprint, administratorId, Decision.DENIED);
    }

    private void save(PluginMeta meta, String fingerprint, long administratorId, Decision decision) {
        save(meta, fingerprint, administratorId, decision,
                meta.requestedPermissions().stream().map(Enum::name).sorted().toList(), null);
    }

    private void save(PluginMeta meta, String fingerprint, long administratorId, Decision decision,
                      java.util.List<String> permissionCeiling, String inheritedFromFingerprint) {
        ApprovalRecord record = new ApprovalRecord(
                meta.getId(),
                fingerprint,
                permissionCeiling == null ? java.util.List.of() : permissionCeiling.stream().sorted().toList(),
                decision,
                administratorId,
                Instant.now().getEpochSecond(),
                inheritedFromFingerprint
        );
        databaseClient.write(key(meta.getId(), fingerprint), mapper.valueToTree(record));
    }

    private void saveTrustState(PluginMeta meta, String fingerprint, long administratorId, Decision decision) {
        ApprovalRecord record = new ApprovalRecord(
                meta.getId(), fingerprint,
                meta.requestedPermissions().stream().map(Enum::name).sorted().toList(),
                decision, administratorId, Instant.now().getEpochSecond(), null
        );
        databaseClient.write(trustKey(meta.getId()), mapper.valueToTree(record));
    }

    private static XrossDbKey key(String pluginId, String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Plugin fingerprint is invalid.");
        }
        return new XrossDbKey("plugin-security", "approvals", pluginId + "@" + fingerprint);
    }

    private static XrossDbKey trustKey(String pluginId) {
        return new XrossDbKey("plugin-security", "approval-trust", pluginId);
    }

    public enum Decision {
        APPROVED,
        DENIED
    }

    public enum ApprovalResolution {
        EXACT_APPROVAL,
        /** @deprecated Since 1.2.2. Artifact approvals are fingerprint-exact. */
        @Deprecated
        INHERITED_APPROVAL,
        REQUIRES_APPROVAL,
        DENIED
    }

    public record ApprovalRecord(
            String pluginId,
            String fingerprint,
            java.util.List<String> permissions,
            Decision decision,
            long administratorId,
            long decidedAt,
            String inheritedFromFingerprint
    ) {
    }
}
