package com.ztraqto.openxross.runtime.shard;

/** Minimal Get Gateway Bot response used by OpenXross shard planning. */
public record DiscordGatewayBotInfo(
        int recommendedShards,
        int sessionStartsTotal,
        int sessionStartsRemaining,
        long sessionResetAfterMillis,
        int maxConcurrency
) {
    public DiscordGatewayBotInfo {
        if (recommendedShards < 1) throw new IllegalArgumentException("recommendedShards must be positive.");
        if (sessionStartsTotal < 0 || sessionStartsRemaining < 0) {
            throw new IllegalArgumentException("Session start limits cannot be negative.");
        }
        if (maxConcurrency < 1) throw new IllegalArgumentException("maxConcurrency must be positive.");
    }

    /** A full reshard rebuild must be able to identify every target shard. */
    public boolean canStartShardSet(int shardCount) {
        return shardCount > 0 && sessionStartsRemaining >= shardCount;
    }
}
