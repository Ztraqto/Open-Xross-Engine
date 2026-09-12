package com.ztraqto.openxross.service;

import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.api.plugin.PluginMeta;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginApprovalQueueTest {

    @Test
    void displaysOnlyOneApprovalAtATime() {
        PluginApprovalQueue queue = new PluginApprovalQueue();
        PluginApprovalQueue.Request first = request("first", "a".repeat(64));
        PluginApprovalQueue.Request second = request("second", "b".repeat(64));

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertEquals(first, queue.activateNext(request -> true));
        assertNull(queue.activateNext(request -> true));

        assertTrue(queue.complete(first.requestId()));
        assertEquals(second, queue.activateNext(request -> true));
    }

    @Test
    void deduplicatesAndSkipsRequestsThatAreNoLongerPending() {
        PluginApprovalQueue queue = new PluginApprovalQueue();
        PluginApprovalQueue.Request stale = request("stale", "c".repeat(64));
        PluginApprovalQueue.Request current = request("current", "d".repeat(64));

        assertTrue(queue.offer(stale));
        assertFalse(queue.offer(stale));
        assertTrue(queue.offer(current));
        assertEquals(current, queue.activateNext(request -> request.meta().getId().equals("current")));
    }

    @Test
    void releasesFailedNotificationForRetry() {
        PluginApprovalQueue queue = new PluginApprovalQueue();
        PluginApprovalQueue.Request request = request("retry", "e".repeat(64));

        assertTrue(queue.offer(request));
        assertEquals(request, queue.activateNext(value -> true));
        queue.release(request);
        assertTrue(queue.offer(request));
    }


    @Test
    void approvalRoutingUsesOpaqueRequestIdsInsteadOfFingerprintPrefixes() {
        PluginApprovalQueue queue = new PluginApprovalQueue();
        PluginApprovalQueue.Request first = request("same", "a".repeat(64));
        PluginApprovalQueue.Request second = request("other", "a".repeat(12) + "b".repeat(52));

        assertFalse(first.requestId().startsWith("a".repeat(12)));
        assertFalse(second.requestId().startsWith("a".repeat(12)));
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertEquals(first, queue.activateNext(value -> true));
        assertEquals(first, queue.find(first.requestId()));
        assertNull(queue.find("a".repeat(12)));
        assertTrue(queue.complete(first.requestId()));
        assertEquals(second, queue.activateNext(value -> true));
    }

    private static PluginApprovalQueue.Request request(String id, String fingerprint) {
        PluginMeta meta = new PluginMeta();
        meta.setId(id);
        meta.setName(id);
        meta.setVersion("1.0.0");
        meta.setMain("example.Main");
        return new PluginApprovalQueue.Request(meta, fingerprint, new File(id + ".jar"));
    }
}
