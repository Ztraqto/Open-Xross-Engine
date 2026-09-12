package com.ztraqto.openxross.api.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.storage.DatabaseContext;
import com.ztraqto.openxross.api.storage.IDatabaseOwner;
import com.ztraqto.openxross.api.storage.PluginDataStore;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.AITerminalService;
import com.ztraqto.openxross.service.EditorSensitiveDataService;
import com.ztraqto.openxross.service.GuildSettingsService;
import com.ztraqto.openxross.service.UserSettingsService;
import com.ztraqto.openxross.api.settings.SettingScope;
import com.ztraqto.openxross.service.LocaleService;
import com.ztraqto.openxross.service.PluginApprovalService;
import com.ztraqto.openxross.service.StorageService;
import com.ztraqto.openxross.service.XrossConsoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Base class for XrossEngine plugins. */
public abstract class XrossPlugin implements IDatabaseOwner {

    private final List<Object> managedEventListeners = new CopyOnWriteArrayList<>();
    private PluginMeta meta;
    private XrossEngine engine;
    private StorageService storage;
    private PluginDataStore dataStore;
    private Logger logger;
    private boolean enabled;

    /** Called by XrossEngine before the plugin lifecycle begins. */
    public final void init(XrossEngine engine, PluginMeta meta) {
        if (this.engine != null) {
            throw new IllegalStateException("Plugin is already initialized.");
        }
        this.engine = engine;
        this.meta = meta;
        // The scoped PluginDataStore is safe for every approved plugin. Direct
        // StorageService access remains permission-gated through requireService.
        this.storage = lookupService(StorageService.class);
        this.logger = LoggerFactory.getLogger(meta.getName());
        this.dataStore = PluginDataStore.forPlugin(storage.getClient(), meta.getId(), logger);
    }

    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onUnload() {
    }

    /** Prefer the focused helper APIs instead of depending on the engine internals. */
    @Deprecated
    protected final XrossEngine getEngine() {
        return engine;
    }

    public PluginMeta getMeta() {
        return meta;
    }

    public Logger getLogger() {
        return logger;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        if (enabled) {
            this.enabled = true;
            try {
                onEnable();
            } catch (RuntimeException | Error exception) {
                this.enabled = false;
                throw exception;
            }
            return;
        }
        try {
            onDisable();
        } finally {
            this.enabled = false;
        }
    }

    /** Registers a listener that XrossEngine automatically removes on unload. */
    protected final <T> T registerEventListener(T listener) {
        ShardManager shardManager = requireShardManager();
        shardManager.addEventListener(listener);
        managedEventListeners.add(listener);
        return listener;
    }

    protected final void registerCommand(SlashCommand command) {
        requireService(CommandService.class).register(this, command);
    }

    /** Registers an existing JDA command definition whose events are handled by a managed listener. */
    protected final void registerCommand(CommandData command) {
        requireService(CommandService.class).register(this, command);
    }

    /** Plugin-scoped JSON document storage backed by XrossDB. */
    public final PluginDataStore getDataStore() {
        if (dataStore == null) {
            throw new IllegalStateException("Plugin is not initialized.");
        }
        return dataStore;
    }

    protected final void registerSetting(GuildSettingDefinition definition) {
        if (!getMeta().getId().equals(definition.owner())) {
            throw new IllegalArgumentException("Plugin settings must use the plugin id as their owner.");
        }
        if (definition.scope() == SettingScope.USER) {
            requireService(UserSettingsService.class).registerDefinition(definition);
        } else {
            requireService(GuildSettingsService.class).registerDefinition(definition);
        }
    }

    protected final boolean getBooleanSetting(long guildId, String key) {
        requireOwnedSettingKey(key);
        return requireService(GuildSettingsService.class).getBoolean(guildId, key);
    }

    protected final int getIntegerSetting(long guildId, String key) {
        requireOwnedSettingKey(key);
        return requireService(GuildSettingsService.class).getInteger(guildId, key);
    }

    protected final String getStringSetting(long guildId, String key) {
        requireOwnedSettingKey(key);
        return requireService(GuildSettingsService.class).getString(guildId, key);
    }

