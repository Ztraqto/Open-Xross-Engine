package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps engine-wide state in {@code xross_core} and each plugin in an
 * independent PostgreSQL schema.
 */
public final class PluginSplitXrossDbRepository implements XrossDbRepository {

    private static final String PLUGIN_DATA_NAMESPACE = "plugin-data";

    private final HikariDataSource dataSource;
    private final PostgresXrossDbRepository coreRepository;
    private final Map<String, PostgresXrossDbRepository> pluginRepositories = new LinkedHashMap<>();

    public PluginSplitXrossDbRepository(String postgresUrl, String postgresUser, String postgresPassword) {
        this.dataSource = PostgresXrossDbRepository.createDataSource(postgresUrl, postgresUser, postgresPassword);
        this.coreRepository = new PostgresXrossDbRepository(dataSource, "xross_core");
    }

    @Override
    public Optional<XrossDbRecord> read(XrossDbKey key) {
        return repositoryFor(key).read(key);
    }

    @Override
    public XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        return repositoryFor(key).write(key, payload, expectedRevision);
    }

    @Override
    public boolean delete(XrossDbKey key, long expectedRevision) {
        return repositoryFor(key).delete(key, expectedRevision);
    }

    @Override
    public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        return repositoryFor(namespace, collection).scanPage(namespace, collection, afterKey, limit);
    }

    @Override
    public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return coreRepository.applyBatch(mutations);
        }
        for (XrossDbMutation mutation : mutations) {
            if (mutation == null) {
                throw new IllegalArgumentException("A XrossDB batch must not contain null mutations.");
            }
        }
        XrossDbRepository repository = repositoryFor(mutations.get(0).key());
        for (XrossDbMutation mutation : mutations) {
            if (repositoryFor(mutation.key()) != repository) {
                throw new IllegalArgumentException("A XrossDB batch cannot span the core database and plugin databases.");
            }
        }
        return repository.applyBatch(mutations);
    }

    @Override
    public synchronized void close() {
        pluginRepositories.values().forEach(PostgresXrossDbRepository::close);
        pluginRepositories.clear();
        coreRepository.close();
        dataSource.close();
    }

    private synchronized PostgresXrossDbRepository repositoryFor(XrossDbKey key) {
        return repositoryFor(key.namespace(), key.collection());
    }

    private synchronized PostgresXrossDbRepository repositoryFor(String namespace, String collection) {
        if (!PLUGIN_DATA_NAMESPACE.equals(namespace)) {
            return coreRepository;
        }
        String schema = schemaForPlugin(collection);
        return pluginRepositories.computeIfAbsent(collection,
                ignored -> new PostgresXrossDbRepository(dataSource, schema));
    }

    /**
     * PostgreSQL identifiers are limited to 63 bytes. Replacing punctuation
     * alone made ids such as {@code alpha-beta} and {@code alpha.beta} share a
     * schema. Keep a readable prefix and bind it to the exact collection id
     * with 128 bits of SHA-256.
     */
    static String schemaForPlugin(String collection) {
        String normalized = XrossDbKey.validateName(collection, "collection").toLowerCase(Locale.ROOT);
        String readable = normalized.replaceAll("[^a-z0-9]", "_");
        if (readable.length() > 23) {
            readable = readable.substring(0, 23);
        }
        try {
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(normalized.getBytes(StandardCharsets.UTF_8))
            ).substring(0, 32);
            return "plugin_" + readable + "_" + digest;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
