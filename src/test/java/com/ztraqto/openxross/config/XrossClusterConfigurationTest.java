package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XrossClusterConfigurationTest {
    @Test
    void autoClusterValidatesFailClosedTiming() {
        assertThrows(IllegalArgumentException.class, () -> XrossClusterConfiguration.builder()
                .mode(XrossClusterMode.AUTO)
                .nodeId("node-a")
                .coordinationLossTimeoutSeconds(30)
                .nodeTimeoutSeconds(30)
                .build());
    }

    @Test
    void autoClusterAcceptsUniqueNodeConfiguration() {
        var configuration = XrossClusterConfiguration.builder()
                .mode(XrossClusterMode.AUTO)
                .clusterId("prod")
                .nodeId("node-a")
                .maxShardsPerNode(8)
                .build();
        assertTrue(configuration.autoManaged());
        assertEquals("prod", configuration.clusterId());
        assertEquals("node-a", configuration.nodeId());
        assertEquals(8, configuration.maxShardsPerNode());
    }
}
