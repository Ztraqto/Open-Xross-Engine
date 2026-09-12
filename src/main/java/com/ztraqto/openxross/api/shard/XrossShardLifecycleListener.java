package com.ztraqto.openxross.api.shard;

import net.dv8tion.jda.api.JDA;

/**
 * Lifecycle callbacks for a shard owned by the current OpenXross process.
 *
 * <p>Callbacks are best-effort notifications. Implementations must return
 * quickly and should offload expensive work. {@link #onShardStopping(int, JDA)}
 * is the graceful checkpoint hook; disconnect/resume/recreate callbacks expose
 * transient Gateway state without forcing application plugins to depend on
 * JDA session-event classes.</p>
 */
public interface XrossShardLifecycleListener {
    default void onShardReady(int shardId, JDA shard) {}
    default void onShardDisconnected(int shardId, JDA shard) {}
    default void onShardResumed(int shardId, JDA shard) {}
    default void onShardRecreated(int shardId, JDA shard) {}
    default void onShardStopping(int shardId, JDA shard) {}
    default void onShardShutdown(int shardId, JDA shard) {}

    /** Called before OpenXross tears down the current shard topology for an automatic reshard. */
    default void onShardTopologyChanging(int previousTotalShards, int newTotalShards) {}

    /** Called after application plugins are loaded on the replacement shard topology. */
    default void onShardTopologyChanged(int previousTotalShards, int newTotalShards) {}
}
