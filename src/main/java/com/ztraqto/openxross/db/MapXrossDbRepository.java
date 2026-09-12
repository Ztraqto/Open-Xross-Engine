package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.api.database.XrossDbBatchResult;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbMutation;
import com.ztraqto.openxross.api.database.XrossDbPage;
import com.ztraqto.openxross.api.database.XrossDbRecord;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Single-process XrossDB repository backed by memory or one atomic JSON file. */
public final class MapXrossDbRepository implements XrossDbRepository {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 100;
    private static final Comparator<XrossDbKey> KEY_ORDER = Comparator
            .comparing(XrossDbKey::namespace)
            .thenComparing(XrossDbKey::collection)
            .thenComparing(XrossDbKey::key);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path storagePath;
    private final Map<XrossDbKey, XrossDbRecord> records;

    public static MapXrossDbRepository memory() {
        return new MapXrossDbRepository(null);
    }

    public static MapXrossDbRepository local(Path storagePath) {
        if (storagePath == null) throw new IllegalArgumentException("storagePath is required.");
        return new MapXrossDbRepository(storagePath.toAbsolutePath().normalize());
    }

    private MapXrossDbRepository(Path storagePath) {
        this.storagePath = storagePath;
        this.records = load();
    }

    @Override
    public synchronized Optional<XrossDbRecord> read(XrossDbKey key) {
        return Optional.ofNullable(copy(records.get(key)));
    }

