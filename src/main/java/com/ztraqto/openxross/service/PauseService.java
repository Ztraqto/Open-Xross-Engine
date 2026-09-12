package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PauseService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(PauseService.class);
    private static final long REFRESH_INTERVAL_MILLIS = 2000L;

    private final BotBanService banService;
    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> pausedPlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedCommands = ConcurrentHashMap.newKeySet();
    private volatile boolean corePaused;
    private volatile long lastRefresh;

    public PauseService(BotBanService banService, XrossDbClient databaseClient) {
        this.banService = banService;
        this.databaseClient = databaseClient;
    }

    @Override
    public void init(XrossEngine engine) {
        refresh();
    }

    @Override
    public void shutdown() {
        pausedPlugins.clear();
        pausedCommands.clear();
        corePaused = false;
        lastRefresh = 0L;
    }

    @Override
    public String getName() {
        return "PauseService";
    }

    public boolean canExecute(long userId, String pluginId, String commandName) {
        refreshIfStale();
        if (corePaused || banService.isBanned(userId)) {
            return false;
        }
        if (pluginId != null && pausedPlugins.contains(pluginId)) {
            return false;
        }
        return commandName == null || !pausedCommands.contains(commandKey(pluginId, commandName));
    }

    public void setCorePaused(boolean paused) {
        persist("core", paused);
        corePaused = paused;
        logger.warn("System pause state changed to: {}", paused);
    }

    public void setPluginPaused(String pluginId, boolean paused) {
        requireValue(pluginId, "pluginId");
        persist("plugin:" + pluginId, paused);
        setMembership(pausedPlugins, pluginId, paused);
        logger.info("Plugin {} pause state: {}", pluginId, paused);
    }

    public void setCommandPaused(String pluginId, String commandName, boolean paused) {
        requireValue(commandName, "commandName");
        String commandKey = commandKey(pluginId, commandName);
        persist("command:" + commandKey, paused);
        setMembership(pausedCommands, commandKey, paused);
        logger.info("Command {} pause state: {}", commandKey, paused);
    }

    private void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefresh < REFRESH_INTERVAL_MILLIS) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - lastRefresh >= REFRESH_INTERVAL_MILLIS) {
                try {
                    refresh();
                } catch (RuntimeException exception) {
                    lastRefresh = System.currentTimeMillis();
                    logger.warn("Failed to refresh shared pause state; retaining the last known state.", exception);
                }
            }
        }
    }

    private void refresh() {
        Set<String> plugins = ConcurrentHashMap.newKeySet();
        Set<String> commands = ConcurrentHashMap.newKeySet();
        boolean pausedCore = false;
        for (var record : databaseClient.scan("system", "pause")) {
            if (!record.payload().path("paused").asBoolean(false)) {
                continue;
            }
            String key = record.key().key();
            if ("core".equals(key)) {
                pausedCore = true;
            } else if (key.startsWith("plugin:")) {
                plugins.add(key.substring("plugin:".length()));
            } else if (key.startsWith("command:")) {
                commands.add(key.substring("command:".length()));
            }
        }
        pausedPlugins.clear();
        pausedPlugins.addAll(plugins);
        pausedCommands.clear();
        pausedCommands.addAll(commands);
        corePaused = pausedCore;
        lastRefresh = System.currentTimeMillis();
    }

    private void persist(String key, boolean paused) {
        XrossDbKey databaseKey = new XrossDbKey("system", "pause", key);
        if (!paused) {
            databaseClient.delete(databaseKey);
            lastRefresh = System.currentTimeMillis();
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("paused", true);
        payload.put("updatedAt", System.currentTimeMillis());
        databaseClient.write(databaseKey, payload);
        lastRefresh = System.currentTimeMillis();
    }

    private static void setMembership(Set<String> values, String value, boolean present) {
        if (present) {
            values.add(value);
        } else {
            values.remove(value);
        }
    }

    private static String commandKey(String pluginId, String commandName) {
        return (pluginId == null ? "SYSTEM" : pluginId) + ":" + commandName;
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }
}
