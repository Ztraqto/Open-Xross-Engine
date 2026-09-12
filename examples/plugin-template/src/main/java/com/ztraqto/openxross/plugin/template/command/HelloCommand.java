package com.ztraqto.openxross.plugin.template.command;

import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.plugin.template.TemplatePlugin;

public class HelloCommand extends SlashCommand {

    public HelloCommand() {
        super("hello", "Says hello to the world");
    }

    @Override
    public void execute(CommandContext ctx) {
        String user = ctx.getEvent().getUser().getName();
        String greeting = ctx.getEvent().getGuild() == null
                ? "Hello"
                : getPlugin().getGreeting(ctx.getEvent().getGuild().getIdLong());
        ctx.replyTr("cmd.hello.response", greeting, user);
    }

    public TemplatePlugin getPlugin() {
        return (TemplatePlugin) super.getPlugin();
    }
}
