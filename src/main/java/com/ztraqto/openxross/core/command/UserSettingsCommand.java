package com.ztraqto.openxross.core.command;

import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.EditorSessionService;

public final class UserSettingsCommand extends SlashCommand {

    public UserSettingsCommand() {
        super("user-settings", "Open the Xross Editor for your personal settings");
        setHelp("/user-settings", "Creates a signed, time-limited Editor link containing only your personal settings.");
    }

    @Override
    public void execute(CommandContext context) {
        EditorSessionService editor = context.getEngine().getServiceManager().getService(EditorSessionService.class);
        if (!EditorCommand.isPngTransportReady(editor)) {
            context.replyErrorTr("cmd.editor.error.not-configured");
            return;
        }
        try {
            long userId = context.getEvent().getUser().getIdLong();
            EditorAttachmentCodec.EncryptedAttachment attachment =
                    editor.createUserEditorAttachment(userId, context.getEvent().getUser().getEffectiveName());
            context.getEvent().deferReply(true).queue(hook -> EditorLinkResponse.publish(
                    hook, editor, attachment,
                    context.tr("cmd.editor.response.open"),
                    context.tr("cmd.editor.response.open"),
                    context.tr("cmd.editor.button.open"),
                    context.tr("cmd.editor.button.reload"),
                    EditorLinkResponse.RELOAD_USER,
                    context.tr("cmd.editor.error.transport")
            ));
        } catch (Exception exception) {
            context.replyError(exception.getMessage());
        }
    }
}
