package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.config.XrossEditorConfiguration;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.core.editor.EditorSessionCodec;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.settings.SettingType;
import com.ztraqto.openxross.api.settings.SettingScope;
import com.ztraqto.openxross.api.certification.CertificationProgram;
import com.ztraqto.openxross.api.certification.CertificationProfile;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EditorSessionService implements IService {

    public static final int DISCORD_BUTTON_URL_LIMIT = 512;
    public static final int DISCORD_MESSAGE_URL_LIMIT = 1_800;
    private static final String AI_REPORT_ENABLED = "ai-report.enabled";
    private static final String AI_REPORT_PROVIDER = "ai-report.provider";
    private static final String AI_REPORT_SECONDARY_PROVIDER = "ai-report.secondary-provider";
    private static final String AI_REPORT_PRIMARY_MODEL = "ai-report.primary-model";
    private static final String AI_REPORT_SECONDARY_MODEL = "ai-report.secondary-model";
    private static final String AI_REPORT_API_KEY = "ai-report.api-key";
    private static final Logger logger = LoggerFactory.getLogger(EditorSessionService.class);

    private final XrossDbClient databaseClient;
    private final GuildSettingsService settingsService;
    private final UserSettingsService userSettingsService;
    private final XrossEditorConfiguration configuration;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EditorAttachmentCodec attachmentCodec = new EditorAttachmentCodec();
    private final CertificationService certifications;
    private final EditorSensitiveDataService sensitiveData;
    private EditorSessionCodec codec;
    private XrossEngine engine;

    public EditorSessionService(
            XrossDbClient databaseClient,
            GuildSettingsService settingsService,
            XrossEditorConfiguration configuration
    ) {
        this(databaseClient, settingsService, new UserSettingsService(databaseClient), configuration,
                new EditorSensitiveDataService(configuration));
    }

    public EditorSessionService(
            XrossDbClient databaseClient,
            GuildSettingsService settingsService,
            UserSettingsService userSettingsService,
            XrossEditorConfiguration configuration
    ) {
        this(databaseClient, settingsService, userSettingsService, configuration,
                new EditorSensitiveDataService(configuration));
    }

    public EditorSessionService(
            XrossDbClient databaseClient,
            GuildSettingsService settingsService,
            UserSettingsService userSettingsService,
            XrossEditorConfiguration configuration,
            EditorSensitiveDataService sensitiveData
    ) {
        this.databaseClient = databaseClient;
        this.settingsService = settingsService;
        this.userSettingsService = userSettingsService;
        this.configuration = configuration;
        this.sensitiveData = sensitiveData;
        this.certifications = new CertificationService(databaseClient);
    }

    @Override
    public void init(XrossEngine engine) {
        this.engine = engine;
        if (configuration.isEnabled()) {
            if (engine == null) sensitiveData.init(null);
            codec = new EditorSessionCodec(configuration.signingSecret(), configuration.sessionTtl());
        }
    }

    @Override
    public void shutdown() {
        codec = null;
        engine = null;
    }

    @Override
    public String getName() {
        return "EditorSessionService";
    }

    public boolean isEnabled() {
        return codec != null;
    }

    public boolean isAttachmentTransportEnabled() {
        return configuration.attachmentTransport();
    }

    public boolean hasAttachmentChannel() {
        return configuration.attachmentChannelId() != 0L;
    }

    public CompletableFuture<String> publishAttachment(EditorAttachmentCodec.EncryptedAttachment attachment) {
        requireEnabled();
        if (engine == null || engine.getShardManager() == null || !hasAttachmentChannel()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Editor attachment channel is unavailable."));
        }
        TextChannel channel = engine.getShardManager().getTextChannelById(configuration.attachmentChannelId());
        if (channel == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Editor attachment channel was not found."));
        }
        return channel.sendFiles(FileUpload.fromData(attachment.data(), EditorAttachmentCodec.FILE_NAME)).submit()
                .thenApply(message -> {
                    if (message.getAttachments().isEmpty()) throw new IllegalStateException("Editor attachment upload failed.");
                    // A deleted Discord message can invalidate its attachment URL. Keep the encrypted
                    // session image alive for exactly the same lifetime as its signed Editor session.
                    String attachmentUrl = message.getAttachments().get(0).getUrl();
                    long delayMillis = Math.max(1L, configuration.sessionTtl().toMillis());
                    CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() -> message.delete().queue(
                            ignored -> logger.debug("Deleted expired Editor CDN attachment message {}.", message.getId()),
                            failure -> logger.debug("Editor CDN attachment {} was already unavailable.", message.getId())
                    ));
                    return attachmentUrl;
                });
    }

    public EditorAttachmentCodec.EncryptedAttachment createEditorAttachment(long guildId, String guildName) {
        return createEditorAttachment(guildId, 0L, guildName);
    }

    public EditorAttachmentCodec.EncryptedAttachment createEditorAttachment(long guildId, long userId, String guildName) {
        return createGuildEditorAttachment(guildId, userId, guildName);
    }

    public EditorAttachmentCodec.EncryptedAttachment createGuildEditorAttachment(long guildId, long userId, String guildName) {
        requireEnabled();
        return attachmentCodec.encrypt(createLaunch(SettingScope.GUILD, guildId, userId, guildName).token());
    }

    public EditorAttachmentCodec.EncryptedAttachment createGuildEditorAttachment(Guild guild, long userId) {
        requireEnabled();
        return attachmentCodec.encrypt(createLaunch(SettingScope.GUILD, guild.getIdLong(), userId, guild.getName(), guild).token());
    }

    public EditorAttachmentCodec.EncryptedAttachment createUserEditorAttachment(long userId, String userName) {
        requireEnabled();
        return attachmentCodec.encrypt(createLaunch(SettingScope.USER, 0L, userId, userName).token());
    }

    public EditorAttachmentCodec.EncryptedAttachment createAdminEditorAttachment(long userId) {
        requireEnabled();
        requireBotAdministrator(userId);
        return attachmentCodec.encrypt(createLaunch(SettingScope.ADMIN, 0L, userId, "Xross 管理者").token());
    }

    public URI createAttachmentEditorUri(String attachmentUrl, String key) {
        requireEnabled();
        if (attachmentUrl == null || attachmentUrl.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Editor attachment URL and key are required.");
        }
        String fragment = EditorAttachmentCodec.LINK_PREFIX
                + "?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&u=" + URLEncoder.encode(attachmentUrl, StandardCharsets.UTF_8);
        return URI.create(configuration.editorUri() + "#" + fragment);
    }

    private EditorSessionCodec.EditorLaunch createLaunch(SettingScope scope, long guildId, long userId, String guildName) {
        return createLaunch(scope, guildId, userId, guildName, null);
    }

    private synchronized EditorSessionCodec.EditorLaunch createLaunch(
            SettingScope scope,
            long guildId,
            long userId,
            String guildName,
            Guild guild
    ) {
        List<GuildSettingDefinition> definitions = scope == SettingScope.USER
                ? userSettingsService.getDefinitions()
                : scope == SettingScope.ADMIN ? List.of() : settingsService.getDefinitions();
        LinkedHashMap<String, JsonNode> values = new LinkedHashMap<>(scope == SettingScope.USER
                ? userSettingsService.getValues(userId)
                : scope == SettingScope.ADMIN ? Map.of() : settingsService.getValues(guildId));
        definitions.stream().filter(definition -> definition.type() == SettingType.SECRET)
                .forEach(definition -> values.put(definition.key(), mapper.getNodeFactory().textNode("")));
        if (scope == SettingScope.ADMIN) {
            BotStatusService botStatus = engine == null || engine.getServiceManager() == null
                    ? null : engine.getServiceManager().getService(BotStatusService.class);
            values.put("xross-admin.status-template", mapper.getNodeFactory().textNode(
                    botStatus == null ? BotStatusService.DEFAULT_TEMPLATE : botStatus.getTemplate()));
            var programs = mapper.createArrayNode();
            certifications.getPrograms().stream().limit(200).forEach(program -> {
                ObjectNode item = programs.addObject();
                item.put("id", program.id());
                item.put("name", program.name());
            });
            var profiles = mapper.createArrayNode();
            certifications.getProfiles().stream().limit(500).forEach(profile -> {
                ObjectNode item = profiles.addObject();
                item.put("programId", profile.programId());
                item.put("userId", profile.userId());
                item.put("displayName", profile.displayName());
            });
            values.put("xross-admin.candidates.programs", programs);
            values.put("xross-admin.candidates.profiles", profiles);
        }
        String language = scope == SettingScope.GUILD
                ? editorLanguage(settingsService.getString(guildId, GuildSettingsService.LANGUAGE_KEY))
                : "ja";
        List<EditorSessionCodec.DiscordEntity> channels = guild == null ? List.of() : guild.getChannels().stream()
                .map(channel -> new EditorSessionCodec.DiscordEntity(channel.getId(), channel.getName(), channel.getType().name()))
                .toList();
        List<EditorSessionCodec.DiscordEntity> roles = guild == null ? List.of() : guild.getRoles().stream()
                .map(role -> new EditorSessionCodec.DiscordEntity(role.getId(), role.getName(), "en".equals(language) ? "Role" : "ロール"))
                .toList();
        LinkedHashMap<String, JsonNode> extensions = new LinkedHashMap<>(scope == SettingScope.GUILD
                ? getEditorExtensions(guildId, userId, guild, definitions) : Map.of());
        extensions.put("xross-product", getProductIdentity());
        EditorSessionCodec.EditorLaunch launch = codec.createLaunch(
                scope,
                guildId,
                userId,
                guildName,
                getCategoryNames(definitions),
                definitions,
                values,
                language,
                channels,
                roles,
                extensions
        );

        ObjectNode session = mapper.createObjectNode();
        session.put("guildId", guildId);
        session.put("userId", userId);
        session.put("scope", scope.name());
        session.put("expiresAt", launch.authorization().expiresAt());
        session.put("applyExpiresAt", launch.authorization().applyExpiresAt());
        databaseClient.write(sessionKey(launch.authorization().nonce()), session, 0L);

        XrossDbKey activeKey = activeSessionKey(scope, guildId, userId);
        XrossDbRecord previousActive = databaseClient.read(activeKey).orElse(null);
        ObjectNode active = mapper.createObjectNode();
        active.put("nonce", launch.authorization().nonce());
        active.put("expiresAt", launch.authorization().expiresAt());
        databaseClient.write(activeKey, active, XrossDbClient.ANY_REVISION);
        if (previousActive != null) {
            String previousNonce = previousActive.payload().path("nonce").asText("");
            if (!previousNonce.isBlank() && !previousNonce.equals(launch.authorization().nonce())) {
                databaseClient.read(sessionKey(previousNonce))
                        .ifPresent(previous -> databaseClient.delete(previous.key(), previous.revision()));
            }
        }
        return launch;
    }

    private static String editorLanguage(String language) {
        return "en".equalsIgnoreCase(language) ? "en" : "ja";
    }

    private ObjectNode getProductIdentity() {
        ObjectNode product = mapper.createObjectNode();
        product.put("name", engine == null ? "OpenXross Bot" : engine.getProductName());
        if (engine != null && engine.getShardManager() != null) {
            engine.getShardManager().getShards().stream().findFirst().ifPresent(jda -> {
                product.put("botId", jda.getSelfUser().getId());
                product.put("iconUrl", jda.getSelfUser().getEffectiveAvatarUrl());
            });
        }
        var plugins = product.putArray("plugins");
        if (engine != null && engine.getPluginManager() != null) {
            engine.getPluginManager().getLoadedPlugins().stream()
                    .filter(com.ztraqto.openxross.core.PluginManager.LoadedPlugin::enabled)
                    .map(plugin -> plugin.meta().getId())
                    .sorted()
                    .forEach(plugins::add);
        }
        return product;
    }

    private Map<String, JsonNode> getEditorExtensions(
            long guildId,
            long viewerId,
            Guild guild,
            List<GuildSettingDefinition> definitions
    ) {
        String prefix = guildId + "/";
        LinkedHashMap<String, JsonNode> extensions = new LinkedHashMap<>();
        for (XrossDbRecord record : databaseClient.scan("editor", "extensions")) {
            String key = record.key().key();
            if (!key.startsWith(prefix) || extensions.size() >= 20) continue;
            String owner = key.substring(prefix.length());
            if (!owner.matches("[a-z0-9][a-z0-9._-]{0,63}") || !record.payload().isObject()) continue;
            JsonNode payload = record.payload().deepCopy();
            if ("erify".equals(owner)) continue;
            extensions.put(owner, payload);
        }
        if (definitions.stream().anyMatch(definition -> AI_REPORT_ENABLED.equals(definition.key()))) {
            ObjectNode access = mapper.createObjectNode();
            access.put("canEnable", certifications.hasCertification(viewerId, certificationProgramId()));
            extensions.put("ai-report-access", access);
        }
        return Map.copyOf(extensions);
    }

    /** Reveals protected details only to a certified manager of the selected guild. */
    private JsonNode authorizeErifyGraph(JsonNode payload, long guildId, long viewerId, Guild guild) {
        if (!(payload instanceof ObjectNode graph)
                || !"erify-relationship-graph".equals(graph.path("type").asText())) {
            return payload;
        }
        String programId = graph.path("detailProgramId").asText(certificationProgramId());
        var member = guild == null ? null : guild.getMemberById(viewerId);
        boolean guildAdministrator = member != null && member.hasPermission(Permission.MANAGE_SERVER);
        boolean certified = viewerId > 0L && !programId.isBlank()
                && certifications.hasCertification(viewerId, programId);
        boolean permitted = guildAdministrator && certified;
        graph.put("sensitiveDetailsVisible", permitted);
        graph.remove("networkDetailsVisible");
        graph.remove("detailProgramId");

        Set<String> restrictedFields = Set.of(
                "asn", "country", "timezone", "ipTimezone", "region", "regionCode", "city",
                "postalCode", "latitude", "longitude", "asOrganization", "colo", "continent",
                "asnRisk", "tor", "automation", "timezoneMismatch"
        );
        JsonNode nodes = graph.path("nodes");
        if (nodes.isArray()) {
            nodes.forEach(node -> {
                if (node instanceof ObjectNode object && "user".equals(object.path("kind").asText())) {
                    if (permitted) {
                        revealProtectedField(object, guildId, "ip-address", "protectedIpAddress", "ipAddress");
                        revealProtectedField(object, guildId, "email-address", "protectedEmailAddress", "emailAddress");
                    } else {
                        restrictedFields.forEach(object::remove);
                    }
                    object.remove("protectedIpAddress");
                    object.remove("protectedEmailAddress");
                }
            });
        }
        return graph;
    }

    private void revealProtectedField(ObjectNode node, long guildId, String field, String source, String target) {
        String protectedValue = node.path(source).asText("");
        if (protectedValue.isBlank()) return;
        try {
            String value = sensitiveData.reveal(guildId, field, protectedValue);
            if (!value.isBlank()) node.put(target, value);
        } catch (RuntimeException exception) {
            logger.warn("Erify protected field {} could not be opened for guild {}.", field, guildId);
        }
    }

    public Map<String, JsonNode> apply(long guildId, String code) {
        return apply(guildId, 0L, code);
    }

    public Map<String, JsonNode> apply(long guildId, long userId, String code) {
        requireEnabled();
        SettingScope scope = getApplyScope(code, guildId, userId);
        long expectedGuildId = scope == SettingScope.GUILD ? guildId : 0L;
        EditorSessionCodec.DecodedApply decoded = codec.decodeApply(code, expectedGuildId, userId, scope);
        validateActiveSession(decoded, expectedGuildId, userId, scope);
        if (scope == SettingScope.ADMIN) {
            requireBotAdministrator(userId);
            return applyAdministratorAction(userId, decoded.values());
        }
        Set<String> guildKeys = settingsService.getDefinitions().stream().map(GuildSettingDefinition::key).collect(Collectors.toSet());
        Set<String> userKeys = userSettingsService.getDefinitions().stream().map(GuildSettingDefinition::key).collect(Collectors.toSet());
        LinkedHashMap<String, JsonNode> guildChanges = new LinkedHashMap<>();
        LinkedHashMap<String, JsonNode> userChanges = new LinkedHashMap<>();
        decoded.values().forEach((key, value) -> {
            if (guildKeys.contains(key)) guildChanges.put(key, value);
            else if (userKeys.contains(key)) userChanges.put(key, value);
            else throw new IllegalArgumentException("Unknown setting: " + key);
        });
        if (scope == SettingScope.GUILD && !userChanges.isEmpty()) throw new IllegalArgumentException("User setting found in a server setting code.");
        if (scope == SettingScope.USER && !guildChanges.isEmpty()) throw new IllegalArgumentException("Server setting found in a user setting code.");
        boolean hasAiReport = guildKeys.contains(AI_REPORT_ENABLED);
        if (scope == SettingScope.GUILD && hasAiReport) {
            LinkedHashMap<String, JsonNode> accessChanges = new LinkedHashMap<>(guildChanges);
            accessChanges.putIfAbsent(AI_REPORT_ENABLED, com.fasterxml.jackson.databind.node.BooleanNode.valueOf(
                    settingsService.getBoolean(guildId, AI_REPORT_ENABLED)));
            accessChanges.putIfAbsent(AI_REPORT_PROVIDER, mapper.getNodeFactory().textNode(
                    settingsService.getString(guildId, AI_REPORT_PROVIDER)));
            accessChanges.putIfAbsent(AI_REPORT_SECONDARY_PROVIDER, mapper.getNodeFactory().textNode(
                    settingsService.getString(guildId, AI_REPORT_SECONDARY_PROVIDER)));
            validateAiReportAccess(accessChanges, certifications.hasCertification(userId, certificationProgramId()));
        }
        if (scope == SettingScope.GUILD) protectSecretChanges(guildId, guildChanges);
        if (scope == SettingScope.GUILD && hasAiReport) validateAiReportConfiguration(guildId, guildChanges);
        Map<String, JsonNode> validatedGuild = settingsService.validateChanges(guildChanges);
        Map<String, JsonNode> validatedUser = userSettingsService.validateChanges(userChanges);

        LinkedHashMap<String, JsonNode> changed = new LinkedHashMap<>();
        if (!validatedGuild.isEmpty()) changed.putAll(settingsService.apply(guildId, validatedGuild));
        if (!validatedUser.isEmpty()) {
            changed.putAll(userSettingsService.apply(userId, validatedUser));
        }
        settingsService.getDefinitions().stream().filter(definition -> definition.type() == SettingType.SECRET)
                .map(GuildSettingDefinition::key).forEach(key -> {
                    if (changed.containsKey(key)) changed.put(key, mapper.getNodeFactory().textNode("***"));
                });
        return Map.copyOf(changed);
    }

    private void protectSecretChanges(long guildId, Map<String, JsonNode> changes) {
        for (GuildSettingDefinition definition : settingsService.getDefinitions()) {
            if (definition.type() != SettingType.SECRET || !changes.containsKey(definition.key())) continue;
            String value = changes.get(definition.key()).asText("");
            if (value.isBlank()) {
                changes.remove(definition.key());
                continue;
            }
            if ("__XROSS_CLEAR_SECRET__".equals(value)) {
                changes.put(definition.key(), mapper.getNodeFactory().textNode(""));
                continue;
            }
            String field = definition.key().substring(definition.owner().length() + 1).replace('.', '-').replace('_', '-');
            changes.put(definition.key(), mapper.getNodeFactory().textNode(
                    sensitiveData.protect(guildId, definition.owner(), field, value)));
        }
    }

    void validateAiReportConfiguration(long guildId, Map<String, JsonNode> changes) {
        if (settingsService.getDefinitions().stream().noneMatch(definition -> AI_REPORT_ENABLED.equals(definition.key()))) return;
        boolean enabled = changes.containsKey(AI_REPORT_ENABLED) ? changes.get(AI_REPORT_ENABLED).asBoolean()
                : settingsService.getBoolean(guildId, AI_REPORT_ENABLED);
        if (!enabled) return;
        String managementId = changes.containsKey(GuildSettingsService.MANAGEMENT_LOG_CHANNEL_KEY)
                ? changes.get(GuildSettingsService.MANAGEMENT_LOG_CHANNEL_KEY).asText()
                : settingsService.getString(guildId, GuildSettingsService.MANAGEMENT_LOG_CHANNEL_KEY);
        Guild guild = engine == null || engine.getShardManager() == null ? null : engine.getShardManager().getGuildById(guildId);
        TextChannel channel = guild == null ? null : guild.getTextChannelById(managementId);
        if (channel == null || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
            throw new IllegalArgumentException("AI Report requires a usable Bot management log channel.");
        }
        String provider = changes.containsKey(AI_REPORT_PROVIDER) ? changes.get(AI_REPORT_PROVIDER).asText()
                : settingsService.getString(guildId, AI_REPORT_PROVIDER);
        String secondaryProvider = changes.containsKey(AI_REPORT_SECONDARY_PROVIDER)
                ? changes.get(AI_REPORT_SECONDARY_PROVIDER).asText()
                : settingsService.getString(guildId, AI_REPORT_SECONDARY_PROVIDER);
        String primary = changes.containsKey(AI_REPORT_PRIMARY_MODEL) ? changes.get(AI_REPORT_PRIMARY_MODEL).asText()
                : settingsService.getString(guildId, AI_REPORT_PRIMARY_MODEL);
        String secondary = changes.containsKey(AI_REPORT_SECONDARY_MODEL) ? changes.get(AI_REPORT_SECONDARY_MODEL).asText()
                : settingsService.getString(guildId, AI_REPORT_SECONDARY_MODEL);
        String key = changes.containsKey(AI_REPORT_API_KEY) ? changes.get(AI_REPORT_API_KEY).asText()
                : settingsService.getString(guildId, AI_REPORT_API_KEY);
        boolean primaryExternal = isExternalAiProvider(provider);
        boolean secondaryExternal = isExternalAiProvider(secondaryProvider);
        if ((primaryExternal && primary.isBlank()) || (secondaryExternal && secondary.isBlank())
                || ((primaryExternal || secondaryExternal) && key.isBlank())) {
            throw new IllegalArgumentException("External AI Report stages require their model id and an API key.");
        }
    }

    static void validateAiReportAccess(Map<String, JsonNode> changes, boolean partnerCertified) {
        if (!changes.getOrDefault(AI_REPORT_ENABLED, com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()
                || partnerCertified) return;
        String primary = changes.getOrDefault(AI_REPORT_PROVIDER,
                com.fasterxml.jackson.databind.node.TextNode.valueOf("internal-nano")).asText();
        String secondary = changes.getOrDefault(AI_REPORT_SECONDARY_PROVIDER,
                com.fasterxml.jackson.databind.node.TextNode.valueOf("internal-mini")).asText();
        if (isInternalAiProvider(primary) || isInternalAiProvider(secondary)) {
            throw new IllegalArgumentException("Internal Nano and Internal Mini can only be enabled by a certified manager. External providers remain available with a server-owned key.");
        }
    }

    private static boolean isInternalAiProvider(String provider) {
        return "internal-nano".equals(provider) || "internal-mini".equals(provider) || "internal".equals(provider);
    }

    private static boolean isExternalAiProvider(String provider) {
        return "openai".equals(provider) || "gemini".equals(provider);
    }

    /** Consumes a signed Editor action payload without changing guild settings. */
    public JsonNode consumeAction(long guildId, long userId, String code, String actionKey) {
        requireEnabled();
        if (actionKey == null || actionKey.isBlank()) throw new IllegalArgumentException("Action key is required.");
        EditorSessionCodec.DecodedApply decoded = codec.decodeApply(code, guildId, userId, SettingScope.GUILD);
        if (decoded.values().size() != 1 || !decoded.values().containsKey(actionKey)) {
            throw new IllegalArgumentException("Editor action code is not valid for " + actionKey + ".");
        }
        validateActiveSession(decoded, guildId, userId, SettingScope.GUILD);
        XrossDbKey key = sessionKey(decoded.authorization().nonce());
        XrossDbRecord session = databaseClient.read(key)
                .orElseThrow(() -> new IllegalArgumentException("Editor session was already used or does not exist."));
        long storedGuildId = session.payload().path("guildId").asLong(0L);
        long storedUserId = session.payload().path("userId").asLong(0L);
        long expiry = session.payload().path("applyExpiresAt").asLong(session.payload().path("expiresAt").asLong(0L));
        if (storedGuildId != guildId || storedUserId != userId || expiry != decoded.authorization().applyExpiresAt()) {
            throw new IllegalArgumentException("Editor session data does not match the action code.");
        }
        if (expiry < Instant.now().getEpochSecond()) {
            databaseClient.delete(key, session.revision());
            throw new IllegalArgumentException("Editor session has expired. Run /editor again.");
        }
        if (!databaseClient.delete(key, session.revision())) {
            throw new IllegalArgumentException("Editor session was already used or replaced.");
        }
        return decoded.values().get(actionKey).deepCopy();
    }

    public SettingScope getApplyScope(String code, long guildId, long userId) {
        try {
            codec.decodeApply(code, guildId, userId, SettingScope.GUILD);
            return SettingScope.GUILD;
        } catch (IllegalArgumentException guildFailure) {
            try {
                codec.decodeApply(code, 0L, userId, SettingScope.USER);
                return SettingScope.USER;
            } catch (IllegalArgumentException userFailure) {
                try {
                    codec.decodeApply(code, 0L, userId, SettingScope.ADMIN);
                    return SettingScope.ADMIN;
                } catch (IllegalArgumentException adminFailure) {
                    throw guildFailure;
                }
            }
        }
    }

    private void validateActiveSession(EditorSessionCodec.DecodedApply decoded, long guildId, long userId, SettingScope scope) {
        XrossDbRecord active = databaseClient.read(activeSessionKey(scope, guildId, userId))
                .orElseThrow(() -> new IllegalArgumentException("Editor session is no longer active."));
        if (!decoded.authorization().nonce().equals(active.payload().path("nonce").asText())) {
            throw new IllegalArgumentException("Editor session was replaced. Run the Editor again.");
        }
        XrossDbKey key = sessionKey(decoded.authorization().nonce());
        XrossDbRecord session = databaseClient.read(key)
                .orElseThrow(() -> new IllegalArgumentException("Editor session does not exist."));
        long expiry = session.payload().path("applyExpiresAt").asLong(session.payload().path("expiresAt").asLong(0L));
        if (session.payload().path("guildId").asLong(0L) != guildId
                || session.payload().path("userId").asLong(0L) != userId
                || !scope.name().equals(session.payload().path("scope").asText())
                || expiry != decoded.authorization().applyExpiresAt()) {
            throw new IllegalArgumentException("Editor session data does not match the setting code.");
        }
        if (expiry < Instant.now().getEpochSecond()) {
            databaseClient.delete(key, session.revision());
            throw new IllegalArgumentException("Editor session has expired. Run /editor again.");
        }
    }

    private Map<String, JsonNode> applyAdministratorAction(long administratorId, Map<String, JsonNode> values) {
        String action = value(values, "xross-admin.action");
        switch (action) {
            case "status" -> engine.getServiceManager().getService(BotStatusService.class)
                    .setTemplate(value(values, "xross-admin.status-template"));
            case "program" -> certifications.upsertProgram(new CertificationProgram(value(values, "xross-admin.program-id"),
                    value(values, "xross-admin.program-name"), optionalValue(values, "xross-admin.description"),
                    optionalValue(values, "xross-admin.badge"), ""));
            case "grant" -> certifications.grant(value(values, "xross-admin.program-id"), parseUserId(value(values, "xross-admin.user-id")),
                    value(values, "xross-admin.profile-name"), optionalValue(values, "xross-admin.detail"), administratorId);
            case "revoke" -> certifications.revoke(value(values, "xross-admin.program-id"), parseUserId(value(values, "xross-admin.user-id")));
            default -> throw new IllegalArgumentException("Unsupported Xross administrator action.");
        }
        return Map.of("xross-admin." + action, mapper.getNodeFactory().textNode("applied"));
    }

    private static String value(Map<String, JsonNode> values, String key) {
        JsonNode value = values.get(key);
        if (value == null || value.asText().isBlank()) throw new IllegalArgumentException("Missing administrator field: " + key);
        return value.asText().trim();
    }

    private static String optionalValue(Map<String, JsonNode> values, String key) {
        JsonNode value = values.get(key);
        return value == null ? "" : value.asText("").trim();
    }

    private static long parseUserId(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("User ID must be a Discord numeric ID."); }
    }

    private void requireBotAdministrator(long userId) {
        if (engine == null || !engine.getConfiguration().botAdministratorIds().contains(userId)) {
            throw new IllegalArgumentException("This action is restricted to Xross Bot administrators.");
        }
    }

    EditorSessionCodec getCodec() {
        requireEnabled();
        return codec;
    }

    private Map<String, String> getCategoryNames(List<GuildSettingDefinition> definitions) {
        LinkedHashMap<String, String> categories = new LinkedHashMap<>();
        Set<String> owners = definitions.stream()
                .map(GuildSettingDefinition::owner)
                .filter(owner -> !"xross".equals(owner))
                .collect(Collectors.toSet());
        if (engine != null && engine.getPluginManager() != null) {
            engine.getPluginManager().getLoadedPlugins().stream()
                    .filter(plugin -> owners.contains(plugin.meta().getId()))
                    .forEach(plugin -> categories.put(plugin.meta().getId(), plugin.meta().getName()));
        }
        return Map.copyOf(categories);
    }

    private void requireEnabled() {
        if (codec == null) {
            throw new IllegalStateException("Xross Editor is disabled. Configure XROSS_EDITOR_URL and XROSS_EDITOR_SECRET.");
        }
    }

    private String certificationProgramId() {
        return engine == null
                ? "openxross-partner"
                : engine.getConfiguration().product().certificationProgramId();
    }

    private static XrossDbKey sessionKey(String nonce) {
        return new XrossDbKey("editor", "sessions", nonce);
    }

    private static XrossDbKey activeSessionKey(SettingScope scope, long guildId, long userId) {
        return new XrossDbKey(
                "editor",
                "active-sessions",
                scope.name().toLowerCase() + "-" + guildId + "-" + userId
        );
    }
}
