package com.ztraqto.openxross.api.plugin;

public enum PluginPermission {
    JDA_FULL_ACCESS("Direct access to the complete JDA and ShardManager API"),
    XROSS_DB_FULL_ACCESS("Direct access to all XrossDB namespaces, including Engine security state"),
    AI_TERMINAL_ACCESS("Send prompts through the XrossEngine AI terminal service");

    private final String description;

    PluginPermission(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
