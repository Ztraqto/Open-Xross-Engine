package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.EditorSessionService;

public class EditorCommand extends SlashCommand {

    public static final String RELOAD_BUTTON_ID = EditorLinkResponse.RELOAD_GUILD;

    public EditorCommand() {
        this("editor", "Open the static Xross Editor for this Discord server");
    }

    protected EditorCommand(String name, String description) {
        super(name, description);
        setDefaultMemberPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
        setHelp("/" + name, "Creates a signed, time-limited Editor link containing this server's configurable settings.");
    }

    @Override
    public void execute(CommandContext context) {
        if (context.getEvent().getGuild() == null || !canManageServer(context.getEvent().getMember())) {
            context.replyErrorTr("cmd.editor.error.manage-server");
            return;
        }
        EditorSessionService editor = context.getEngine().getServiceManager().getService(EditorSessionService.class);
        if (!isPngTransportReady(editor)) {
            context.replyErrorTr("cmd.editor.error.not-configured");
            return;
        }
        try {
            EditorAttachmentCodec.EncryptedAttachment attachment = editor.createGuildEditorAttachment(
                    context.getEvent().getGuild(), context.getEvent().getUser().getIdLong());
            context.getEvent().deferReply(true).queue(hook -> EditorLinkResponse.publish(
                    hook, editor, attachment,
                    context.tr("cmd.editor.response.open"),
                    context.tr("cmd.editor.response.open"),
                    context.tr("cmd.editor.button.open"),
                    context.tr("cmd.editor.button.reload"),
                    EditorLinkResponse.RELOAD_GUILD,
                    context.tr("cmd.editor.error.transport")
            ));
        } catch (Exception exception) {
            context.replyError(exception.getMessage());
        }
    }

    public static boolean isPngTransportReady(EditorSessionService editor) {
        return editor != null && editor.isEnabled() && editor.isAttachmentTransportEnabled() && editor.hasAttachmentChannel();
    }

    private static boolean canManageServer(Member member) {
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }
}
