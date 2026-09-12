package com.ztraqto.openxross.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.core.security.SecureJson;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide, read-only configuration loaded from a portable JSON file.
 *
 * <p>Values come from JSON first and may fall back to the named environment
 * variable. Command-line options are applied later and remain the
 * highest-priority override. Values are never written or logged here.</p>
 */
public final class XrossLocalConfiguration {

    public static final String DEFAULT_FILE_NAME = "xross.config.json";

    private static final Object LOCK = new Object();
    private static final ObjectMapper MAPPER = SecureJson.newMapper();

    private static volatile JsonNode root = MissingNode.getInstance();
    private static volatile Path sourceFile;

    private XrossLocalConfiguration() {
    }

    public static void loadDefault() throws IOException {
        load(Path.of(DEFAULT_FILE_NAME), false);
    }

    public static void load(Path path) throws IOException {
        load(path, true);
    }

    private static void load(Path path, boolean required) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        synchronized (LOCK) {
            if (!Files.exists(normalized)) {
                if (required) {
                    throw new IOException("Xross configuration file was not found: " + normalized);
                }
                root = MissingNode.getInstance();
                sourceFile = null;
                return;
            }
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("Xross configuration path is not a regular file: " + normalized);
            }
            JsonNode parsed = MAPPER.readTree(normalized.toFile());
            if (parsed == null || !parsed.isObject()) {
                throw new IOException("Xross configuration root must be a JSON object: " + normalized);
            }
            root = parsed;
            sourceFile = normalized;
        }
    }

    public static Path sourceFile() {
        return sourceFile;
    }


    /**
     * Reads a secret using file -> environment -> JSON precedence. This keeps
     * production secrets out of process arguments and allows container secret
     * mounts without requiring a plaintext configuration file.
     */
    public static String secret(String jsonPath, String environmentName, String fileEnvironmentName) {
        if (fileEnvironmentName != null && !fileEnvironmentName.isBlank()) {
            String fileName = System.getenv(fileEnvironmentName);
            if (fileName != null && !fileName.isBlank()) {
                try {
                    Path path = Path.of(fileName.trim()).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(path)) {
                        throw new IllegalArgumentException(fileEnvironmentName + " does not point to a regular file.");
                    }
                    long size = Files.size(path);
                    if (size <= 0L || size > 64 * 1024L) {
                        throw new IllegalArgumentException(fileEnvironmentName + " secret file has an invalid size.");
                    }
                    String value = Files.readString(path).trim();
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException(fileEnvironmentName + " secret file is empty.");
                    }
                    return value;
                } catch (IOException exception) {
                    throw new IllegalArgumentException("Could not read secret file from " + fileEnvironmentName + ".", exception);
                }
            }
        }

        if (environmentName != null && !environmentName.isBlank()) {
            String environmentValue = System.getenv(environmentName);
            if (environmentValue != null && !environmentValue.isBlank()) {
                return environmentValue.trim();
            }
        }
        return string(jsonPath, null);
    }

    public static String string(String jsonPath, String legacyEnvironmentName) {
        JsonNode node = node(jsonPath);
        if (!node.isMissingNode() && !node.isNull()) {
            if (!node.isValueNode()) {
                throw new IllegalArgumentException(jsonPath + " must be a JSON string, number, or boolean.");
            }
            String value = node.asText().trim();
            return value.isEmpty() ? null : value;
        }
        if (legacyEnvironmentName == null || legacyEnvironmentName.isBlank()) return null;
        String environmentValue = System.getenv(legacyEnvironmentName);
        if (environmentValue == null || environmentValue.isBlank()) return null;
        return environmentValue.trim();
    }

    public static String string(String jsonPath, String legacyEnvironmentName, String fallback) {
        String value = string(jsonPath, legacyEnvironmentName);
        return value == null ? fallback : value;
    }

    public static boolean bool(String jsonPath, String legacyEnvironmentName, boolean fallback) {
        String value = string(jsonPath, legacyEnvironmentName);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(jsonPath + " must be true or false.");
    }

    public static long longValue(String jsonPath, String legacyEnvironmentName, long fallback) {
        String value = string(jsonPath, legacyEnvironmentName);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(jsonPath + " must be an integer.", exception);
        }
    }

    public static List<String> strings(String jsonPath, String legacyEnvironmentName) {
        JsonNode node = node(jsonPath);
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isValueNode()) {
                    throw new IllegalArgumentException(jsonPath + " entries must be strings or numbers.");
                }
                String value = item.asText().trim();
                if (!value.isEmpty()) values.add(value);
            }
            return List.copyOf(values);
        }
        String value = string(jsonPath, legacyEnvironmentName);
        if (value == null) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            String normalized = part.trim();
            if (!normalized.isEmpty()) values.add(normalized);
        }
        return List.copyOf(values);
    }

    private static JsonNode node(String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (!current.isObject()) return MissingNode.getInstance();
            current = current.path(segment);
        }
        return current;
    }
}
