package com.ztraqto.openxross.api.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.config.XrossLocalConfiguration;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * XrossDB-backed JSON documents owned by one plugin.
 *
 * <p>Legacy JSON files are imported only when their XrossDB document does not
 * exist yet. Set {@code XROSS_LEGACY_DATA_ROOT} or the
 * {@code xross.legacyDataRoot} system property to the Xecute 3 Bot directory
 * before the first startup.</p>
 */
public final class PluginDataStore {

    private static final String NAMESPACE = "plugin-data";
    private static final int MAX_UPDATE_ATTEMPTS = 8;

    private final XrossDbClient client;
    private final String collection;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final Path legacyRoot;

    public static PluginDataStore forPlugin(XrossDbClient client, String pluginId, Logger logger) {
        return new PluginDataStore(client, pluginId, new ObjectMapper(), logger, configuredLegacyRoot());
    }

    PluginDataStore(XrossDbClient client, String pluginId, ObjectMapper mapper, Logger logger, Path legacyRoot) {
        this.client = Objects.requireNonNull(client, "client");
        this.collection = normalizeCollection(pluginId);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.legacyRoot = Objects.requireNonNull(legacyRoot, "legacyRoot").toAbsolutePath().normalize();
    }

    public boolean exists(String key) {
        return client.read(dbKey(key)).isPresent();
    }

    public <T> T read(String key, Class<T> type) {
        return readNode(key).map(node -> this.<T>convert(node, mapper.constructType(type))).orElse(null);
    }

    public <T> T read(String key, TypeReference<T> type) {
        return readNode(key).map(node -> this.<T>convert(node, mapper.getTypeFactory().constructType(type))).orElse(null);
    }

    public <T> T loadOrCreate(String key, Class<T> type) {
        return loadOrCreate(key, key, type, () -> instantiate(type));
    }

    public <T> T loadOrCreate(String key, String legacyRelativePath, Class<T> type) {
        return loadOrCreate(key, legacyRelativePath, type, () -> instantiate(type));
    }

    public <T> T loadOrCreate(String key, String legacyRelativePath, Class<T> type, Supplier<T> factory) {
        JavaType javaType = mapper.constructType(type);
        return loadOrCreateValue(key, legacyRelativePath, javaType, factory);
    }

