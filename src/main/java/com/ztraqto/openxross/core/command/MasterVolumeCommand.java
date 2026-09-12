package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.service.VoiceService;
import com.ztraqto.openxross.service.XrossConsoleService;

public final class MasterVolumeCommand extends SlashCommand {

    public MasterVolumeCommand() {
        super("master-volume", "[Owner Only] Set global volume limit");
        setDefaultMemberPermissions(DefaultMemberPermissions.DISABLED);
        addOption(new OptionData(OptionType.INTEGER, "level", "Master Volume (0-100)", true)
                .setMinValue(0)
                .setMaxValue(100));
    }

    @Override
    public void execute(CommandContext context) {
        XrossConsoleService administrators = context.getEngine().getServiceManager()
                .getService(XrossConsoleService.class);
        if (administrators == null || !administrators.isAdministrator(context.getEvent().getUser().getIdLong())) {
            context.replyErrorTr("cmd.master-volume.error.bot-admin");
            return;
        }

        int level = context.getOption("level").getAsInt();
        VoiceService voice = context.getEngine().getServiceManager().getService(VoiceService.class);
        voice.setMasterVolume(level);
        context.replyTr("cmd.master-volume.response.success", level);
    }
}
