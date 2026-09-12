package com.ztraqto.openxross.runtime.cluster;

import com.ztraqto.openxross.api.cluster.XrossClusterPhase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record XrossClusterTopology(
        long generation,
        int totalShards,
        XrossClusterPhase phase,
        String leaderNodeId,
        long createdAt,
        String reason,
        Map<String, List<Integer>> assignments
) {
    public XrossClusterTopology {
        if (generation < 1) throw new IllegalArgumentException("generation must be positive.");
        if (totalShards < 1) throw new IllegalArgumentException("totalShards must be positive.");
        if (phase == null) throw new IllegalArgumentException("phase is required.");
        LinkedHashMap<String, List<Integer>> copy = new LinkedHashMap<>();
        if (assignments != null) {
            assignments.forEach((node, shards) -> copy.put(node, shards == null ? List.of() : shards.stream().sorted().toList()));
        }
        assignments = Map.copyOf(copy);
    }

    public List<Integer> shardsFor(String nodeId) {
        return assignments.getOrDefault(nodeId, List.of());
    }
}
