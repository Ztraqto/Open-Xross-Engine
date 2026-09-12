package com.ztraqto.openxross.api.shard;

/** Immutable operational snapshot of one locally managed shard. */
public record XrossShardSnapshot(
        int shardId,
        int totalShards,
        String status,
        int guildCount,
        long gatewayPing
) {
}
