package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XrossShardAutoScaleConfigurationTest {
    @Test
    void autoScaleCanIncreaseAndNeverNeedsExplicitShardIds() {
        var configuration = XrossShardConfiguration.builder()
                .mode(XrossShardMode.AUTO_SCALE)
                .totalShards(2)
                .autoScaleCheckSeconds(60)
                .autoScaleCooldownSeconds(60)
                .build();

        var scaled = configuration.withResolvedTotalShards(8);
        assertEquals(XrossShardMode.AUTO_SCALE, scaled.mode());
        assertEquals(8, scaled.totalShards());
        assertEquals(8, scaled.shardIds().size());
        assertFalse(scaled.usesExplicitShardIds());
    }

    @Test
    void autoScaleRejectsExplicitDistributedAssignment() {
        assertThrows(IllegalArgumentException.class, () -> XrossShardConfiguration.builder()
                .mode(XrossShardMode.AUTO_SCALE)
                .totalShards(4)
                .shardIds(0, 1)
                .build());
    }

    @Test
    void clusterCoordinatorCanProvidePartialAssignmentForAutoScaleMode() {
        var configuration = XrossShardConfiguration.builder()
                .mode(XrossShardMode.AUTO_SCALE)
                .totalShards(4)
                .build();
        var assigned = configuration.withClusterAssignment(8, new int[]{2, 6});
        assertEquals(8, assigned.totalShards());
        assertEquals(java.util.Set.of(2, 6), assigned.shardIds());
        assertTrue(assigned.coordinatedAssignment());
    }

    @Test
    void optionalCeilingClampsDiscordRecommendation() {
        var configuration = XrossShardConfiguration.builder()
                .mode(XrossShardMode.AUTO_SCALE)
                .totalShards(2)
                .autoScaleMaxShards(16)
                .build();
        assertEquals(16, configuration.clampAutoScaleTarget(32));
        assertEquals(8, configuration.clampAutoScaleTarget(8));
    }
}
