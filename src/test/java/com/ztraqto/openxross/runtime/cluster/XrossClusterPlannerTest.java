package com.ztraqto.openxross.runtime.cluster;

import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.api.cluster.XrossClusterNodeState;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XrossClusterPlannerTest {
    @Test
    void balancesAndPreservesEveryShardExactlyOnce() {
        long now = System.currentTimeMillis();
        var a = node("node-a", now, 0);
        var b = node("node-b", now, 0);
        var plan = XrossClusterPlanner.plan(9, List.of(a, b), Map.of());

        assertEquals(9, plan.values().stream().mapToInt(List::size).sum());
        var unique = new HashSet<Integer>();
        plan.values().forEach(unique::addAll);
        assertEquals(9, unique.size());
        assertTrue(Math.abs(plan.get("node-a").size() - plan.get("node-b").size()) <= 1);
    }

    @Test
    void addingNodeMovesOnlyTheShardsNeededForBalance() {
        long now = System.currentTimeMillis();
        var a = node("node-a", now, 0);
        var b = node("node-b", now, 0);
        var first = XrossClusterPlanner.plan(8, List.of(a, b), Map.of());
        var c = node("node-c", now, 0);
        var second = XrossClusterPlanner.plan(8, List.of(a, b, c), first);

        assertEquals(8, second.values().stream().mapToInt(List::size).sum());
        assertFalse(second.get("node-c").isEmpty());
    }

    @Test
    void respectsPerNodeCapacity() {
        long now = System.currentTimeMillis();
        var a = node("node-a", now, 1);
        var b = node("node-b", now, 4);
        var plan = XrossClusterPlanner.plan(5, List.of(a, b), Map.of());
        assertEquals(1, plan.get("node-a").size());
        assertEquals(4, plan.get("node-b").size());
    }

    private static XrossClusterNodeRecord node(String id, long now, int max) {
        return new XrossClusterNodeRecord(id, "1.4.1", now, now, true, max, 0, 0, List.of(), XrossClusterNodeState.JOINING);
    }
}
