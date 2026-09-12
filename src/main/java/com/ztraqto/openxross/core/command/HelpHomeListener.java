package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.command.CommandCatalogEntry;
import com.ztraqto.openxross.api.components.EmbedContainer;
import com.ztraqto.openxross.core.editor.EditorAttachmentCodec;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.EditorSessionService;
import com.ztraqto.openxross.service.LocaleService;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Handles the ephemeral, Components V2 action buttons rendered by /help. */
public final class HelpHomeListener extends ListenerAdapter {
    private static final int PLUGINS_PER_PAGE = 10;
    private static final String PAGE_PREFIX = "xhelp:commands:";

    private final XrossEngine engine;
    private volatile Map<String, String> mentionCache = Map.of();
    private volatile long mentionCacheExpiresAt;

    public HelpHomeListener(XrossEngine engine) {
        this.engine = engine;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        switch (event.getComponentId()) {
            case "xhelp:commands" ->
                    event.deferReply(true).queue(hook -> showCommandList(event, 0, hook));
            case "xhelp:user-editor" -> openUserEditor(event);
            case "xhelp:guild-editor" -> openGuildEditor(event);
            case "xhelp:xross-admin-editor" -> openAdministratorEditor(event);
            case "xhelp:privacy" ->
                    event.replyEmbeds(PrivacyPolicyCommand.createEmbed(
                                    engine.getConfiguration().product(), engine.getProductName()))
                            .setEphemeral(true).queue();
            default -> handleCommandPage(event);
        }
    }

    private void handleCommandPage(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (!componentId.startsWith(PAGE_PREFIX)) return;
        try {
            int page = Integer.parseInt(componentId.substring(componentId.lastIndexOf(':') + 1));
            // Acknowledge before command-mention REST lookup. Discord otherwise expires the interaction.
            event.deferEdit().queue(hook -> showCommandList(event, page, hook));
        } catch (NumberFormatException exception) {
            event.reply("コマンド一覧のページ情報が壊れています。もう一度 `/help` を実行してください。")
                    .setEphemeral(true).queue();
        }
    }

    private void showCommandList(ButtonInteractionEvent event, int requestedPage, InteractionHook hook) {
        CommandService service = engine.getServiceManager().getService(CommandService.class);
        if (service == null) {
            hook.editOriginal(tr(event, "cmd.help.error.service-unavailable")).queue();
            return;
        }

        List<CommandCatalogEntry> catalog =
                service.getCommandCatalog(guildId(event), event.getUser().getIdLong());
        Map<String, List<CommandCatalogEntry>> grouped = catalog.stream().collect(Collectors.groupingBy(
                CommandCatalogEntry::pluginId, LinkedHashMap::new, Collectors.toList()));
        LinkedHashMap<String, String> pluginNames = new LinkedHashMap<>();
        if (grouped.containsKey("XROSS-SYSTEM")) {
            pluginNames.put("XROSS-SYSTEM", tr(event, "help.system"));
        }
        catalog.forEach(entry -> pluginNames.putIfAbsent(entry.pluginId(), entry.pluginName()));

        List<Map.Entry<String, String>> plugins = new ArrayList<>(pluginNames.entrySet());
        int pages = Math.max(1, (plugins.size() + PLUGINS_PER_PAGE - 1) / PLUGINS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Map<String, String> mentions = commandMentions(event);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(engine.getProductName() + " " + tr(event, "help.cmd-list")
                        + " (" + (page + 1) + "/" + pages + ")")
                .setDescription(page == 0
                        ? "コマンドのメンションを押すと、Discordの入力欄へそのコマンドを挿入できます。"
                        : null)
                .setColor(new Color(0, 153, 255));

        int start = page * PLUGINS_PER_PAGE;
        for (int index = start; index < Math.min(start + PLUGINS_PER_PAGE, plugins.size()); index++) {
            Map.Entry<String, String> plugin = plugins.get(index);
            String commands = grouped.getOrDefault(plugin.getKey(), List.of()).stream()
                    .map(entry -> displayCommand(entry, mentions))
                    .collect(Collectors.joining("  "));
            embed.addField(plugin.getValue(),
                    commands.isBlank() ? tr(event, "help.no-commands") : commands, false);
        }

        List<String> navigationIds = paginationComponentIds(page, pages);
        ActionRow navigation = ActionRow.of(
                Button.secondary(navigationIds.get(0), tr(event, "help.pagination.first"))
                        .withDisabled(page == 0),
                Button.secondary(navigationIds.get(1),
                                tr(event, "help.pagination.previous"))
                        .withDisabled(page == 0),
                Button.secondary(navigationIds.get(2),
                                tr(event, "help.pagination.next"))
                        .withDisabled(page + 1 >= pages),
                Button.secondary(navigationIds.get(3), tr(event, "help.pagination.last"))
                        .withDisabled(page + 1 >= pages)
        );
        Container content = EmbedContainer.of(embed.build(), navigation);
        hook.editOriginalComponents(content).useComponentsV2(true).queue();
    }

    private synchronized Map<String, String> commandMentions(ButtonInteractionEvent event) {
        long now = System.currentTimeMillis();
        if (mentionCacheExpiresAt > now && !mentionCache.isEmpty()) return mentionCache;

        Map<String, String> mentions = new HashMap<>();
        try {
            event.getJDA().retrieveCommands().complete().forEach(command ->
                    mentions.put(command.getName(), "</" + command.getName() + ":" + command.getId() + ">"));
        } catch (Exception ignored) {
        }
        if (event.getGuild() != null) {
            try {
                event.getGuild().retrieveCommands().complete().forEach(command ->
                        mentions.put(command.getName(), "</" + command.getName() + ":" + command.getId() + ">"));
            } catch (Exception ignored) {
            }
        }
        mentionCache = Map.copyOf(mentions);
        mentionCacheExpiresAt = now + 5 * 60_000L;
        return mentionCache;
    }

    private String displayCommand(CommandCatalogEntry entry, Map<String, String> mentions) {
        if (entry.contextMenu()) return "`" + entry.name() + "`";
        return mentions.getOrDefault(entry.name(), "`/" + entry.name() + "`");
    }

    private void openUserEditor(ButtonInteractionEvent event) {
        EditorSessionService editor = getEditor(event);
        if (editor == null) return;
        try {
            openAttachmentEditor(event, editor,
                    editor.createUserEditorAttachment(
                            event.getUser().getIdLong(), event.getUser().getEffectiveName()),
                    EditorLinkResponse.RELOAD_USER);
        } catch (Exception exception) {
            event.reply(tr(event, "help.editor.create-failed")).setEphemeral(true).queue();
        }
    }

    private void openGuildEditor(ButtonInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null
                || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply(tr(event, "help.editor.manage-server-required")).setEphemeral(true).queue();
            return;
        }
        EditorSessionService editor = getEditor(event);
        if (editor == null) return;
        try {
            openAttachmentEditor(event, editor,
                    editor.createGuildEditorAttachment(event.getGuild(), event.getUser().getIdLong()),
                    EditorLinkResponse.RELOAD_GUILD);
        } catch (Exception exception) {
            event.reply(tr(event, "help.editor.create-failed")).setEphemeral(true).queue();
        }
    }

