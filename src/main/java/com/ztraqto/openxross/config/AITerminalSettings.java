package com.ztraqto.openxross.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Configuration for the XrossEngine AI terminal service. */
public final class AITerminalSettings {

    public static final Path DEFAULT_PATH = Path.of("xross.aits.config.json");
    public static final Path LEGACY_CONFIG_PATH = Path.of("xecute.aits.config.json");
    public static final Path LEGACY_PATH = Path.of("xecute.aits.settings.json");
    public static final int CURRENT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final int maxConcurrentRequests;
    private final Map<String, Profile> profiles;

    public AITerminalSettings(int maxConcurrentRequests, Map<String, Profile> profiles) {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("AI global maxConcurrentRequests must be at least 1.");
        }
        Objects.requireNonNull(profiles, "profiles");
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public static AITerminalSettings empty() {
        return new AITerminalSettings(4, Map.of());
    }

    public static AITerminalSettings load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            throw new java.nio.file.NoSuchFileException(path.toString());
        }
        JsonNode root = MAPPER.readTree(path.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("AI settings root must be a JSON object: " + path.toAbsolutePath());
        }

        int version = integer(root, "version", CURRENT_VERSION);
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported AI settings version " + version + ". Expected " + CURRENT_VERSION + ".");
        }

        JsonNode global = childObject(root, "global");
        int maxConcurrentRequests = integer(global, "maxConcurrentRequests", 4);
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 256) {
            throw new IOException("global.maxConcurrentRequests must be between 1 and 256.");
        }

        JsonNode profilesNode = root.path("profiles");
        if (!profilesNode.isObject()) {
            throw new IOException("profiles must be a JSON object.");
        }

        LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();
        var fields = profilesNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            profiles.put(entry.getKey(), parseProfile(entry.getKey(), entry.getValue()));
        }
        return new AITerminalSettings(maxConcurrentRequests, profiles);
    }

    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public Map<String, Profile> profiles() {
        return profiles;
    }

    private static Profile parseProfile(String name, JsonNode node) throws IOException {
        if (name.isBlank()) {
            throw new IOException("AI profile names must not be blank.");
        }
        if (!node.isObject()) {
            throw new IOException("AI profile must be a JSON object: " + name);
        }

        String protocol = text(node, "protocol", "openai-chat-completions");
        if (!"openai-chat-completions".equals(protocol)) {
            throw new IOException("Unsupported AI protocol for profile " + name + ": " + protocol);
        }

        String baseUrl = text(node, "baseUrl", null);
        validateBaseUrl(name, baseUrl);
        String model = text(node, "model", null);
        if (model == null) {
            throw new IOException("AI profile " + name + " requires model.");
        }

        String apiKey = text(node, "apiKey", null);
        URI baseUri = URI.create(baseUrl);
        if (apiKey != null && "http".equalsIgnoreCase(baseUri.getScheme()) && !isLoopback(baseUri.getHost())) {
            throw new IOException("AI profile " + name + " must use HTTPS when apiKey is configured.");
        }
        int timeoutSeconds = integer(node, "requestTimeoutSeconds", 300);
        if (timeoutSeconds < 1 || timeoutSeconds > 86_400) {
            throw new IOException("AI profile " + name + ".requestTimeoutSeconds must be between 1 and 86400.");
        }

        JsonNode rateLimitNode = childObject(node, "rateLimit");
        int maxRequests = integer(rateLimitNode, "maxRequests", 0);
        int windowSeconds = integer(rateLimitNode, "windowSeconds", 60);
        int maxConcurrentRequests = integer(rateLimitNode, "maxConcurrentRequests", 0);
        int maxQueuedRequests = integer(rateLimitNode, "maxQueuedRequests", 64);
        if (maxRequests < 0) {
            throw new IOException("AI profile " + name + ".rateLimit.maxRequests must be 0 or greater.");
        }
        if (windowSeconds < 1 || windowSeconds > 86_400) {
            throw new IOException("AI profile " + name + ".rateLimit.windowSeconds must be between 1 and 86400.");
        }
        if (maxConcurrentRequests < 0 || maxConcurrentRequests > 256) {
            throw new IOException("AI profile " + name + ".rateLimit.maxConcurrentRequests must be between 0 and 256.");
        }
        if (maxQueuedRequests < 1 || maxQueuedRequests > 100_000) {
            throw new IOException("AI profile " + name + ".rateLimit.maxQueuedRequests must be between 1 and 100000.");
        }

        return new Profile(
                protocol,
                baseUrl,
                model,
                apiKey,
                timeoutSeconds,
                new RateLimit(maxRequests, windowSeconds, maxConcurrentRequests, maxQueuedRequests)
        );
    }

    private static void validateBaseUrl(String profile, String value) throws IOException {
        if (value == null) {
            throw new IOException("AI profile " + profile + " requires baseUrl.");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("AI profile " + profile + " has an invalid baseUrl.", exception);
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static JsonNode childObject(JsonNode parent, String field) throws IOException {
        if (parent == null || parent.isMissingNode() || parent.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        if (!value.isObject()) {
            throw new IOException(field + " must be a JSON object.");
        }
        return value;
    }

    private static String text(JsonNode parent, String field, String fallback) throws IOException {
        if (parent == null || parent.isMissingNode()) {
            return fallback;
        }
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IOException(field + " must be a JSON string.");
        }
        String result = value.asText().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static int integer(JsonNode parent, String field, int fallback) throws IOException {
        if (parent == null || parent.isMissingNode()) {
            return fallback;
        }
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new IOException(field + " must be an integer.");
        }
        return value.asInt();
    }

    public record RateLimit(int maxRequests, int windowSeconds, int maxConcurrentRequests, int maxQueuedRequests) {
    }

    public record Profile(
            String protocol,
            String baseUrl,
            String model,
            String apiKey,
            int requestTimeoutSeconds,
            RateLimit rateLimit
    ) {
        @Override
        public String toString() {
            return "Profile[protocol=" + protocol
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", apiKeyConfigured=" + (apiKey != null)
                    + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                    + ", rateLimit=" + rateLimit + "]";
        }
    }
}
