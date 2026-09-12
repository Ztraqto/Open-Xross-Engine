package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.EditorSessionService;

/** Opens the PNG-backed Bot-wide administration Editor for configured Xross administrators only. */
public final class XrossAdminEditorCommand extends SlashCommand {
    public XrossAdminEditorCommand() {
        super("xross-admin-editor", "Open the Xross administrator-only Editor");
        setDefaultMemberPermissions(DefaultMemberPermissions.DISABLED);
        setHelp("/xross-admin-editor", "Opens the Bot-wide administrator-only editor.");
    }

    @Override
    public void execute(CommandContext context) {
        long userId = context.getEvent().getUser().getIdLong();
        if (!context.getEngine().getConfiguration().botAdministratorIds().contains(userId)) {
            context.replyError("This command is restricted to Xross Bot administrators.");
            return;
        }
        EditorSessionService editor = context.getEngine().getServiceManager().getService(EditorSessionService.class);
        if (!EditorCommand.isPngTransportReady(editor)) {
            context.replyError("Xross Editor PNG session transport is not configured.");
            return;
        }
        try {
            EditorAttachmentCodec.EncryptedAttachment attachment = editor.createAdminEditorAttachment(userId);
            context.getEvent().deferReply(true).queue(hook -> EditorLinkResponse.publish(
                    hook, editor, attachment,
                    "Xross 管理者専用画面",
                    "認定制度とBot全体の制限設定を管理します。",
                    context.tr("cmd.editor.button.open"),
                    context.tr("cmd.editor.button.reload"),
                    EditorLinkResponse.RELOAD_ADMIN,
                    context.tr("cmd.editor.error.transport")
            ));
        } catch (Exception exception) {
            context.replyError(exception.getMessage());
        }
    }
}
