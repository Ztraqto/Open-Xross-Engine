package com.ztraqto.openxross.api.components;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.section.SectionContentComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Converts a classic Discord embed and its controls into one Components V2 container. */
public final class EmbedContainer {

    private static final int TEXT_CHUNK_LENGTH = 3_900;

    private EmbedContainer() {
    }

    public static Container of(MessageEmbed embed, ActionRow... controls) {
        return of(embed, List.of(controls));
    }

    public static Container of(MessageEmbed embed, Collection<? extends ActionRow> controls) {
        return ofEmbeds(List.of(Objects.requireNonNull(embed, "embed")), controls);
    }

    public static Container ofEmbeds(Collection<? extends MessageEmbed> embeds, ActionRow... controls) {
        return ofEmbeds(embeds, List.of(controls));
    }

    public static Container ofEmbeds(
            Collection<? extends MessageEmbed> embeds,
            Collection<? extends ActionRow> controls
    ) {
        Objects.requireNonNull(embeds, "embeds");
        List<ContainerChildComponent> children = new ArrayList<>();
        MessageEmbed accentSource = null;
        for (MessageEmbed embed : embeds) {
            if (embed == null) continue;
            if (accentSource == null && embed.getColor() != null) accentSource = embed;
            if (!children.isEmpty()) children.add(Separator.createDivider(Separator.Spacing.LARGE));
            appendEmbed(children, embed);
        }

        if (controls != null && !controls.isEmpty()) {
            if (!children.isEmpty()) children.add(Separator.createDivider(Separator.Spacing.SMALL));
            controls.stream().filter(Objects::nonNull).forEach(children::add);
        }

        if (children.isEmpty()) children.add(TextDisplay.of("\u200B"));
        Container container = Container.of(children);
        return accentSource == null ? container : container.withAccentColor(accentSource.getColor());
    }

    /** Returns the existing component tree with one custom-id button disabled. */
    public static MessageComponentTree disableButton(Message message, String customId) {
        return disableButton(message, customId, null);
    }

    /** Returns the existing component tree with one custom-id button disabled and optionally relabelled. */
    public static MessageComponentTree disableButton(Message message, String customId, String label) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(customId, "customId");
        ComponentReplacer replacer = ComponentReplacer.of(
                Button.class,
                button -> customId.equals(button.getCustomId()),
                button -> label == null ? button.asDisabled() : button.asDisabled().withLabel(label)
        );
        return message.getComponentTree().replace(replacer);
    }

    private static void appendEmbed(List<ContainerChildComponent> children, MessageEmbed embed) {
        List<TextDisplay> primaryText = buildPrimaryText(embed);

        if (embed.getThumbnail() != null && embed.getThumbnail().getUrl() != null && !primaryText.isEmpty()) {
            int sectionSize = Math.min(primaryText.size(), Section.MAX_COMPONENTS);
            List<SectionContentComponent> sectionText = new ArrayList<>(primaryText.subList(0, sectionSize));
            children.add(Section.of(Thumbnail.fromUrl(embed.getThumbnail().getUrl()), sectionText));
            children.addAll(primaryText.subList(sectionSize, primaryText.size()));
        } else {
            children.addAll(primaryText);
        }

        for (MessageEmbed.Field field : embed.getFields()) {
            String name = blankToNull(field.getName());
            String value = blankToNull(field.getValue());
            if (name == null && value == null) continue;
            String content = (name == null ? "" : "**" + name + "**\n") + (value == null ? "-" : value);
            addText(children, content);
        }

        if (embed.getImage() != null && embed.getImage().getUrl() != null) {
            children.add(MediaGallery.of(MediaGalleryItem.fromUrl(embed.getImage().getUrl())));
        }

        String footer = embed.getFooter() == null ? null : blankToNull(embed.getFooter().getText());
        OffsetDateTime timestamp = embed.getTimestamp();
        if (footer != null || timestamp != null) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            StringBuilder text = new StringBuilder("-# ");
            if (footer != null) text.append(footer);
            if (footer != null && timestamp != null) text.append(" • ");
            if (timestamp != null) text.append("<t:").append(timestamp.toEpochSecond()).append(":f>");
            addText(children, text.toString());
        }

    }

    private static List<TextDisplay> buildPrimaryText(MessageEmbed embed) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        if (embed.getAuthor() != null && blankToNull(embed.getAuthor().getName()) != null) {
            header.append("**").append(embed.getAuthor().getName()).append("**");
        }
        String title = blankToNull(embed.getTitle());
        if (title != null) {
            if (!header.isEmpty()) header.append("\n");
            String url = blankToNull(embed.getUrl());
            header.append("## ");
            if (url == null) header.append(title);
            else header.append('[').append(title).append("](").append(url).append(')');
        }
        String description = blankToNull(embed.getDescription());
        if (description != null) {
            if (!header.isEmpty()) header.append("\n");
            header.append(description);
        }
        addText(parts, header.toString());
        List<TextDisplay> result = new ArrayList<>();
        for (ContainerChildComponent part : parts) result.add((TextDisplay) part);
        return result;
    }

    private static void addText(List<ContainerChildComponent> target, String content) {
        String text = blankToNull(content);
        if (text == null) return;
        for (int start = 0; start < text.length(); start += TEXT_CHUNK_LENGTH) {
            int end = Math.min(text.length(), start + TEXT_CHUNK_LENGTH);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            target.add(TextDisplay.of(text.substring(start, end)));
            start = end - TEXT_CHUNK_LENGTH;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
