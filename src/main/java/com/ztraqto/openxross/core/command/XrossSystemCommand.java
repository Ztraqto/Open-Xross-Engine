package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.XrossConsoleService;

public class XrossSystemCommand extends SlashCommand {

    public XrossSystemCommand() {
        super("xross", "Xross Engine System Management");
        // /xross version is intentionally visible to normal users. Mutating
        // subcommands enforce Administrator/Bot-admin privileges at runtime.
        addSubcommand(new SubcommandData("version", "Show the OpenXrossEngine version"));
        addSubcommand(new SubcommandData("sync-global", "Sync global application commands"));
        addSubcommand(new SubcommandData("sync-guild", "Sync application commands for this server"));
        addSubcommand(new SubcommandData("delete-guild", "Delete all application commands for this server")
                .addOptions(new OptionData(OptionType.BOOLEAN, "confirm", "Required confirmation", true)));
        addSubcommand(new SubcommandData("delete-global", "Delete all global application commands")
                .addOptions(new OptionData(OptionType.BOOLEAN, "confirm", "Required confirmation", true)));
    }

    @Override
    public void execute(CommandContext ctx) {
        String action = ctx.getEvent().getSubcommandName();
        // Migration compatibility for Discord-side command definitions synced
        // by OpenXrossEngine 1.1.0/1.1.1. Once the new subcommand tree is
        // synced, this fallback is no longer used.
        if (action == null) {
            action = ctx.getOptionAsString("action");
        }
        if ("version".equals(action)) {
            var shards = ctx.getEngine().getConfiguration().shards();
            var cluster = ctx.getEngine().getClusterStatus();
            String clusterMode = ctx.getEngine().getConfiguration().cluster().mode().name();
            String node = cluster.enabled() ? cluster.nodeId() : "-";
            String role = cluster.enabled() ? (cluster.leader() ? "LEADER" : "WORKER") : "-";
            String assigned = cluster.enabled() ? cluster.assignedShardIds().toString() : shards.shardIds().stream().sorted().toList().toString();
            ctx.replyTrEphemeral(
                    "cmd.xross.response.version",
                    ctx.getEngine().getVersion(),
                    shards.mode().name(),
                    shards.totalShards(),
                    clusterMode,
                    node,
                    role,
                    assigned
            );
            return;
        }

        if (!isGuildAdministrator(ctx)) {
            ctx.replyErrorTr("cmd.xross.error.administrator");
            return;
        }

        CommandService commandService = ctx.getEngine().getServiceManager().getService(CommandService.class);
        if (commandService == null) {
            ctx.replyErrorTr("cmd.xross.error.service-unavailable");
            return;
        }

        if (("sync-global".equals(action) || "delete-global".equals(action)) && !isBotAdministrator(ctx)) {
            ctx.replyErrorTr("cmd.xross.error.bot-admin");
            return;
        }

        switch (action == null ? "" : action) {
            case "sync-global" -> {
                ctx.replyTrEphemeral("cmd.xross.response.sync-global");
                commandService.syncCommandsGlobal();
            }
            case "sync-guild" -> {
                if (ctx.getEvent().getGuild() == null) {
                    ctx.replyErrorTr("cmd.xross.error.guild-only");
                    return;
                }
                ctx.replyTrEphemeral("cmd.xross.response.sync-guild");
                commandService.syncCommandsGuild(ctx.getEvent().getGuild());
            }
            case "delete-guild" -> {
                if (ctx.getEvent().getGuild() == null) {
                    ctx.replyErrorTr("cmd.xross.error.guild-only");
                    return;
                }
                if (!canDelete(ctx) || !confirmed(ctx)) return;
                deleteCommands(ctx, commandService.deleteCommandsGuild(ctx.getEvent().getGuild()),
                        "cmd.xross.response.delete-guild");
            }
            case "delete-global" -> {
                if (!canDelete(ctx) || !confirmed(ctx)) return;
                deleteCommands(ctx, commandService.deleteCommandsGlobal(),
                        "cmd.xross.response.delete-global");
            }
            default -> ctx.replyErrorTr("cmd.xross.error.unknown-action", action);
        }
    }

    private static boolean isGuildAdministrator(CommandContext ctx) {
        return ctx.getEvent().getMember() != null
                && ctx.getEvent().getMember().hasPermission(Permission.ADMINISTRATOR);
    }

    private static boolean isBotAdministrator(CommandContext ctx) {
        XrossConsoleService console = ctx.getEngine().getServiceManager().getService(XrossConsoleService.class);
        return console != null && console.isAdministrator(ctx.getEvent().getUser().getIdLong());
    }

    private static boolean canDelete(CommandContext ctx) {
        XrossConsoleService console = ctx.getEngine().getServiceManager().getService(XrossConsoleService.class);
        if (console != null && console.isAdministrator(ctx.getEvent().getUser().getIdLong())) return true;
        ctx.replyErrorTr("cmd.xross.error.bot-admin");
        return false;
    }

    private static boolean confirmed(CommandContext ctx) {
        if (ctx.getOption("confirm") != null && ctx.getOption("confirm").getAsBoolean()) return true;
        ctx.replyErrorTr("cmd.xross.error.confirm-required");
        return false;
    }

    private static void deleteCommands(CommandContext ctx, java.util.concurrent.CompletableFuture<Integer> deletion,
                                       String successKey) {
        ctx.getEvent().deferReply(true).queue(hook -> deletion.whenComplete((count, failure) -> {
            if (failure == null) hook.editOriginal(ctx.tr(successKey, count)).queue();
            else hook.editOriginal(ctx.tr("cmd.xross.error.delete-failed")).queue();
        }));
    }
}
