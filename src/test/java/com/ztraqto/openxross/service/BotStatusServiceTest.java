package com.ztraqto.openxross.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotStatusServiceTest {

    @Test
    void expandsBotStatusPlaceholders() {
        assertEquals(
                "/help (16 servers) | 250 users | 2 shards | 1d 2h",
                BotStatusService.expand(
                        "/help ({servers} servers) | {users} users | {shards} shards | {uptime}",
                        new BotStatusService.Counts(16, 250, 2, 1_560)
                )
        );
        assertEquals("16 guilds", BotStatusService.expand(
                "{guilds} guilds", new BotStatusService.Counts(16, 250, 2, 0)));
    }
}
