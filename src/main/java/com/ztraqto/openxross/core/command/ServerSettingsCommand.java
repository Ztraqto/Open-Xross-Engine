package com.ztraqto.openxross.core.command;

/** Preferred product-neutral entry point for guild-scoped Web Editor settings. */
public final class ServerSettingsCommand extends EditorCommand {
    public ServerSettingsCommand() {
        super("server-settings", "Open the Web Editor for this Discord server");
    }
}
