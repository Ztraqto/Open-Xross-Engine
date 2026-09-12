package com.ztraqto.openxross.api.cluster;

import java.util.List;

public record XrossClusterStatus(
        boolean enabled,
        String clusterId,
        String nodeId,
        boolean leader,
        String leaderNodeId,
        long generation,
        int totalShards,
        List<Integer> assignedShardIds,
        XrossClusterPhase phase,
        boolean coordinationHealthy
) {
    public XrossClusterStatus {
        assignedShardIds = assignedShardIds == null ? List.of() : List.copyOf(assignedShardIds);
    }

    public static XrossClusterStatus disabled() {
        return new XrossClusterStatus(false, null, null, false, null, 0, 0, List.of(), null, true);
    }
}
