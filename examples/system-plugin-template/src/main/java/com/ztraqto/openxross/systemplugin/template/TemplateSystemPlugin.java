package com.ztraqto.openxross.systemplugin.template;

import com.ztraqto.openxross.api.system.SystemProviderRegistry;
import com.ztraqto.openxross.api.system.XrossSystemPlugin;

/** Minimal privileged system-plugin example. */
public final class TemplateSystemPlugin extends XrossSystemPlugin {
    @Override
    public void onLoad() {
        getLogger().info("Template system plugin bootstrap started.");
    }

    @Override
    public void registerProviders(SystemProviderRegistry registry) {
        registry.register(TemplateProvider.class, new TemplateProvider("template-provider"));
    }

    @Override
    public void onEngineReady() {
        getLogger().info("OpenXross is ready.");
    }
}
