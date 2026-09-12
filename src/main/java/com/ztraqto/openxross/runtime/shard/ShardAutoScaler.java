package com.ztraqto.openxross.runtime.shard;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.config.XrossShardConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Up-only runtime shard scaler. Discord's recommended shard total is treated as
 * authoritative for increases. Down-scaling is intentionally not automatic to
 * avoid topology churn and surprise reassignment.
 */
public final class ShardAutoScaler implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ShardAutoScaler.class);

    private final XrossEngine engine;
    private final String token;
    private final DiscordGatewayBotInfoClient gatewayClient;
    private final AtomicBoolean checking = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private volatile long lastScaleAt;

    public ShardAutoScaler(XrossEngine engine, String token) {
        this(engine, token, new DiscordGatewayBotInfoClient());
    }

    ShardAutoScaler(XrossEngine engine, String token, DiscordGatewayBotInfoClient gatewayClient) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.token = Objects.requireNonNull(token, "token");
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
    }

    public synchronized void start() {
        if (executor != null) return;
        XrossShardConfiguration configuration = engine.getConfiguration().shards();
        long interval = configuration.autoScaleCheckSeconds();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OpenXross-Shard-AutoScale");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::checkSafely, interval, interval, TimeUnit.SECONDS);
        logger.info("Shard AUTO_SCALE enabled; checking Discord recommendation every {} seconds.", interval);
    }

    public void checkNow() {
        checkSafely();
    }

    private void checkSafely() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            if (!engine.isRunning()) return;
            XrossShardConfiguration configuration = engine.getConfiguration().shards();
            if (!configuration.autoScaleEnabled()) return;

            long now = System.currentTimeMillis();
            long cooldownMillis = Duration.ofSeconds(configuration.autoScaleCooldownSeconds()).toMillis();
            if (lastScaleAt > 0L && now - lastScaleAt < cooldownMillis) return;

            DiscordGatewayBotInfo gateway = gatewayClient.fetch(token);
            int current = configuration.totalShards();
            int recommended = gateway.recommendedShards();
            int target = configuration.clampAutoScaleTarget(recommended);
            if (configuration.autoScaleMaxShards() > 0 && recommended > configuration.autoScaleMaxShards()) {
                logger.error(
                        "Discord recommends {} shards but autoScaleMaxShards={} blocks that topology. "
                                + "Increase/remove the ceiling before Discord requires the larger shard set.",
                        recommended,
                        configuration.autoScaleMaxShards()
                );
            }
            if (target <= current) return;

            if (!gateway.canStartShardSet(target)) {
                logger.warn(
                        "Deferring shard auto-scale {} -> {}: Discord session start limit has only {} remaining (reset in {} ms).",
                        current,
                        target,
                        gateway.sessionStartsRemaining(),
                        gateway.sessionResetAfterMillis()
                );
                return;
            }

            logger.warn("Discord recommends {} shards; OpenXross currently runs {}. Starting automatic reshard.", target, current);
            if (engine.scaleShardsUp(target, gateway)) {
                lastScaleAt = System.currentTimeMillis();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.warn("Shard auto-scale check failed; keeping the current topology.", exception);
        } finally {
            checking.set(false);
        }
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
    }
}
