package com.ztraqto.openxross.api.system;

import com.ztraqto.openxross.XrossEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Privileged bootstrap plugin for OpenXrossEngine internals.
 *
 * <p>Unlike normal {@code XrossPlugin}s, system plugins are loaded before
 * services and Discord and are not hot-reloaded. They may register storage,
 * cluster, cache, metrics, or other infrastructure providers.</p>
 */
public abstract class XrossSystemPlugin {
    /** Public bootstrap API major understood by this engine release. */
    public static final String API_MAJOR = "1";
    private SystemPluginMeta meta;
    private SystemPluginContext context;
    private Logger logger;

    public final void init(SystemPluginContext context, SystemPluginMeta meta) {
        if (this.context != null) {
            throw new IllegalStateException("System plugin is already initialized.");
        }
        this.context = context;
        this.meta = meta;
        this.logger = LoggerFactory.getLogger("OpenXross.SystemPlugin." + meta.getId());
    }

    /** Called after construction but before providers are registered. */
    public void onLoad() throws Exception {
    }

    /** Register infrastructure providers required by the engine/applications. */
    public void registerProviders(SystemProviderRegistry registry) throws Exception {
    }

    /** Called after all runtimes are fully started. */
    public void onEngineReady() throws Exception {
    }

    /** Called before Discord/services begin shutting down. */
    public void onEngineStopping() throws Exception {
    }

    /** Called after runtimes have stopped and the class loader is about to close. */
    public void onUnload() throws Exception {
    }

    public final SystemPluginMeta getMeta() {
        return meta;
    }

    protected final XrossEngine getEngine() {
        return context.engine();
    }

    protected final SystemPluginContext getContext() {
        return context;
    }

    protected final Logger getLogger() {
        return logger;
    }
}