    private void openAdministratorEditor(ButtonInteractionEvent event) {
        if (!engine.getConfiguration().botAdministratorIds().contains(event.getUser().getIdLong())) {
            event.reply(tr(event, "help.admin-editor.not-authorized")).setEphemeral(true).queue();
            return;
        }
        EditorSessionService editor = getEditor(event);
        if (editor == null) return;
        try {
            openAttachmentEditor(event, editor,
                    editor.createAdminEditorAttachment(event.getUser().getIdLong()),
                    EditorLinkResponse.RELOAD_ADMIN);
        } catch (Exception exception) {
            event.reply(tr(event, "help.editor.create-failed")).setEphemeral(true).queue();
        }
    }

    private EditorSessionService getEditor(ButtonInteractionEvent event) {
        EditorSessionService editor = engine.getServiceManager().getService(EditorSessionService.class);
        if (!EditorCommand.isPngTransportReady(editor)) {
            event.reply(tr(event, "help.editor.not-configured")).setEphemeral(true).queue();
            return null;
        }
        return editor;
    }

    private void openAttachmentEditor(
            ButtonInteractionEvent event,
            EditorSessionService editor,
            EditorAttachmentCodec.EncryptedAttachment attachment,
            String reloadId
    ) {
        event.deferReply(true).queue(hook -> EditorLinkResponse.publish(
                hook, editor, attachment,
                tr(event, "help.editor.open"),
                tr(event, "help.editor.open"),
                tr(event, "help.editor.open-button"),
                tr(event, "cmd.editor.button.reload"),
                reloadId,
                tr(event, "help.editor.upload-failed")
        ));
    }

    private String tr(ButtonInteractionEvent event, String key, Object... args) {
        LocaleService locale = engine.getServiceManager().getService(LocaleService.class);
        return locale == null
                ? key
                : locale.getSystemText(guildId(event), event.getUser().getIdLong(), key, args);
    }

    private static long guildId(ButtonInteractionEvent event) {
        return event.getGuild() == null ? 0L : event.getGuild().getIdLong();
    }

    static List<String> paginationComponentIds(int page, int pages) {
        int lastPage = Math.max(0, pages - 1);
        int currentPage = Math.max(0, Math.min(page, lastPage));
        return List.of(
                PAGE_PREFIX + "first:0",
                PAGE_PREFIX + "previous:" + Math.max(0, currentPage - 1),
                PAGE_PREFIX + "next:" + Math.min(lastPage, currentPage + 1),
                PAGE_PREFIX + "last:" + lastPage
        );
    }
}
