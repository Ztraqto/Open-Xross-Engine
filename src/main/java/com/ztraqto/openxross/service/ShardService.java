package com.ztraqto.openxross.service;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.shard.XrossShardLifecycleListener;
import com.ztraqto.openxross.api.shard.XrossShardSnapshot;
import com.ztraqto.openxross.config.XrossShardConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shard-aware engine service used by application plugins and stateful systems.
 * It centralizes deterministic guild ownership and graceful lifecycle hooks.
 */
public final class ShardService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(ShardService.class);

    private final XrossShardConfiguration configuration;
    private final CopyOnWriteArrayList<XrossShardLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ShardManager shardManager;

    public ShardService(XrossShardConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public void init(XrossEngine engine) {
        // Runtime attachment happens after JDA's ShardManager is built.
    }

    @Override
    public void shutdown() {
        shardManager = null;
        listeners.clear();
    }

    @Override
    public String getName() {
        return "ShardService";
    }

    public void attach(ShardManager shardManager) {
        this.shardManager = Objects.requireNonNull(shardManager, "shardManager");
    }

    public void addLifecycleListener(XrossShardLifecycleListener listener) {
        XrossShardLifecycleListener checked = Objects.requireNonNull(listener, "listener");
        if (!listeners.addIfAbsent(checked)) {
            return;
        }

        // Application plugins are loaded after the initial Discord READY phase.
        // Replay currently connected shards so late listeners still receive a
        // deterministic initialization callback.
        ShardManager manager = shardManager;
        if (manager != null) {
            for (JDA shard : List.copyOf(manager.getShards())) {
                if (shard.getStatus() == JDA.Status.CONNECTED) {
                    invoke("READY replay", shard, checked::onShardReady);
                }
            }
        }
    }

    public void removeLifecycleListener(XrossShardLifecycleListener listener) {
        listeners.remove(listener);
    }

    public int shardIdForGuild(long guildId) {
        return configuration.shardIdForGuild(guildId);
    }

    public boolean ownsGuild(long guildId) {
        return configuration.ownsGuild(guildId);
    }

    public Optional<JDA> shardForGuild(long guildId) {
        ShardManager manager = shardManager;
        if (manager == null) return Optional.empty();
        return Optional.ofNullable(manager.getShardById(shardIdForGuild(guildId)));
    }

    public List<XrossShardSnapshot> snapshots() {
        ShardManager manager = shardManager;
        if (manager == null) return List.of();
        return manager.getShards().stream()
                .map(shard -> new XrossShardSnapshot(
                        shard.getShardInfo().getShardId(),
                        shard.getShardInfo().getShardTotal(),
                        shard.getStatus().name(),
                        shard.getGuilds().size(),
                        shard.getGatewayPing()
                ))
                .sorted(java.util.Comparator.comparingInt(XrossShardSnapshot::shardId))
                .toList();
    }

    public void notifyReady(JDA shard) {
        notifyListeners("READY", shard, XrossShardLifecycleListener::onShardReady);
    }

    public void notifyDisconnected(JDA shard) {
        notifyListeners("DISCONNECTED", shard, XrossShardLifecycleListener::onShardDisconnected);
    }

    public void notifyResumed(JDA shard) {
        notifyListeners("RESUMED", shard, XrossShardLifecycleListener::onShardResumed);
    }

    public void notifyRecreated(JDA shard) {
        notifyListeners("RECREATED", shard, XrossShardLifecycleListener::onShardRecreated);
    }

    public void notifyShutdown(JDA shard) {
        notifyListeners("SHUTDOWN", shard, XrossShardLifecycleListener::onShardShutdown);
    }

    public void notifyStoppingAll() {
        ShardManager manager = shardManager;
        if (manager == null) return;
        for (JDA shard : List.copyOf(manager.getShards())) {
            notifyListeners("STOPPING", shard, XrossShardLifecycleListener::onShardStopping);
        }
    }

    public void notifyTopologyChanging(int previousTotal, int newTotal) {
        for (XrossShardLifecycleListener listener : listeners) {
            try {
                listener.onShardTopologyChanging(previousTotal, newTotal);
            } catch (RuntimeException exception) {
                logger.warn("Shard lifecycle listener failed during topology change {} -> {}.", previousTotal, newTotal, exception);
            }
        }
    }

    public void notifyTopologyChanged(int previousTotal, int newTotal) {
        for (XrossShardLifecycleListener listener : listeners) {
            try {
                listener.onShardTopologyChanged(previousTotal, newTotal);
            } catch (RuntimeException exception) {
                logger.warn("Shard lifecycle listener failed after topology change {} -> {}.", previousTotal, newTotal, exception);
            }
        }
    }

    private void notifyListeners(String phase, JDA shard, Callback callback) {
        for (XrossShardLifecycleListener listener : listeners) {
            invoke(phase, shard, (shardId, jda) -> callback.call(listener, shardId, jda));
        }
    }

    private void invoke(String phase, JDA shard, ListenerCallback callback) {
        int shardId = shard.getShardInfo().getShardId();
        try {
            callback.call(shardId, shard);
        } catch (RuntimeException exception) {
            logger.warn("Shard lifecycle listener failed during {} for shard {}.", phase, shardId, exception);
        }
    }

    @FunctionalInterface
    private interface Callback {
        void call(XrossShardLifecycleListener listener, int shardId, JDA shard);
    }

    @FunctionalInterface
    private interface ListenerCallback {
        void call(int shardId, JDA shard);
    }
}
