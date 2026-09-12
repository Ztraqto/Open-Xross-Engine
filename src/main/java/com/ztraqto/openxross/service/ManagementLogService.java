package com.ztraqto.openxross.service;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.utils.FileUpload;

/** Shared, privacy-safe delivery path for Bot operational events. */
public final class ManagementLogService implements IService {
    private final GuildSettingsService settings;

    public ManagementLogService(GuildSettingsService settings) {
        this.settings = settings;
    }

    @Override public void init(XrossEngine engine) { }
    @Override public void shutdown() { }
    @Override public String getName() { return "ManagementLogService"; }

    public boolean isUsable(Guild guild, boolean attachments) {
        TextChannel channel = channel(guild);
        if (channel == null) return false;
        var self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) return false;
        return !attachments || self.hasPermission(channel, Permission.MESSAGE_ATTACH_FILES);
    }

    public CompletableFuture<Boolean> send(Guild guild, boolean mentionOwner, String title, String body) {
        return send(guild, mentionOwner, title, body, null, null);
    }

    public CompletableFuture<Boolean> send(Guild guild, boolean mentionOwner, String title, String body,
                                           byte[] attachment, String fileName) {
        TextChannel channel = channel(guild);
        if (channel == null || !isUsable(guild, attachment != null)) return CompletableFuture.completedFuture(false);
        String ownerMention = mentionOwner ? "<@" + guild.getOwnerId() + ">\n" : "";
        String safeTitle = safe(title, 100);
        String safeBody = safe(body, 3_500);
        Container card = Container.of(TextDisplay.of(ownerMention + "## " + safeTitle + "\n" + safeBody))
                .withAccentColor(mentionOwner ? new Color(220, 53, 69) : new Color(88, 101, 242));
        var action = channel.sendMessageComponents(card).useComponentsV2(true);
        if (mentionOwner) {
            action = action.setAllowedMentions(List.of(MentionType.USER))
                    .mentionUsers(List.of(guild.getOwnerId()));
        } else {
            action = action.setAllowedMentions(Collections.emptyList());
        }
        if (attachment != null && attachment.length > 0) {
            String safeFile = fileName == null || fileName.isBlank() ? "reported-image.bin"
                    : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
            action = action.addFiles(FileUpload.fromData(attachment, safeFile));
        }
        return action.submit().handle((ignored, failure) -> failure == null);
    }

    private TextChannel channel(Guild guild) {
        if (guild == null) return null;
        String value = settings.getString(guild.getIdLong(), GuildSettingsService.MANAGEMENT_LOG_CHANNEL_KEY);
        return value == null || !value.matches("[1-9][0-9]{5,24}") ? null : guild.getTextChannelById(value);
    }

    private static String safe(String value, int limit) {
        String text = value == null ? "" : value.replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere").replace("<@", "<@\u200b").trim();
        return text.length() <= limit ? text : text.substring(0, limit - 3) + "...";
    }
}
