package com.ztraqto.openxross.service;

import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot of a prompt waiting in a profile queue. */
public record AIQueuedPrompt(
        UUID chatId,
        String profile,
        int position,
        Instant queuedAt,
        QueueReason reason,
        String systemPrompt,
        String promptContent
) {
    public enum QueueReason {
        RATE_LIMIT,
        PROFILE_CONCURRENCY_LIMIT,
        GLOBAL_CONCURRENCY_LIMIT
    }
}
