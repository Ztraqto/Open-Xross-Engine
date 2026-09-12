package com.ztraqto.openxross.api.cluster;

import java.util.List;

public record XrossClusterAssignment(long generation, int totalShards, List<Integer> shardIds) {
    public XrossClusterAssignment {
        if (generation < 1) throw new IllegalArgumentException("generation must be positive.");
        if (totalShards < 1) throw new IllegalArgumentException("totalShards must be positive.");
        shardIds = shardIds == null ? List.of() : shardIds.stream().sorted().distinct().toList();
        for (int shardId : shardIds) {
            if (shardId < 0 || shardId >= totalShards) {
                throw new IllegalArgumentException("Invalid shard id " + shardId + " for total " + totalShards);
            }
        }
    }

    public int[] shardIdArray() {
        return shardIds.stream().mapToInt(Integer::intValue).toArray();
    }
}
