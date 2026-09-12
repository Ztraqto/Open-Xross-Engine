package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.api.console.XrossConsoleScope;
import com.ztraqto.openxross.core.PluginManager;
import com.ztraqto.openxross.service.XrossConsoleService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class XrossConsoleCommand extends SlashCommand {

    public XrossConsoleCommand() {
        super("xross-console", "Configure the private Xross Engine Discord console");
        setDefaultMemberPermissions(DefaultMemberPermissions.DISABLED);

        addOption(new OptionData(OptionType.STRING, "action", "Console action", true)
                .addChoices(
                        choice("Bind this channel", "bind"),
                        choice("Unbind", "unbind"),
                        choice("Change log scope", "scope"),
                        choice("Show status", "status"),
                        choice("Show pending plugins", "pending"),
                        choice("Reject a pending plugin", "deny"),
                        choice("Show loaded plugins", "plugins"),
                        choice("Scan plugin directory", "scan")
                ));
        addOption(new OptionData(OptionType.STRING, "plugin", "Pending plugin id to reject", false));
        addOption(new OptionData(OptionType.STRING, "level", "Messages sent to the console", false)
                .addChoices(
                        choice("Approvals only", XrossConsoleScope.APPROVALS_ONLY.name()),
                        choice("Warnings and approvals", XrossConsoleScope.WARNINGS_AND_APPROVALS.name()),
                        choice("All logs", XrossConsoleScope.ALL.name())
                ));
        setHelp(
                "/xross-console action:<bind|unbind|scope|status|pending|deny|plugins|scan> [level] [plugin]",
                "Discord application owners, accepted application-team administrators, and configured Bot administrators only."
        );
    }

    @Override
    public void execute(CommandContext context) {
        XrossConsoleService console = context.getEngine().getServiceManager().getService(XrossConsoleService.class);
        if (console == null || !console.isAdministrator(context.getEvent().getUser().getIdLong())) {
            context.replyErrorTr("cmd.xross-console.error.bot-admin");
            return;
        }

        String action = context.getOptionAsString("action");
        try {
            switch (action) {
                case "bind" -> bind(context, console);
                case "unbind" -> {
                    console.unbind();
                    context.replyTrEphemeral("cmd.xross-console.response.unbound");
                }
                case "scope" -> {
                    XrossConsoleScope scope = readScope(context, null);
                    if (scope == null) {
                        context.replyErrorTr("cmd.xross-console.error.level-required");
                        return;
                    }
                    console.setScope(scope);
                    context.replyTrEphemeral("cmd.xross-console.response.scope-changed", scope);
                }
                case "status" -> context.replyTrEphemeral(
                        "cmd.xross-console.response.status",
                        console.getChannelId() == 0L
                                ? context.tr("cmd.xross-console.value.not-configured")
                                : console.getChannelId(),
                        console.getScope()
                );
                case "pending" -> showPending(context, console);
                case "deny" -> denyPending(context, console);
                case "plugins" -> showLoaded(context, console);
                case "scan" -> scanPlugins(context, console);
                default -> context.replyErrorTr("cmd.xross-console.error.unknown-action");
            }
        } catch (Exception exception) {
            context.replyError(exception.getMessage());
        }
    }

    private static void bind(CommandContext context, XrossConsoleService console) {
        if (context.getEvent().getChannelType() != ChannelType.TEXT) {
            context.replyErrorTr("cmd.xross-console.error.text-channel");
            return;
        }
        XrossConsoleScope scope = readScope(context, XrossConsoleScope.APPROVALS_ONLY);
        console.bind(context.getEvent().getChannel().getIdLong(), scope);
        context.replyTrEphemeral("cmd.xross-console.response.bound");
    }

    private static XrossConsoleScope readScope(CommandContext context, XrossConsoleScope fallback) {
        String value = context.getOptionAsString("level");
        return value == null ? fallback : XrossConsoleScope.valueOf(value);
    }

    private static void showPending(CommandContext context, XrossConsoleService console) {
        PluginManager manager = console.getPluginManager();
        if (manager == null || manager.getPendingPlugins().isEmpty()) {
            context.replyTrEphemeral("cmd.xross-console.response.pending-empty");
            return;
        }
        String pending = manager.getPendingPlugins().stream()
                .map(plugin -> "- `" + plugin.meta().getId() + "` " + plugin.fingerprint().substring(0, 12))
                .collect(Collectors.joining("\n"));
        replyEphemeralPages(context, "cmd.xross-console.response.pending", pending);
        manager.notifyPendingApprovals();
    }

    private static void denyPending(CommandContext context, XrossConsoleService console) {
        String pluginId = context.getOptionAsString("plugin");
        PluginManager manager = console.getPluginManager();
        if (pluginId == null || pluginId.isBlank() || manager == null) {
            context.replyError("Specify a pending plugin id with plugin:.");
            return;
        }
        PluginManager.PendingPlugin pending = manager.getPendingPlugins().stream()
                .filter(plugin -> plugin.meta().getId().equals(pluginId))
                .findFirst().orElse(null);
        if (pending == null || !manager.denyPending(pluginId, context.getEvent().getUser().getIdLong())) {
            context.replyError("That plugin is no longer pending approval.");
            return;
        }
        console.completePluginDenial(pluginId, pending.fingerprint());
        context.reply("Plugin rejected: " + pluginId, true);
    }

    private static void showLoaded(CommandContext context, XrossConsoleService console) {
        PluginManager manager = console.getPluginManager();
        if (manager == null || manager.getLoadedPlugins().isEmpty()) {
            context.replyTrEphemeral("cmd.xross-console.response.loaded-empty");
            return;
        }
        String loaded = manager.getLoadedPlugins().stream()
                .map(plugin -> "- `" + plugin.meta().getId() + "` v" + plugin.meta().getVersion()
                        + " — " + context.tr(plugin.enabled()
                                ? "cmd.xross-console.value.enabled"
                                : "cmd.xross-console.value.disabled")
                        + " — `" + plugin.jarFile().getName() + "`"
                        + " — `" + plugin.fingerprint().substring(0, 12) + "`")
                .collect(Collectors.joining("\n"));
        replyEphemeralPages(context, "cmd.xross-console.response.loaded", loaded);
    }

    /** Discord limits interaction message content to 2,000 characters. */
    private static void replyEphemeralPages(CommandContext context, String translationKey, String content) {
        final int maxPageBody = 1_800;
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : content.split("\\n", -1)) {
            int separatorLength = page.isEmpty() ? 0 : 1;
            if (page.length() + separatorLength + line.length() > maxPageBody && !page.isEmpty()) {
                pages.add(page.toString());
                page.setLength(0);
                separatorLength = 0;
            }
            if (separatorLength > 0) page.append('\n');
            page.append(line);
        }
        if (!page.isEmpty() || pages.isEmpty()) pages.add(page.toString());

        context.getEvent().reply(context.tr(translationKey, pages.get(0)))
                .setEphemeral(true)
                .queue(hook -> {
                    for (int index = 1; index < pages.size(); index++) {
                        hook.sendMessage(context.tr(translationKey, pages.get(index)))
                                .setEphemeral(true)
                                .queue();
                    }
                });
    }

    private static void scanPlugins(CommandContext context, XrossConsoleService console) {
        PluginManager manager = console.getPluginManager();
        if (manager == null) {
            context.replyErrorTr("cmd.xross-console.error.plugin-manager-unavailable");
            return;
        }
        int count = manager.scanDirectory();
        context.replyTrEphemeral("cmd.xross-console.response.scanned", count);
    }

    private static Command.Choice choice(String english, String value) {
        return new Command.Choice(english, value);
    }
}
