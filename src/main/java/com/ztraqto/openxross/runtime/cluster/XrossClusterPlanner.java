package com.ztraqto.openxross.runtime.cluster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, low-churn shard allocator for active OpenXross nodes. */
final class XrossClusterPlanner {
    private XrossClusterPlanner() {}

    static Map<String, List<Integer>> plan(
            int totalShards,
            List<XrossClusterNodeRecord> nodes,
            Map<String, List<Integer>> previous
    ) {
        if (totalShards < 1) throw new IllegalArgumentException("totalShards must be positive.");
        List<XrossClusterNodeRecord> ordered = nodes.stream()
                .sorted(Comparator.comparing(XrossClusterNodeRecord::nodeId))
                .toList();
        if (ordered.isEmpty()) throw new IllegalArgumentException("At least one active cluster node is required.");

        int aggregateCapacity = 0;
        boolean unlimited = false;
        for (XrossClusterNodeRecord node : ordered) {
            if (node.maxShards() == 0) unlimited = true;
            else aggregateCapacity += node.maxShards();
        }
        if (!unlimited && aggregateCapacity < totalShards) {
            throw new IllegalStateException("Active cluster capacity " + aggregateCapacity + " is below required shard total " + totalShards + ".");
        }

        Map<String, Integer> desiredCounts = desiredCounts(totalShards, ordered);
        LinkedHashMap<String, List<Integer>> result = new LinkedHashMap<>();
        ordered.forEach(node -> result.put(node.nodeId(), new ArrayList<>()));
        Set<Integer> remaining = new LinkedHashSet<>();
        for (int shard = 0; shard < totalShards; shard++) remaining.add(shard);

        // Retain as many previous assignments as possible without exceeding the
        // new balanced capacity. This minimizes handoff churn when a node joins.
        for (XrossClusterNodeRecord node : ordered) {
            List<Integer> old = previous == null ? List.of() : previous.getOrDefault(node.nodeId(), List.of());
            int keep = desiredCounts.get(node.nodeId());
            for (int shard : old.stream().sorted().toList()) {
                if (shard >= 0 && shard < totalShards && remaining.contains(shard) && result.get(node.nodeId()).size() < keep) {
                    result.get(node.nodeId()).add(shard);
                    remaining.remove(shard);
                }
            }
        }

        for (int shard : remaining) {
            XrossClusterNodeRecord target = ordered.stream()
                    .filter(node -> result.get(node.nodeId()).size() < desiredCounts.get(node.nodeId()))
                    .min(Comparator
                            .comparingInt((XrossClusterNodeRecord node) -> result.get(node.nodeId()).size())
                            .thenComparing(XrossClusterNodeRecord::nodeId))
                    .orElseThrow(() -> new IllegalStateException("No cluster capacity remained for shard " + shard));
            result.get(target.nodeId()).add(shard);
        }

        result.replaceAll((ignored, shards) -> shards.stream().sorted().toList());
        return Map.copyOf(result);
    }

    private static Map<String, Integer> desiredCounts(int totalShards, List<XrossClusterNodeRecord> nodes) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.nodeId(), 0));
        for (int index = 0; index < totalShards; index++) {
            XrossClusterNodeRecord target = nodes.stream()
                    .filter(node -> node.maxShards() == 0 || result.get(node.nodeId()) < node.maxShards())
                    .min(Comparator
                            .comparingInt((XrossClusterNodeRecord node) -> result.get(node.nodeId()))
                            .thenComparing(XrossClusterNodeRecord::nodeId))
                    .orElseThrow(() -> new IllegalStateException("Cluster capacity exhausted while planning shards."));
            result.put(target.nodeId(), result.get(target.nodeId()) + 1);
        }
        return result;
    }
}
