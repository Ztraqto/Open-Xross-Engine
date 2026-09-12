package com.ztraqto.openxross.api.system;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.config.XrossConfiguration;

import java.util.Objects;

/** Bootstrap context exposed to privileged system plugins. */
public final class SystemPluginContext {
    private final XrossEngine engine;
    private final SystemProviderRegistry providers;

    public SystemPluginContext(XrossEngine engine, SystemProviderRegistry providers) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public XrossEngine engine() {
        return engine;
    }

    public XrossConfiguration configuration() {
        return engine.getConfiguration();
    }

    public SystemProviderRegistry providers() {
        return providers;
    }
}
