package com.ztraqto.openxross.core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.XrossConsoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class EngineEventListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EngineEventListener.class);
    private final XrossEngine engine;

    // 遅延取得用キャッシュ
    private CommandService commandService;

    public EngineEventListener(XrossEngine engine) {
        this.engine = engine;
    }

    private CommandService getCommandService() {
        if (commandService == null) {
            commandService = engine.getServiceManager().getService(CommandService.class);
        }
        return commandService;
    }


    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (getCommandService() != null) {
            getCommandService().dispatch(event);
        } else {
            event.reply("System is starting up...").setEphemeral(true).queue();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw();

        // ---------------------------------------------------------
        // システム管理コマンド (!xross) のハンドリング
        // ---------------------------------------------------------
        if (content.startsWith("!xross")) {
            handleSystemCommand(event);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (!componentId.startsWith("xpa:")) {
            return;
        }

        XrossConsoleService console = engine.getServiceManager().getService(XrossConsoleService.class);
        if (console == null || !console.isAdministrator(event.getUser().getIdLong())) {
            event.reply("プラグインを承認できるのは設定済みのBot管理者だけです。")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (console.getChannelId() != event.getChannel().getIdLong()) {
            event.reply("プラグイン承認は設定済みのXrossコンソールチャンネルでのみ実行できます。")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if ("xpa:all".equals(componentId)) {
            event.reply("一括承認はOpenXrossEngine 1.4.1で無効化されました。各Pluginを個別に確認してください。")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String[] parts = componentId.split(":", 3);
        if (parts.length != 3 || !("a".equals(parts[1]) || "d".equals(parts[1]))) {
            event.reply("プラグイン承認操作が不正です。").setEphemeral(true).queue();
            return;
        }
        String requestId = parts[2];
        XrossConsoleService.ApprovalTarget target = console.resolveApprovalRequest(requestId);
        if (target == null) {
            event.reply("この承認要求は既に処理済みか無効です。").setEphemeral(true).queue();
            return;
        }
        boolean approved = "a".equals(parts[1]);
        event.deferReply(true).queue(hook -> CompletableFuture
                .supplyAsync(() -> approved
                        ? engine.getPluginManager().approvePending(target.pluginId(), target.fingerprint(), event.getUser().getIdLong())
                        : engine.getPluginManager().denyPending(target.pluginId(), target.fingerprint(), event.getUser().getIdLong()))
                .whenComplete((handled, error) -> {
                    if (error != null) {
                        logger.error("Plugin approval operation failed for " + target.pluginId() + ".", error);
                        hook.editOriginal("プラグイン承認処理中にエラーが発生しました。ログを確認してください。").queue();
                        return;
                    }
                    if (!handled) {
                        hook.editOriginal("この承認要求は既に処理済みです。").queue();
                        return;
                    }
                    console.completeApprovalRequest(requestId, approved);
                    editApprovalMessage(event, approved);
                    hook.editOriginal(approved ? "プラグインを承認しました。" : "プラグインを拒否しました。").queue();
                }), failure -> logger.warn("Could not defer plugin approval interaction.", failure));
    }

    private void editApprovalMessage(ButtonInteractionEvent event, boolean approved) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(approved ? "✅ プラグインを承認しました" : "⛔ プラグインを拒否しました");
        embed.setColor(approved ? new Color(46, 204, 113) : new Color(231, 76, 60));
        embed.addField(
                "処理結果",
                event.getUser().getAsMention() + (approved ? " がこのプラグインを承認しました。" : " がこのプラグインを拒否しました。"),
                false
        );
        embed.setFooter("処理済み • 承認ボタンは無効化されました");
        embed.setTimestamp(Instant.now());
        event.getMessage().editMessageComponents(com.ztraqto.openxross.api.components.EmbedContainer.of(embed.build()))
                .useComponentsV2(true)
                .queue(
                        ignored -> { },
                        failure -> logger.warn("Could not update processed plugin approval message.", failure)
                );
    }

    /**
     * 手動トリガーによるコマンド同期処理
     */
    private void handleSystemCommand(MessageReceivedEvent event) {
        // セキュリティチェック: 管理者権限を持つユーザーのみ実行可能
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            return; // 無視する（反応するとスパムになる可能性があるため）
        }

        String[] args = event.getMessage().getContentRaw().split("\\s+");
        if (args.length < 2) {
            event.getChannel().sendMessage("Usage: `!xross <sync-global | sync-guild>`").queue();
            return;
        }

        String action = args[1].toLowerCase();
        CommandService cmdService = getCommandService();

        if (cmdService == null) {
            event.getChannel().sendMessage("❌ CommandService is not available.").queue();
            return;
        }

        switch (action) {
            case "sync-global":
                XrossConsoleService console = engine.getServiceManager().getService(XrossConsoleService.class);
                if (console == null || !console.isAdministrator(event.getAuthor().getIdLong())) {
                    event.getChannel().sendMessage("❌ Global command synchronization is restricted to Bot administrators.").queue();
                    return;
                }
                event.getChannel().sendMessage("🔄 **Syncing Global Commands...** (This may take up to 1 hour)").queue();
                cmdService.syncCommandsGlobal();
                break;

            case "sync-guild":
                if (!event.isFromGuild()) {
                    event.getChannel().sendMessage("❌ This command must be used in a server (Guild).").queue();
                    return;
                }
                event.getChannel().sendMessage("🔄 **Syncing Guild Commands...** (Instant update)").queue();
                cmdService.syncCommandsGuild(event.getGuild());
                break;

            default:
                event.getChannel().sendMessage("Unknown action: " + action).queue();
                break;
        }
    }
}
