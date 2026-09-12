package com.ztraqto.openxross.plugin.template;

import com.ztraqto.openxross.api.plugin.XrossPlugin;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.settings.SettingType;
import com.ztraqto.openxross.plugin.template.command.HelloCommand;

public class TemplatePlugin extends XrossPlugin {

    public static final String GREETING_KEY = "template-plugin.greeting";

    @Override
    public void onLoad() {
        getLogger().info("Plugin loaded!");
    }

    @Override
    public void onEnable() {
        getLogger().info("Plugin enabled!");
        registerSetting(GuildSettingDefinition.builder(getMeta().getId(), GREETING_KEY, SettingType.STRING)
                .label("Greeting message")
                .description("Message used by the hello command.")
                .defaultValue("Hello")
                .range(1, 60)
                .build());
        registerCommand(new HelloCommand());
    }

    public String getGreeting(long guildId) {
        return getStringSetting(guildId, GREETING_KEY);
    }

    @Override
    public void onDisable() {
        getLogger().info("Plugin disabled!");
    }
}
