package com.ztraqto.openxross.runtime.cluster;

public record XrossClusterLease(String nodeId, long leaseUntil, long epoch) {
}
