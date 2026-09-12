package com.ztraqto.openxross.runtime.shard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordGatewayBotInfoTest {
    @Test
    void completeShardReplacementRequiresEnoughSessionStarts() {
        var enough = new DiscordGatewayBotInfo(8, 1000, 12, 1000, 1);
        assertTrue(enough.canStartShardSet(8));

        var insufficient = new DiscordGatewayBotInfo(8, 1000, 7, 1000, 1);
        assertFalse(insufficient.canStartShardSet(8));
    }
}
