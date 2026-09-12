package com.ztraqto.openxross.service;

import com.ztraqto.openxross.api.plugin.PluginMeta;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class PluginApprovalQueue {

    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private final Set<String> keys = new HashSet<>();
    private Request active;

    synchronized boolean offer(Request request) {
        if (!keys.add(request.key())) return false;
        waiting.addLast(request);
        return true;
    }

    synchronized Request activateNext(Predicate<Request> isPending) {
        if (active != null) return null;
        while (!waiting.isEmpty()) {
            Request request = waiting.removeFirst();
            if (!isPending.test(request)) {
                keys.remove(request.key());
                continue;
            }
            active = request;
            return request;
        }
        return null;
    }

    synchronized Request find(String requestId) {
        if (active != null && active.requestId().equals(requestId)) return active;
        return waiting.stream().filter(request -> request.requestId().equals(requestId)).findFirst().orElse(null);
    }

    synchronized boolean complete(String requestId) {
        if (active != null && active.requestId().equals(requestId)) {
            keys.remove(active.key());
            active = null;
            return true;
        }
        Iterator<Request> iterator = waiting.iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (!request.requestId().equals(requestId)) continue;
            iterator.remove();
            keys.remove(request.key());
            return true;
        }
        return false;
    }

    synchronized void release(Request request) {
        if (active == null || !active.key().equals(request.key())) return;
        keys.remove(active.key());
        active = null;
    }

    synchronized void clear() {
        waiting.clear();
        keys.clear();
        active = null;
    }

    record Request(PluginMeta meta, String fingerprint, File jarFile, String requestId) {
        Request(PluginMeta meta, String fingerprint, File jarFile) {
            this(meta, fingerprint, jarFile, UUID.randomUUID().toString().replace("-", ""));
        }

        String key() {
            return meta.getId() + "@" + fingerprint;
        }
    }
}