    /** Returns a defensive copy of any plugin-owned guild setting value. */
    protected final JsonNode getSetting(long guildId, String key) {
        requireOwnedSettingKey(key);
        JsonNode value = requireService(GuildSettingsService.class).getValues(guildId).get(key);
        if (value == null) throw new IllegalArgumentException("Unknown guild setting: " + key);
        return value.deepCopy();
    }

    /** Encrypts a value for disclosure only through an authorized guild Editor session. */
    protected final String protectEditorSensitiveData(long guildId, String field, String value) {
        return requireService(EditorSensitiveDataService.class).protect(guildId, field, value);
    }

    /** Protects plugin-owned data with guild- and purpose-bound authenticated encryption. */
    protected final String protectSensitiveData(long guildId, String field, String value) {
        return requireService(EditorSensitiveDataService.class).protect(guildId, getMeta().getId(), field, value);
    }

    /** Opens plugin-owned data protected by {@link #protectSensitiveData(long, String, String)}. */
    protected final String revealSensitiveData(long guildId, String field, String value) {
        return requireService(EditorSensitiveDataService.class).reveal(guildId, getMeta().getId(), field, value);
    }

    /** Creates a stable non-reversible guild-scoped key without persisting the source identifier. */
    protected final String blindSensitiveData(long guildId, String field, String value) {
        return requireService(EditorSensitiveDataService.class).blind(guildId, getMeta().getId(), field, value);
    }

    /** Returns whether the user is explicitly listed in XROSS_ADMIN_USER_IDS. */
    protected final boolean isConfiguredBotAdministrator(long userId) {
        if (engine == null) {
            throw new IllegalStateException("Plugin is not initialized.");
        }
        return engine.getConfiguration().botAdministratorIds().contains(userId);
    }

    protected final void setSetting(long guildId, String key, boolean value) {
        setSettingValue(guildId, key, BooleanNode.valueOf(value));
    }

    protected final void setSetting(long guildId, String key, int value) {
        setSettingValue(guildId, key, IntNode.valueOf(value));
    }

    protected final void setSetting(long guildId, String key, String value) {
        setSettingValue(guildId, key, TextNode.valueOf(value));
    }

    /** Stores a structured plugin-owned guild setting after schema validation. */
    protected final void setSetting(long guildId, String key, JsonNode value) {
        if (value == null) throw new IllegalArgumentException("Setting value is required.");
        setSettingValue(guildId, key, value.deepCopy());
    }

    protected final void registerSettingListener(String key, BiConsumer<Long, JsonNode> listener) {
        requireOwnedSettingKey(key);
        requireService(GuildSettingsService.class).registerListener(getMeta().getId(), key, listener);
    }

    protected final boolean getBooleanUserSetting(long userId, String key) {
        requireOwnedSettingKey(key);
        return requireService(UserSettingsService.class).getBoolean(userId, key);
    }

    protected final int getIntegerUserSetting(long userId, String key) {
        requireOwnedSettingKey(key);
        return requireService(UserSettingsService.class).getInteger(userId, key);
    }

    protected final String getStringUserSetting(long userId, String key) {
        requireOwnedSettingKey(key);
        return requireService(UserSettingsService.class).getString(userId, key);
    }

    protected final void setUserSetting(long userId, String key, boolean value) {
        setUserSettingValue(userId, key, BooleanNode.valueOf(value));
    }

    protected final void setUserSetting(long userId, String key, int value) {
        setUserSettingValue(userId, key, IntNode.valueOf(value));
    }

    protected final void setUserSetting(long userId, String key, String value) {
        setUserSettingValue(userId, key, TextNode.valueOf(value));
    }

    protected final void registerUserSettingListener(String key, BiConsumer<Long, JsonNode> listener) {
        requireOwnedSettingKey(key);
        requireService(UserSettingsService.class).registerListener(getMeta().getId(), key, listener);
    }

    protected final <T extends IService> T requireService(Class<T> type) {
        if (type == StorageService.class
                || type == PluginApprovalService.class
                || type == XrossConsoleService.class) {
            requirePermission(PluginPermission.XROSS_DB_FULL_ACCESS);
        }
        if (type == AITerminalService.class) {
            requirePermission(PluginPermission.AI_TERMINAL_ACCESS);
        }
        return lookupService(type);
    }

