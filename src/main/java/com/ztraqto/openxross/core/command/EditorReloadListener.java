package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.EditorSessionService;
import com.ztraqto.openxross.service.LocaleService;

/** Replaces the active PNG-backed Editor session for guild, user, or Bot-administrator scope. */
public final class EditorReloadListener extends ListenerAdapter {
    private final XrossEngine engine;

    public EditorReloadListener(XrossEngine engine) {
        this.engine = engine;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals(EditorLinkResponse.RELOAD_GUILD)
                && !id.equals(EditorLinkResponse.RELOAD_USER)
                && !id.equals(EditorLinkResponse.RELOAD_ADMIN)) return;

        EditorSessionService editor = engine.getServiceManager().getService(EditorSessionService.class);
        if (!EditorCommand.isPngTransportReady(editor)) {
            event.reply(tr(event, "cmd.editor.error.not-configured")).setEphemeral(true).queue();
            return;
        }
        try {
            EditorAttachmentCodec.EncryptedAttachment attachment;
            if (id.equals(EditorLinkResponse.RELOAD_GUILD)) {
                if (event.getGuild() == null || event.getMember() == null
                        || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                    event.reply(tr(event, "cmd.editor.error.manage-server")).setEphemeral(true).queue();
                    return;
                }
                attachment = editor.createGuildEditorAttachment(event.getGuild(), event.getUser().getIdLong());
            } else if (id.equals(EditorLinkResponse.RELOAD_ADMIN)) {
                if (!engine.getConfiguration().botAdministratorIds().contains(event.getUser().getIdLong())) {
                    event.reply(tr(event, "help.admin-editor.not-authorized")).setEphemeral(true).queue();
                    return;
                }
                attachment = editor.createAdminEditorAttachment(event.getUser().getIdLong());
            } else {
                attachment = editor.createUserEditorAttachment(
                        event.getUser().getIdLong(), event.getUser().getEffectiveName());
            }

            String reloadId = id;
            event.deferReply(true).queue(hook -> EditorLinkResponse.publish(
                    hook, editor, attachment,
                    tr(event, "cmd.editor.response.reloaded"),
                    tr(event, "cmd.editor.response.open"),
                    tr(event, "cmd.editor.button.open"),
                    tr(event, "cmd.editor.button.reload"),
                    reloadId,
                    tr(event, "cmd.editor.error.transport")
            ));
        } catch (Exception exception) {
            event.reply(exception.getMessage()).setEphemeral(true).queue();
        }
    }

    private String tr(ButtonInteractionEvent event, String key, Object... args) {
        LocaleService locale = engine.getServiceManager().getService(LocaleService.class);
        long guildId = event.getGuild() == null ? 0L : event.getGuild().getIdLong();
        return locale == null ? key : locale.getSystemText(guildId, event.getUser().getIdLong(), key, args);
    }
}
