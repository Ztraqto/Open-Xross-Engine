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
import com.ztraqto.openxross.api.settings.SettingScope;
import com.ztraqto.openxross.api.settings.SettingType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public final class UserSettingsService implements IService {

    public static final String LANGUAGE_PREFERENCE_KEY = "xross.language-preference";

    private static final int MAX_DEFINITIONS = 512;
    private static final int MAX_UPDATE_ATTEMPTS = 5;

    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, GuildSettingDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<ListenerRegistration>> listeners = new ConcurrentHashMap<>();

    public UserSettingsService(XrossDbClient databaseClient) {
        this.databaseClient = databaseClient;
        registerDefinition(GuildSettingDefinition.builder("xross", LANGUAGE_PREFERENCE_KEY, SettingType.SELECT)
                .scope(SettingScope.USER)
                .label("Preferred language")
                .label("ja", "優先言語")
                .label("en", "Preferred language")
                .description("Overrides the server language for responses from this Bot.")
                .description("ja", "このBotからあなたへの応答で、サーバーの表示言語より優先して使用する言語です。")
                .description("en", "Overrides the server language for responses from this Bot.")
                .defaultValue("inherit")
                .choice("inherit", "Use server language")
                .choice("ja", "日本語")
                .choice("en", "English")
                .build());
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
        return "UserSettingsService";
    }

    public synchronized void registerDefinition(GuildSettingDefinition definition) {
        if (definition.scope() != SettingScope.USER) {
            throw new IllegalArgumentException("UserSettingsService only accepts USER definitions.");
        }
        if (!definitions.containsKey(definition.key()) && definitions.size() >= MAX_DEFINITIONS) {
            throw new IllegalStateException("User setting definition limit reached: " + MAX_DEFINITIONS);
        }
        GuildSettingDefinition previous = definitions.putIfAbsent(definition.key(), definition);
        if (previous != null && !previous.equals(definition)) {
            throw new IllegalArgumentException("User setting key is already registered: " + definition.key());
        }
    }

    public synchronized void unregisterOwner(String owner) {
        definitions.values().removeIf(definition -> owner.equals(definition.owner()));
        listeners.values().forEach(registrations -> registrations.removeIf(registration -> owner.equals(registration.owner())));
    }

    public void registerListener(String owner, String key, BiConsumer<Long, JsonNode> listener) {
        if (!definitions.containsKey(key)) {
            throw new IllegalArgumentException("Unknown user setting: " + key);
        }
        listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>())
                .add(new ListenerRegistration(owner, listener));
    }

    public List<GuildSettingDefinition> getDefinitions() {
        return definitions.values().stream().sorted(Comparator.comparing(GuildSettingDefinition::key)).toList();
    }

    public Map<String, JsonNode> getValues(long userId) {
        StoredSettings stored = loadStored(userId);
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

    public String getString(long userId, String key) {
        return requireValue(userId, key).asText();
    }

    public boolean getBoolean(long userId, String key) {
        return requireValue(userId, key).booleanValue();
    }

    public int getInteger(long userId, String key) {
        return requireValue(userId, key).intValue();
    }

    public void setValue(long userId, String key, JsonNode value) {
        apply(userId, Map.of(key, value));
    }

    public Map<String, JsonNode> apply(long userId, Map<String, JsonNode> changes) {
        if (userId <= 0L) throw new IllegalArgumentException("userId must be positive.");
        if (changes == null || changes.isEmpty()) return Map.of();
        Map<String, JsonNode> validated = validateChanges(changes);

        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            StoredSettings stored = loadStored(userId);
            ObjectNode values = stored.values().deepCopy();
            LinkedHashMap<String, JsonNode> changed = new LinkedHashMap<>();
            validated.forEach((key, value) -> {
                JsonNode oldValue = values.get(key);
                if (!value.equals(oldValue)) {
                    values.set(key, value);
                    changed.put(key, value);
                }
            });
            if (changed.isEmpty()) return Map.of();

            ObjectNode payload = mapper.createObjectNode();
            payload.put("version", 1);
            payload.set("values", values);
            try {
                databaseClient.write(settingsKey(userId), payload, stored.revision());
                notifyListeners(userId, changed);
                return Map.copyOf(changed);
            } catch (XrossDbConflictException conflict) {
                if (attempt == MAX_UPDATE_ATTEMPTS) throw conflict;
            }
        }
        throw new IllegalStateException("User settings update exhausted retry attempts.");
    }

    public Map<String, JsonNode> validateChanges(Map<String, JsonNode> changes) {
        LinkedHashMap<String, JsonNode> validated = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            GuildSettingDefinition definition = definitions.get(key);
            if (definition == null) throw new IllegalArgumentException("Unknown user setting: " + key);
            validated.put(key, definition.validate(value));
        });
        return Map.copyOf(validated);
    }

    private JsonNode requireValue(long userId, String key) {
        JsonNode value = getValues(userId).get(key);
        if (value == null) throw new IllegalArgumentException("Unknown user setting: " + key);
        return value;
    }

    private StoredSettings loadStored(long userId) {
        Optional<XrossDbRecord> record = databaseClient.read(settingsKey(userId));
        if (record.isEmpty()) return new StoredSettings(0L, mapper.createObjectNode());
        JsonNode values = record.get().payload().get("values");
        return values instanceof ObjectNode objectNode
                ? new StoredSettings(record.get().revision(), objectNode)
                : new StoredSettings(record.get().revision(), mapper.createObjectNode());
    }

    private LinkedHashMap<String, JsonNode> defaultValues() {
        LinkedHashMap<String, JsonNode> values = new LinkedHashMap<>();
        getDefinitions().forEach(definition -> values.put(definition.key(), definition.defaultValue().deepCopy()));
        return values;
    }

    private void notifyListeners(long userId, Map<String, JsonNode> changedValues) {
        changedValues.forEach((key, value) -> listeners
                .getOrDefault(key, new CopyOnWriteArrayList<>())
                .forEach(registration -> registration.listener().accept(userId, value.deepCopy())));
    }

    private static XrossDbKey settingsKey(long userId) {
        return new XrossDbKey("user-settings", "values", Long.toString(userId));
    }

    private record StoredSettings(long revision, ObjectNode values) {
    }

    private record ListenerRegistration(String owner, BiConsumer<Long, JsonNode> listener) {
    }
}
