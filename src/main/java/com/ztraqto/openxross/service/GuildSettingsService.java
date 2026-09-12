package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.settings.SettingType;
import com.ztraqto.openxross.api.settings.SettingScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public final class GuildSettingsService implements IService {

    public static final String LANGUAGE_KEY = "xross.language";
    public static final String VOICE_VOLUME_KEY = "xross.voice-volume";
    public static final String MANAGEMENT_LOG_CHANNEL_KEY = "xross.management-log-channel";

    private static final int MAX_DEFINITIONS = 512;
    private static final int MAX_UPDATE_ATTEMPTS = 5;

    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, GuildSettingDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<ListenerRegistration>> listeners = new ConcurrentHashMap<>();

    public GuildSettingsService(XrossDbClient databaseClient) {
        this.databaseClient = databaseClient;
        registerDefinition(GuildSettingDefinition.builder("xross", LANGUAGE_KEY, SettingType.SELECT)
                .label("Language")
                .label("ja", "表示言語")
                .label("en", "Display language")
                .description("Language used by Xross Engine and the Web Editor.")
                .description("ja", "Xross EngineとWebエディターで使用する表示言語です。")
                .description("en", "Language used by Xross Engine and the Web Editor.")
                .defaultValue("ja")
                .choice("ja", "日本語")
                .choice("en", "English")
                .build());
        registerDefinition(GuildSettingDefinition.builder("xross", VOICE_VOLUME_KEY, SettingType.INTEGER)
                .label("Voice volume")
                .label("ja", "音声音量")
                .label("en", "Voice volume")
                .description("Voice playback volume for this Discord server (0-100).")
                .description("ja", "このDiscordサーバーで再生する音声の音量です（0～100）。")
                .description("en", "Voice playback volume for this Discord server (0-100).")
                .defaultValue(100)
                .range(0, 100)
                .build());
        registerDefinition(GuildSettingDefinition.builder("xross", MANAGEMENT_LOG_CHANNEL_KEY, SettingType.CHANNEL)
                .label("Management log channel")
                .label("ja", "Bot管理ログチャンネル")
                .label("en", "Bot management log channel")
                .description("Operational failures and anonymous re-review events are delivered here.")
                .description("ja", "AI運用障害と匿名投稿の再審査イベントを通知するチャンネルです。")
                .description("en", "Operational failures and anonymous re-review events are delivered here.")
                .defaultValue("0").channelTypes("TEXT").build());
    }

    @Override
    public void init(XrossEngine engine) {
    }

    @Override
    public void shutdown() {
        listeners.clear();
    }

    @Override
    public String getName() {
        return "GuildSettingsService";
    }

    public synchronized void registerDefinition(GuildSettingDefinition definition) {
        if (definition.scope() != SettingScope.GUILD) {
            throw new IllegalArgumentException("GuildSettingsService only accepts GUILD definitions.");
        }
        if (!definitions.containsKey(definition.key()) && definitions.size() >= MAX_DEFINITIONS) {
            throw new IllegalStateException("Guild setting definition limit reached: " + MAX_DEFINITIONS);
        }
        GuildSettingDefinition previous = definitions.putIfAbsent(definition.key(), definition);
        if (previous != null && !previous.equals(definition)) {
            throw new IllegalArgumentException("Guild setting key is already registered: " + definition.key());
        }
    }

    public synchronized void unregisterOwner(String owner) {
        if ("xross".equals(owner)) {
            throw new IllegalArgumentException("Core Xross settings cannot be unregistered.");
        }
        definitions.values().removeIf(definition -> owner.equals(definition.owner()));
        listeners.values().forEach(registrations -> registrations.removeIf(registration -> owner.equals(registration.owner())));
    }

    public void registerListener(String owner, String key, BiConsumer<Long, JsonNode> listener) {
        GuildSettingDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown guild setting: " + key);
        }
        listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>())
                .add(new ListenerRegistration(owner, listener));
    }

    public void unregisterListeners(String owner) {
        listeners.values().forEach(registrations -> registrations.removeIf(registration -> owner.equals(registration.owner())));
    }

    public List<GuildSettingDefinition> getDefinitions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(GuildSettingDefinition::key))
                .toList();
    }

    public Map<String, JsonNode> getValues(long guildId) {
        StoredSettings stored = loadStored(guildId);
        LinkedHashMap<String, JsonNode> values = defaultValues();
        stored.values().fields().forEachRemaining(entry -> {
            GuildSettingDefinition definition = definitions.get(entry.getKey());
            if (definition != null) {
                try {
                    values.put(entry.getKey(), definition.validate(entry.getValue()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
        return Map.copyOf(values);
    }

    public boolean hasStoredValue(long guildId, String key) {
        if (!definitions.containsKey(key)) throw new IllegalArgumentException("Unknown guild setting: " + key);
        return loadStored(guildId).values().has(key);
    }

    public String getString(long guildId, String key) {
        JsonNode value = requireValue(guildId, key);
        return value.asText();
    }

    public boolean getBoolean(long guildId, String key) {
        JsonNode value = requireValue(guildId, key);
        return value.booleanValue();
    }

    public int getInteger(long guildId, String key) {
        JsonNode value = requireValue(guildId, key);
        return value.intValue();
    }

    public void setValue(long guildId, String key, JsonNode value) {
        apply(guildId, Map.of(key, value));
    }

    public Map<String, JsonNode> apply(long guildId, Map<String, JsonNode> changes) {
        if (guildId <= 0L) {
            throw new IllegalArgumentException("guildId must be positive.");
        }
        if (changes == null || changes.isEmpty()) {
            return Map.of();
        }

        Map<String, JsonNode> validatedChanges = validateChanges(changes);

        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            StoredSettings stored = loadStored(guildId);
            ObjectNode values = stored.values().deepCopy();
            LinkedHashMap<String, JsonNode> changedValues = new LinkedHashMap<>();
            validatedChanges.forEach((key, value) -> {
                JsonNode oldValue = values.get(key);
                if (!value.equals(oldValue)) {
                    values.set(key, value);
                    changedValues.put(key, value);
                }
            });

            if (changedValues.isEmpty()) {
                return Map.of();
            }

            ObjectNode payload = mapper.createObjectNode();
            payload.put("version", 1);
            payload.set("values", values);
            try {
                databaseClient.write(settingsKey(guildId), payload, stored.revision());
                notifyListeners(guildId, changedValues);
                return Map.copyOf(changedValues);
            } catch (XrossDbConflictException conflict) {
                if (attempt == MAX_UPDATE_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("Guild settings update exhausted retry attempts.");
    }

    public Map<String, JsonNode> validateChanges(Map<String, JsonNode> changes) {
        if (changes == null) {
            throw new IllegalArgumentException("changes are required.");
        }
        LinkedHashMap<String, JsonNode> validatedChanges = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            GuildSettingDefinition definition = definitions.get(key);
            if (definition == null) {
                throw new IllegalArgumentException("Unknown guild setting: " + key);
            }
            validatedChanges.put(key, definition.validate(value));
        });
        return Map.copyOf(validatedChanges);
    }

    private JsonNode requireValue(long guildId, String key) {
        JsonNode value = getValues(guildId).get(key);
        if (value == null) {
            throw new IllegalArgumentException("Unknown guild setting: " + key);
        }
        return value;
    }

    private StoredSettings loadStored(long guildId) {
        Optional<XrossDbRecord> record = databaseClient.read(settingsKey(guildId));
        if (record.isEmpty()) {
            return new StoredSettings(0L, mapper.createObjectNode());
        }
        JsonNode values = record.get().payload().get("values");
        if (!(values instanceof ObjectNode objectNode)) {
            return new StoredSettings(record.get().revision(), mapper.createObjectNode());
        }
        return new StoredSettings(record.get().revision(), objectNode);
    }

    private LinkedHashMap<String, JsonNode> defaultValues() {
        LinkedHashMap<String, JsonNode> values = new LinkedHashMap<>();
        getDefinitions().forEach(definition -> values.put(definition.key(), definition.defaultValue().deepCopy()));
        return values;
    }

    private void notifyListeners(long guildId, Map<String, JsonNode> changedValues) {
        changedValues.forEach((key, value) -> {
            List<ListenerRegistration> registrations = listeners.getOrDefault(key, new CopyOnWriteArrayList<>());
            registrations.forEach(registration -> registration.listener().accept(guildId, value.deepCopy()));
        });
    }

    private static XrossDbKey settingsKey(long guildId) {
        return new XrossDbKey("guild-settings", "values", Long.toString(guildId));
    }

    private record StoredSettings(long revision, ObjectNode values) {
    }

    private record ListenerRegistration(String owner, BiConsumer<Long, JsonNode> listener) {
    }
}