    @Override
    public synchronized XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        if (payload == null) throw new IllegalArgumentException("payload is required.");
        Map<XrossDbKey, XrossDbRecord> working = new LinkedHashMap<>(records);
        XrossDbRecord written = write(working, key, payload, expectedRevision);
        commit(working);
        return copy(written);
    }

    @Override
    public synchronized boolean delete(XrossDbKey key, long expectedRevision) {
        Map<XrossDbKey, XrossDbRecord> working = new LinkedHashMap<>(records);
        boolean deleted = delete(working, key, expectedRevision);
        if (deleted) commit(working);
        return deleted;
    }

    @Override
    public synchronized XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        String normalizedNamespace = XrossDbKey.validateName(namespace, "namespace");
        String normalizedCollection = XrossDbKey.validateName(collection, "collection");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        List<XrossDbRecord> matches = records.values().stream()
                .filter(record -> record.key().namespace().equals(normalizedNamespace))
                .filter(record -> record.key().collection().equals(normalizedCollection))
                .filter(record -> afterKey == null || afterKey.isBlank() || record.key().key().compareTo(afterKey) > 0)
                .sorted(Comparator.comparing(record -> record.key().key()))
                .limit((long) limit + 1L)
                .map(MapXrossDbRepository::copy)
                .toList();
        String nextKey = matches.size() > limit ? matches.get(limit - 1).key().key() : null;
        return new XrossDbPage(matches.size() > limit ? matches.subList(0, limit) : matches, nextKey);
    }

    @Override
    public synchronized XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) return new XrossDbBatchResult(List.of(), List.of());
        if (mutations.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("A batch must not exceed " + MAX_BATCH_SIZE + " mutations.");
        }
        Map<XrossDbKey, XrossDbRecord> working = new LinkedHashMap<>(records);
        List<XrossDbRecord> written = new ArrayList<>();
        List<XrossDbKey> deleted = new ArrayList<>();
        for (XrossDbMutation mutation : mutations) {
            if (mutation == null) throw new IllegalArgumentException("A batch must not contain null mutations.");
            if (mutation.operation() == XrossDbMutation.Operation.WRITE) {
                written.add(write(working, mutation.key(), mutation.payload(), mutation.expectedRevision()));
            } else if (delete(working, mutation.key(), mutation.expectedRevision())) {
                deleted.add(mutation.key());
            }
        }
        commit(working);
        return new XrossDbBatchResult(written.stream().map(MapXrossDbRepository::copy).toList(), deleted);
    }

    @Override
    public synchronized void close() {
        if (storagePath == null) records.clear();
    }

    public synchronized List<XrossDbRecord> snapshot() {
        return records.values().stream()
                .sorted(Comparator.comparing(XrossDbRecord::key, KEY_ORDER))
                .map(MapXrossDbRepository::copy)
                .toList();
    }

    private XrossDbRecord write(
            Map<XrossDbKey, XrossDbRecord> target,
            XrossDbKey key,
            JsonNode payload,
            long expectedRevision
    ) {
        validateExpectedRevision(expectedRevision);
        XrossDbRecord current = target.get(key);
        long currentRevision = current == null ? 0L : current.revision();
        if (expectedRevision != XrossDbClient.ANY_REVISION && expectedRevision != currentRevision) {
            throw conflict(key, expectedRevision, currentRevision);
        }
        XrossDbRecord written = new XrossDbRecord(
                key, payload.deepCopy(), currentRevision + 1L, System.currentTimeMillis());
        target.put(key, written);
        return written;
    }

    private boolean delete(Map<XrossDbKey, XrossDbRecord> target, XrossDbKey key, long expectedRevision) {
        validateExpectedRevision(expectedRevision);
        XrossDbRecord current = target.get(key);
        long currentRevision = current == null ? 0L : current.revision();
        if (current == null && (expectedRevision == XrossDbClient.ANY_REVISION || expectedRevision == 0L)) {
            return false;
        }
        if (expectedRevision != XrossDbClient.ANY_REVISION && expectedRevision != currentRevision) {
            throw conflict(key, expectedRevision, currentRevision);
        }
        target.remove(key);
        return true;
    }

    private void commit(Map<XrossDbKey, XrossDbRecord> working) {
        persist(working);
        records.clear();
        records.putAll(working);
    }

    private Map<XrossDbKey, XrossDbRecord> load() {
        LinkedHashMap<XrossDbKey, XrossDbRecord> loaded = new LinkedHashMap<>();
        if (storagePath == null || !Files.exists(storagePath)) return loaded;
        try {
            JsonNode root = mapper.readTree(storagePath.toFile());
            if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION || !root.path("records").isArray()) {
                throw new IOException("Unsupported or invalid local XrossDB format.");
            }
            for (JsonNode item : root.path("records")) {
                XrossDbKey key = new XrossDbKey(
                        item.path("namespace").asText(),
                        item.path("collection").asText(),
                        item.path("key").asText());
                JsonNode payload = item.get("payload");
                long revision = item.path("revision").asLong(0L);
                long updatedAt = item.path("updatedAt").asLong(0L);
                if (payload == null || revision < 1L || updatedAt < 1L) {
                    throw new IOException("Invalid local XrossDB record: " + key);
                }
                if (loaded.putIfAbsent(key, new XrossDbRecord(
                        key, payload.deepCopy(), revision, updatedAt)) != null) {
                    throw new IOException("Duplicate local XrossDB record: " + key);
                }
            }
            return loaded;
        } catch (Exception exception) {
            throw new XrossDbException("Failed to load local XrossDB: " + storagePath, exception);
        }
    }

    private void persist(Map<XrossDbKey, XrossDbRecord> state) {
        if (storagePath == null) return;
        Path parent = storagePath.getParent();
        Path temporary = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            ObjectNode root = mapper.createObjectNode();
            root.put("version", FORMAT_VERSION);
            ArrayNode items = root.putArray("records");
            state.values().stream().sorted(Comparator.comparing(XrossDbRecord::key, KEY_ORDER)).forEach(record -> {
                ObjectNode item = items.addObject();
                item.put("namespace", record.key().namespace());
                item.put("collection", record.key().collection());
                item.put("key", record.key().key());
                item.set("payload", record.payload());
                item.put("revision", record.revision());
                item.put("updatedAt", record.updatedAt());
            });
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, storagePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new XrossDbException("Failed to save local XrossDB: " + storagePath, exception);
        }
    }

    private static XrossDbRecord copy(XrossDbRecord record) {
        return record == null ? null : new XrossDbRecord(
                record.key(), record.payload().deepCopy(), record.revision(), record.updatedAt());
    }

    private static void validateExpectedRevision(long expectedRevision) {
        if (expectedRevision < 0L && expectedRevision != XrossDbClient.ANY_REVISION) {
            throw new IllegalArgumentException("expectedRevision must be non-negative or ANY_REVISION.");
        }
    }

    private static XrossDbConflictException conflict(
            XrossDbKey key, long expectedRevision, long currentRevision) {
        return new XrossDbConflictException(
                "Revision conflict for " + key + ": expected " + expectedRevision + " but was " + currentRevision);
    }
}
