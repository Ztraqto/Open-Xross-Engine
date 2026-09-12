package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.HelpInfo;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.VoiceService;

import java.awt.Color;
import java.util.Collection;
import java.util.stream.Collectors;

public class VolumeCommand extends SlashCommand {
    public VolumeCommand() {
        super("volume", "Change voice volume for this server");
        setDefaultMemberPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
        addOption(new OptionData(OptionType.INTEGER, "level", "Volume level (0-100)", true)
            .setMinValue(0).setMaxValue(100));
        setHelp("/volume <0-100>", "このサーバーでの再生音量を設定します。");
    }

    @Override
    public void execute(CommandContext ctx) {
        // 権限チェック (管理者のみ)
        if (!ctx.getEvent().getMember().hasPermission(Permission.ADMINISTRATOR)) {
            ctx.replyErrorTr("cmd.volume.error.administrator");
            return;
        }

        int level = ctx.getOption("level").getAsInt();
        VoiceService svc = ctx.getEngine().getServiceManager().getService(VoiceService.class);
        
        svc.setGuildVolume(ctx.getEvent().getGuild().getIdLong(), level);
        
        ctx.replyTr("cmd.volume.response.success", level);
    }
}
