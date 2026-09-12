package com.ztraqto.openxross.core.editor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.settings.SettingChoice;
import com.ztraqto.openxross.api.settings.SettingScope;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class EditorSessionCodec {

    public static final String EDITOR_PREFIX = "XE4E.1";
    public static final String APPLY_PREFIX = "XE4.1";

    private static final int MAX_COMPRESSED_BYTES = 64 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 512 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] signingSecret;
    private final Duration sessionTtl;
    private final Clock clock;

    public EditorSessionCodec(String signingSecret, Duration sessionTtl) {
        this(signingSecret, sessionTtl, Clock.systemUTC());
    }

    EditorSessionCodec(String signingSecret, Duration sessionTtl, Clock clock) {
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalArgumentException("Editor signing secret must contain at least 32 characters.");
        }
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.sessionTtl = sessionTtl;
        this.clock = clock;
    }

    public EditorLaunch createLaunch(
            long guildId,
            String guildName,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values
    ) {
        return createLaunch(guildId, 0L, guildName, Map.of(), definitions, values);
    }

    public EditorLaunch createLaunch(
            long guildId,
            String guildName,
            Map<String, String> categories,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values
    ) {
        return createLaunch(guildId, 0L, guildName, categories, definitions, values);
    }

    public EditorLaunch createLaunch(
            long guildId,
            long userId,
            String guildName,
            Map<String, String> categories,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values
    ) {
        return createLaunch(SettingScope.GUILD, guildId, userId, guildName, categories, definitions, values);
    }

    public EditorLaunch createLaunch(
            SettingScope scope,
            long guildId,
            long userId,
            String guildName,
            Map<String, String> categories,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values
    ) {
        return createLaunch(scope, guildId, userId, guildName, categories, definitions, values, "ja", List.of(), List.of());
    }

    public EditorLaunch createLaunch(
            SettingScope scope,
            long guildId,
            long userId,
            String guildName,
            Map<String, String> categories,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values,
            String language,
            List<DiscordEntity> channels,
            List<DiscordEntity> roles
    ) {
        return createLaunch(scope, guildId, userId, guildName, categories, definitions, values,
                language, channels, roles, Map.of());
    }

    public EditorLaunch createLaunch(
            SettingScope scope,
            long guildId,
            long userId,
            String guildName,
            Map<String, String> categories,
            List<GuildSettingDefinition> definitions,
            Map<String, JsonNode> values,
            String language,
            List<DiscordEntity> channels,
            List<DiscordEntity> roles,
            Map<String, JsonNode> extensions
    ) {
        Instant now = Instant.now(clock);
        long expiresAt = now.plus(sessionTtl).getEpochSecond();
        long applyExpiresAt = expiresAt;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Authorization authorization = new Authorization(guildId, userId, scope, expiresAt, applyExpiresAt, nonce);
        String authorizationPart = encode(authorization);
        String signaturePart = sign(authorizationPart);
        List<EditorSetting> editorSettings = definitions.stream().map(definition -> EditorSetting.from(definition, language)).toList();
        String payloadPart = encode(new LaunchPayload(guildName, language, categories, editorSettings, values, channels, roles, extensions));
        String token = String.join(".", EDITOR_PREFIX, authorizationPart, signaturePart, payloadPart);
        return new EditorLaunch(token, authorization);
    }

    public DecodedApply decodeApply(String code, long expectedGuildId) {
        return decodeApply(code, expectedGuildId, 0L);
    }

    public DecodedApply decodeApply(String code, long expectedGuildId, long expectedUserId) {
        return decodeApply(code, expectedGuildId, expectedUserId, SettingScope.GUILD);
    }

    public DecodedApply decodeApply(String code, long expectedGuildId, long expectedUserId, SettingScope expectedScope) {
        String normalized = normalizeCode(code);
        String[] parts = normalized.split("\\.", 5);
        if (parts.length != 5 || !"XE4".equals(parts[0]) || !"1".equals(parts[1])) {
            throw new IllegalArgumentException("Unsupported Xross Editor setting code.");
        }
        Authorization authorization = verifyAuthorization(parts[2], parts[3], expectedGuildId, expectedUserId, true);
        if (authorization.scope() != expectedScope) {
            throw new IllegalArgumentException("Setting code has a different settings scope.");
        }
        ApplyPayload payload = decode(parts[4], ApplyPayload.class);
        if (payload.values() == null) {
            throw new IllegalArgumentException("Setting code does not contain values.");
        }
        return new DecodedApply(authorization, Map.copyOf(payload.values()));
    }

    public String createApplyCode(String editorToken, Map<String, JsonNode> values) {
        String[] parts = normalizeCode(editorToken).split("\\.", 5);
        if (parts.length != 5 || !"XE4E".equals(parts[0]) || !"1".equals(parts[1])) {
            throw new IllegalArgumentException("Unsupported Xross Editor launch token.");
        }
        Authorization authorization = decode(parts[2], Authorization.class);
        verifyAuthorization(parts[2], parts[3], authorization.guildId(), authorization.userId(), false);
        return String.join(".", APPLY_PREFIX, parts[2], parts[3], encode(new ApplyPayload(values)));
    }

    public LaunchPayload decodeLaunchPayload(String editorToken) {
        String[] parts = normalizeCode(editorToken).split("\\.", 5);
        if (parts.length != 5 || !"XE4E".equals(parts[0]) || !"1".equals(parts[1])) {
            throw new IllegalArgumentException("Unsupported Xross Editor launch token.");
        }
        Authorization authorization = decode(parts[2], Authorization.class);
        verifyAuthorization(parts[2], parts[3], authorization.guildId(), authorization.userId(), false);
        return decode(parts[4], LaunchPayload.class);
    }

    private Authorization verifyAuthorization(String encoded, String signature, long expectedGuildId, long expectedUserId, boolean applying) {
        byte[] expectedSignature = decodeBase64(sign(encoded));
        byte[] actualSignature = decodeBase64(signature);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("Setting code signature is invalid.");
        }

        Authorization authorization = decode(encoded, Authorization.class);
        if (authorization.guildId() != expectedGuildId) {
            throw new IllegalArgumentException("Setting code belongs to a different Discord server.");
        }
        if (authorization.userId() != expectedUserId) {
            throw new IllegalArgumentException("Setting code belongs to a different Discord user.");
        }
        long expiresAt = applying ? authorization.applyExpiresAt() : authorization.expiresAt();
        if (expiresAt < Instant.now(clock).getEpochSecond()) {
            throw new IllegalArgumentException("Setting code has expired. Run /editor again.");
        }
        if (authorization.nonce() == null || !authorization.nonce().matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Setting code session identifier is invalid.");
        }
        return authorization;
    }

    private String sign(String authorizationPart) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((APPLY_PREFIX + "." + authorizationPart).getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
        }
    }

    private String encode(Object value) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater)) {
                compressed.write(json);
            } finally {
                deflater.end();
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("Editor payload is too large.");
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not encode Editor payload.", exception);
        }
    }

    private <T> T decode(String encoded, Class<T> type) {
        byte[] compressed = decodeBase64(encoded);
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Editor payload is too large.");
        }

        Inflater inflater = new Inflater();
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
            byte[] json = input.readNBytes(MAX_DECOMPRESSED_BYTES + 1);
            if (json.length > MAX_DECOMPRESSED_BYTES) {
                throw new IllegalArgumentException("Expanded Editor payload is too large.");
            }
            return mapper.readValue(json, type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Editor payload is corrupted.", exception);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Editor payload contains invalid Base64URL data.", exception);
        }
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Setting code is required.");
        }
        String normalized = code.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).trim();
        }
        return normalized;
    }

    public record Authorization(
            @JsonProperty("g") long guildId,
            @JsonProperty("u") long userId,
            @JsonProperty("s") SettingScope scope,
            @JsonProperty("e") long expiresAt,
            @JsonProperty("a") long applyExpiresAt,
            @JsonProperty("n") String nonce
    ) {
        public Authorization {
            scope = scope == null ? SettingScope.GUILD : scope;
            applyExpiresAt = applyExpiresAt == 0L ? expiresAt : applyExpiresAt;
        }

        public Authorization(long guildId, long expiresAt, String nonce) {
            this(guildId, 0L, SettingScope.GUILD, expiresAt, expiresAt, nonce);
        }
    }

    public record EditorLaunch(String token, Authorization authorization) {
    }

    public record DecodedApply(Authorization authorization, Map<String, JsonNode> values) {
    }

    public record LaunchPayload(
            @JsonProperty("n") String guildName,
            @JsonProperty("i") String language,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("c") Map<String, String> categories,
            @JsonProperty("d") List<EditorSetting> definitions,
            @JsonProperty("v") Map<String, JsonNode> values,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("ch") List<DiscordEntity> channels,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("r") List<DiscordEntity> roles,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("x") Map<String, JsonNode> extensions
    ) {
        public LaunchPayload {
            language = "en".equalsIgnoreCase(language) ? "en" : "ja";
            channels = channels == null ? List.of() : List.copyOf(channels);
            roles = roles == null ? List.of() : List.copyOf(roles);
            extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        }
    }

    public record ApplyPayload(@JsonProperty("v") Map<String, JsonNode> values) {
    }

    public record EditorSetting(
            @JsonProperty("k") String key,
            @JsonProperty("l") String label,
            @JsonProperty("h") String description,
            @JsonProperty("t") String type,
            @JsonProperty("s") String scope,
            @JsonProperty("n") Integer minimum,
            @JsonProperty("x") Integer maximum,
            @JsonProperty("c") List<EditorChoice> choices,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("a") List<String> allowedChannelTypes
    ) {
        public EditorSetting {
            choices = choices == null ? List.of() : List.copyOf(choices);
            allowedChannelTypes = allowedChannelTypes == null ? List.of() : List.copyOf(allowedChannelTypes);
        }

        @JsonIgnore
        public Map<String, String> labels() {
            return Map.of("ja", label);
        }

        @JsonIgnore
        public Map<String, String> descriptions() {
            return Map.of();
        }

        private static EditorSetting from(GuildSettingDefinition definition, String language) {
            return new EditorSetting(
                    definition.key(),
                    definition.label(language),
                    definition.description(language),
                    definition.type().name(),
                    definition.scope().name(),
                    definition.minimum(),
                    definition.maximum(),
                    definition.choices().stream().map(EditorChoice::from).toList(),
                    definition.channelTypes()
            );
        }

    }

    public record DiscordEntity(
            @JsonProperty("i") String id,
            @JsonProperty("n") String name,
            @JsonProperty("t") String type
    ) {
    }

    public record EditorChoice(@JsonProperty("v") String value, @JsonProperty("l") String label) {
        private static EditorChoice from(SettingChoice choice) {
            return new EditorChoice(choice.value(), choice.label());
        }
    }
}
