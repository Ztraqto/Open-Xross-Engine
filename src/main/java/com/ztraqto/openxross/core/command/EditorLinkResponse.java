package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.interactions.InteractionHook;
import com.ztraqto.openxross.api.components.EmbedContainer;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.EditorSessionService;

import java.awt.Color;

/** One PNG-session transport and one Components V2 presentation for every Editor entry point. */
public final class EditorLinkResponse {
    public static final String RELOAD_GUILD = "xeditor:reload:guild";
    public static final String RELOAD_USER = "xeditor:reload:user";
    public static final String RELOAD_ADMIN = "xeditor:reload:admin";

    private EditorLinkResponse() { }

    public static void publish(
            InteractionHook hook,
            EditorSessionService editor,
            EditorAttachmentCodec.EncryptedAttachment attachment,
            String title,
            String description,
            String openLabel,
            String reloadLabel,
            String reloadId,
            String errorMessage
    ) {
        editor.publishAttachment(attachment).whenComplete((attachmentUrl, failure) -> {
            if (failure != null) {
                hook.editOriginal(errorMessage).queue();
                return;
            }
            try {
                String url = editor.createAttachmentEditorUri(attachmentUrl, attachment.key()).toString();
                if (url.length() > EditorSessionService.DISCORD_BUTTON_URL_LIMIT) {
                    hook.editOriginal(errorMessage).queue();
                    return;
                }
                hook.editOriginalComponents(card(title, description, url, openLabel, reloadLabel, reloadId))
                        .useComponentsV2(true).queue();
            } catch (Exception exception) {
                hook.editOriginal(errorMessage).queue();
            }
        });
    }

    public static Container card(String title, String description, String url, String openLabel, String reloadLabel, String reloadId) {
        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(88, 101, 242)).setTitle(title).setDescription(description);
        return EmbedContainer.of(embed.build(), ActionRow.of(
                Button.link(url, openLabel),
                Button.secondary(reloadId, reloadLabel)
        ));
    }
}
