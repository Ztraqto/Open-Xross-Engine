package com.ztraqto.openxross.api.system;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe registry populated during the system-plugin bootstrap phase. */
public final class SystemProviderRegistry {

    private final Map<Class<?>, Map<String, XrossSystemProvider>> providers = new ConcurrentHashMap<>();
    private volatile boolean sealed;

    public synchronized <T extends XrossSystemProvider> void register(Class<T> contract, T provider) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(provider, "provider");
        if (sealed) {
            throw new IllegalStateException("System provider registry is sealed after bootstrap.");
        }
        if (!contract.isInstance(provider)) {
            throw new IllegalArgumentException("Provider does not implement " + contract.getName());
        }
        String id = normalizeId(provider.id());
        Map<String, XrossSystemProvider> typed = providers.computeIfAbsent(contract, ignored -> new LinkedHashMap<>());
        if (typed.putIfAbsent(id, provider) != null) {
            throw new IllegalStateException("Duplicate system provider " + contract.getSimpleName() + ":" + id);
        }
    }

    public <T extends XrossSystemProvider> Optional<T> find(Class<T> contract, String id) {
        Objects.requireNonNull(contract, "contract");
        String normalized = normalizeId(id);
        Map<String, XrossSystemProvider> typed = providers.get(contract);
        if (typed == null) return Optional.empty();
        XrossSystemProvider provider = typed.get(normalized);
        return provider == null ? Optional.empty() : Optional.of(contract.cast(provider));
    }

    public <T extends XrossSystemProvider> T require(Class<T> contract, String id) {
        return find(contract, id).orElseThrow(() -> new IllegalStateException(
                "Required system provider is not registered: " + contract.getSimpleName() + ":" + id
        ));
    }

    public <T extends XrossSystemProvider> List<T> list(Class<T> contract) {
        Map<String, XrossSystemProvider> typed = providers.get(contract);
        if (typed == null) return List.of();
        List<T> values = new ArrayList<>();
        typed.values().forEach(provider -> values.add(contract.cast(provider)));
        values.sort(Comparator.comparingInt(XrossSystemProvider::priority).reversed().thenComparing(XrossSystemProvider::id));
        return List.copyOf(values);
    }

    /** Creates a bootstrap rollback point so a failing plugin cannot leave partial providers behind. */
    public synchronized Snapshot checkpoint() {
        Map<Class<?>, Map<String, XrossSystemProvider>> copy = new LinkedHashMap<>();
        providers.forEach((contract, typed) -> copy.put(contract, new LinkedHashMap<>(typed)));
        return new Snapshot(copy, sealed);
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        providers.clear();
        snapshot.providers.forEach((contract, typed) -> providers.put(contract, new LinkedHashMap<>(typed)));
        sealed = snapshot.sealed;
    }

    public synchronized void seal() {
        sealed = true;
    }

    public boolean isSealed() {
        return sealed;
    }

    public synchronized void clear() {
        providers.clear();
        sealed = false;
    }

    public static final class Snapshot {
        private final Map<Class<?>, Map<String, XrossSystemProvider>> providers;
        private final boolean sealed;

        private Snapshot(Map<Class<?>, Map<String, XrossSystemProvider>> providers, boolean sealed) {
            this.providers = providers;
            this.sealed = sealed;
        }
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("System provider id must not be blank.");
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid system provider id: " + id);
        }
        return normalized;
    }
}
