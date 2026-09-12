package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XrossShardConfigurationEnhancedTest {

    @Test
    void computesDeterministicGuildShardAndOwnership() {
        XrossShardConfiguration configuration = XrossShardConfiguration.builder()
                .totalShards(8)
                .shardIds(1, 3, 5, 7)
                .build();

        long guildId = 123456789012345678L;
        int expected = (int) ((guildId >>> 22) % 8);
        assertEquals(expected, configuration.shardIdForGuild(guildId));
        assertEquals(configuration.shardIds().contains(expected), configuration.ownsGuild(guildId));
    }

    @Test
    void shardRangeExpandsInclusiveRange() {
        XrossShardConfiguration configuration = XrossShardConfiguration.builder()
                .totalShards(8)
                .shardRange(2, 4)
                .readyTimeoutSeconds(90)
                .shutdownTimeoutSeconds(12)
                .build();

        assertArrayEquals(new int[]{2, 3, 4}, configuration.shardIdArray());
        assertEquals(90, configuration.readyTimeoutSeconds());
        assertEquals(12, configuration.shutdownTimeoutSeconds());
    }
}
