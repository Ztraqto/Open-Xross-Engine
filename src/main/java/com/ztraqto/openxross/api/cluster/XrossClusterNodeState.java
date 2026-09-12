package com.ztraqto.openxross.api.cluster;

public enum XrossClusterNodeState {
    JOINING,
    RUNNING,
    PREPARED,
    STANDBY,
    COORDINATION_LOST,
    STOPPING
}
