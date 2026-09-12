package com.ztraqto.openxross.runtime.cluster;

import com.ztraqto.openxross.api.cluster.XrossClusterNodeState;

import java.util.List;

public record XrossClusterNodeRecord(
        String nodeId,
        String engineVersion,
        long startedAt,
        long heartbeatAt,
        boolean leaderEligible,
        int maxShards,
        long preparedGeneration,
        long runningGeneration,
        List<Integer> localShards,
        XrossClusterNodeState state
) {
    public XrossClusterNodeRecord {
        localShards = localShards == null ? List.of() : List.copyOf(localShards);
        state = state == null ? XrossClusterNodeState.JOINING : state;
    }
}