    public <T> T loadOrCreate(String key, String legacyRelativePath, TypeReference<T> type, Supplier<T> factory) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return loadOrCreateValue(key, legacyRelativePath, javaType, factory);
    }

    private <T> T loadOrCreateValue(String key, String legacyRelativePath, JavaType javaType, Supplier<T> factory) {
        T value = readNode(key).map(node -> this.<T>convert(node, javaType)).orElse(null);
        if (value != null) {
            return value;
        }
        T imported = importLegacy(legacyRelativePath, javaType);
        if (imported != null) {
            writeIfAbsent(key, imported);
            T stored = readNode(key).map(node -> this.<T>convert(node, javaType)).orElse(null);
            return stored == null ? imported : stored;
        }
        T created = Objects.requireNonNull(factory.get(), "factory returned null");
        writeIfAbsent(key, created);
        T stored = readNode(key).map(node -> this.<T>convert(node, javaType)).orElse(null);
        return stored == null ? created : stored;
    }

    public <T> List<T> loadList(String key, String legacyRelativePath, TypeReference<List<T>> type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        List<T> value = readNode(key).map(node -> this.<List<T>>convert(node, javaType)).orElse(null);
        if (value != null) {
            return new ArrayList<>(value);
        }
        List<T> imported = importLegacy(legacyRelativePath, javaType);
        List<T> initial = imported == null ? new ArrayList<>() : new ArrayList<>(imported);
        writeIfAbsent(key, initial);
        List<T> stored = read(key, type);
        return stored == null ? initial : new ArrayList<>(stored);
    }

    public void write(String key, Object value) {
        client.write(dbKey(key), mapper.valueToTree(value));
    }

    public <T> T update(String key, String legacyRelativePath, Class<T> type, Supplier<T> factory, Consumer<T> updater) {
        return updateValue(key, legacyRelativePath, mapper.constructType(type), factory, updater);
    }

    public <T> List<T> updateList(
            String key,
            String legacyRelativePath,
            TypeReference<List<T>> type,
            Consumer<List<T>> updater
    ) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return updateValue(key, legacyRelativePath, javaType, ArrayList::new, updater);
    }

    public boolean delete(String key) {
        return client.delete(dbKey(key));
    }

    public List<String> keys(String prefix) {
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "" : normalizeKey(prefix);
        return client.scan(NAMESPACE, collection).stream()
                .map(record -> record.key().key())
                .filter(key -> key.startsWith(normalizedPrefix))
                .sorted()
                .toList();
    }

    private Optional<JsonNode> readNode(String key) {
        return client.read(dbKey(key)).map(XrossDbRecord::payload);
    }

    private <T> T updateValue(
            String key,
            String legacyRelativePath,
            JavaType type,
            Supplier<T> factory,
            Consumer<T> updater
    ) {
        XrossDbKey dbKey = dbKey(key);
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            Optional<XrossDbRecord> current = client.read(dbKey);
            T value;
            long revision;
            if (current.isPresent()) {
                value = convert(current.get().payload(), type);
                revision = current.get().revision();
            } else {
                value = importLegacy(legacyRelativePath, type);
                if (value == null) {
                    value = Objects.requireNonNull(factory.get(), "factory returned null");
                }
                revision = 0L;
            }
            updater.accept(value);
            try {
                client.write(dbKey, mapper.valueToTree(value), revision);
                return value;
            } catch (XrossDbConflictException ignored) {
                // Another process updated this document. Re-read and retry.
            }
        }
        throw new XrossDbConflictException("Could not update plugin document after retries: " + key);
    }

    private void writeIfAbsent(String key, Object value) {
        try {
            client.write(dbKey(key), mapper.valueToTree(value), 0L);
        } catch (XrossDbConflictException ignored) {
            // A concurrent plugin process completed the same initialization.
        }
    }

    private <T> T importLegacy(String relativePath, JavaType type) {
        Path path = resolveLegacyPath(relativePath);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            T value = mapper.readValue(path.toFile(), type);
            logger.info("Imported Xecute 3 data '{}' into XrossDB collection '{}'.", relativePath, collection);
            return value;
        } catch (IOException exception) {
            logger.warn("Failed to import legacy plugin data '{}'.", path, exception);
            return null;
        }
    }

    private Path resolveLegacyPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String portable = relativePath.replace('\\', '/');
        while (portable.startsWith("/")) {
            portable = portable.substring(1);
        }
        Path resolved = legacyRoot.resolve(portable).normalize();
        return resolved.startsWith(legacyRoot) ? resolved : null;
    }

    private <T> T convert(JsonNode node, JavaType type) {
        return mapper.convertValue(node, type);
    }

    private XrossDbKey dbKey(String key) {
        return new XrossDbKey(NAMESPACE, collection, normalizeKey(key));
    }

    private static String normalizeCollection(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is required.");
        }
        String normalized = pluginId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]", "-");
        return XrossDbKey.validateName(normalized, "pluginId");
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required.");
        }
        String normalized = key.replace('\\', '/').replaceAll("/+", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Data type requires a no-argument constructor: " + type.getName(), exception);
        }
    }

    private static Path configuredLegacyRoot() {
        String configured = System.getProperty("xross.legacyDataRoot");
        if (configured == null || configured.isBlank()) {
            configured = XrossLocalConfiguration.string("storage.legacyDataRoot", "XROSS_LEGACY_DATA_ROOT");
        }
        return configured == null || configured.isBlank() ? Path.of(".") : Path.of(configured);
    }
}