    private <T extends IService> T lookupService(Class<T> type) {
        if (engine == null) {
            throw new IllegalStateException("Plugin is not initialized.");
        }
        T service = engine.getServiceManager().getService(type);
        if (service == null) {
            throw new IllegalStateException(type.getSimpleName() + " is unavailable.");
        }
        return service;
    }

    /**
     * Returns the complete JDA shard manager after checking the declared and
     * Bot-administrator-approved JDA_FULL_ACCESS permission.
     */
    public final ShardManager getJdaAccess() {
        engine.getPluginManager().requirePermission(this, PluginPermission.JDA_FULL_ACCESS);
        return requireShardManager();
    }

    public final void releaseManagedResources() {
        if (engine != null && engine.getShardManager() != null && !managedEventListeners.isEmpty()) {
            engine.getShardManager().removeEventListener(managedEventListeners.toArray());
        }
        managedEventListeners.clear();
    }

    protected void postBus(Object packet) {
        engine.getPluginBus().post(packet);
    }

    protected <T> T askBus(Object packet, Class<T> responseClass) {
        return engine.getPluginBus().ask(packet, responseClass);
    }

    public String tr(long guildId, String key, Object... args) {
        LocaleService service = engine.getServiceManager().getService(LocaleService.class);
        return service == null ? key : service.get(this, guildId, key, args);
    }

    public String tr(String key, Object... args) {
        return tr(0L, key, args);
    }

    /** Direct JDBC cannot represent a remote XrossDB connection; use the XrossDB APIs. */
    @Deprecated
    protected Connection getDatabaseConnection() throws SQLException {
        return storage.getConnection(this);
    }

    protected final XrossDbClient getDatabaseClient() {
        requirePermission(PluginPermission.XROSS_DB_FULL_ACCESS);
        return storage.getClient();
    }

    private void requirePermission(PluginPermission permission) {
        if (engine == null || engine.getPluginManager() == null) {
            throw new SecurityException("Plugin permissions are unavailable before the plugin is registered.");
        }
        engine.getPluginManager().requirePermission(this, permission);
    }

    protected void saveQL(Object entity) {
        storage.getWrider().initTable(this, entity.getClass());
        storage.getWrider().saveQL(this, entity);
    }

    protected <T> T loadQL(Class<T> type, Object id) {
        return storage.getWrider().loadQL(this, type, id);
    }

    protected final <T> List<T> loadAllQL(Class<T> type) {
        return storage.getWrider().loadAllQL(this, type);
    }

    protected final boolean deleteQL(Class<?> type, Object id) {
        return storage.getWrider().deleteQL(this, type, id);
    }

    protected void saveQL(String databaseName, Object entity) {
        IDatabaseOwner owner = new DatabaseContext(this, databaseName);
        storage.getWrider().initTable(owner, entity.getClass());
        storage.getWrider().saveQL(owner, entity);
    }

    protected <T> T loadQL(String databaseName, Class<T> type, Object id) {
        return storage.getWrider().loadQL(new DatabaseContext(this, databaseName), type, id);
    }

    protected final <T> List<T> loadAllQL(String databaseName, Class<T> type) {
        return storage.getWrider().loadAllQL(new DatabaseContext(this, databaseName), type);
    }

    protected final boolean deleteQL(String databaseName, Class<?> type, Object id) {
        return storage.getWrider().deleteQL(new DatabaseContext(this, databaseName), type, id);
    }

    @Override
    public String getDatabaseContextId() {
        return "plugins/" + getMeta().getId() + "/database";
    }

    private ShardManager requireShardManager() {
        if (engine == null || engine.getShardManager() == null) {
            throw new IllegalStateException("JDA is not available.");
        }
        return engine.getShardManager();
    }

    private void setSettingValue(long guildId, String key, JsonNode value) {
        requireOwnedSettingKey(key);
        requireService(GuildSettingsService.class).setValue(guildId, key, value);
    }

    private void setUserSettingValue(long userId, String key, JsonNode value) {
        requireOwnedSettingKey(key);
        requireService(UserSettingsService.class).setValue(userId, key, value);
    }

    private void requireOwnedSettingKey(String key) {
        if (getMeta() == null || key == null || !key.startsWith(getMeta().getId() + ".")) {
            throw new IllegalArgumentException("Plugin setting key must start with the plugin id prefix.");
        }
    }
}
