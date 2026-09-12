package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.sharding.ShardManager;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Persists and periodically refreshes the Bot-wide Discord custom status. */
public final class BotStatusService implements IService {

    public static final String DEFAULT_TEMPLATE = "/help ({servers} servers)";
    public static final int MAX_TEMPLATE_LENGTH = 128;

    private static final XrossDbKey STATUS_KEY = new XrossDbKey("xross-core", "settings", "bot-status");

    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private String template;
    private ShardManager shardManager;
    private ScheduledExecutorService refresher;
    private long startedAt;

    public BotStatusService(XrossDbClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public synchronized void init(XrossEngine engine) {
        startedAt = engine == null ? System.currentTimeMillis() : engine.getStartTime();
        template = databaseClient.read(STATUS_KEY)
                .map(record -> record.payload().path("template").asText(DEFAULT_TEMPLATE))
                .filter(value -> !value.isBlank() && value.length() <= MAX_TEMPLATE_LENGTH)
                .orElse(DEFAULT_TEMPLATE);
    }

    public synchronized void attach(ShardManager shardManager) {
        this.shardManager = shardManager;
        refresh();
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xross-bot-status");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleAtFixedRate(this::refresh, 1, 1, TimeUnit.MINUTES);
    }

    public synchronized String getTemplate() {
        return template;
    }

    public synchronized void setTemplate(String value) {
        String validated = validate(value);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("version", 1);
        payload.put("template", validated);
        databaseClient.write(STATUS_KEY, payload, XrossDbClient.ANY_REVISION);
        template = validated;
        refresh();
    }

    synchronized void refresh() {
        if (shardManager == null) return;
        Counts counts = new Counts(
                shardManager.getGuildCache().size(),
                shardManager.getUserCache().size(),
                shardManager.getShardsRunning(),
                Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - startedAt)).toMinutes()
        );
        shardManager.setActivity(Activity.customStatus(expand(template, counts)));
    }

    static String expand(String template, Counts counts) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{servers}", Long.toString(counts.servers()));
        placeholders.put("{guilds}", Long.toString(counts.servers()));
        placeholders.put("{users}", Long.toString(counts.users()));
        placeholders.put("{shards}", Integer.toString(counts.shards()));
        placeholders.put("{uptime}", formatUptime(counts.uptimeMinutes()));
        String result = template;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result.length() <= Activity.MAX_ACTIVITY_STATE_LENGTH
                ? result
                : result.substring(0, Activity.MAX_ACTIVITY_STATE_LENGTH);
    }

    private static String formatUptime(long minutes) {
        long days = minutes / 1_440;
        long hours = minutes % 1_440 / 60;
        long remainingMinutes = minutes % 60;
        return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + remainingMinutes + "m" : remainingMinutes + "m";
    }

    private static String validate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Bot status must not be blank.");
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("Bot status must be at most " + MAX_TEMPLATE_LENGTH + " characters.");
        }
        return trimmed;
    }

    @Override
    public synchronized void shutdown() {
        if (refresher != null) refresher.shutdownNow();
        refresher = null;
        shardManager = null;
    }

    @Override
    public String getName() {
        return "BotStatusService";
    }

    record Counts(long servers, long users, int shards, long uptimeMinutes) {
    }
}
