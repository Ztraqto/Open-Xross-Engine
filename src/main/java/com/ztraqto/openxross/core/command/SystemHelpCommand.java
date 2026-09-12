package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import com.ztraqto.openxross.api.command.CommandCatalogEntry;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.service.CommandService;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SystemHelpCommand extends SlashCommand {
    private static final int FIELDS_PER_EMBED = 20;
    private static final String ENGINE_CREDIT = "Powered by Open Xross Engine";

    public SystemHelpCommand() {
        super("help", "Show help for commands managed by Xross Engine");
        addOption(new OptionData(OptionType.STRING, "cmd", "Command name to view details", false));
        setHelp("/help [cmd]", "Show all registered commands or detailed help for one command.");
    }

    @Override
    public void execute(CommandContext ctx) {
        CommandService commandService = ctx.getEngine().getServiceManager().getService(CommandService.class);
        if (commandService == null) {
            ctx.replyErrorTr("cmd.help.error.service-unavailable");
            return;
        }
        String targetCommandName = ctx.getOptionAsString("cmd");
        if (targetCommandName == null) {
            showHome(ctx);
            return;
        }
        showCommandDetail(ctx, commandService, targetCommandName);
    }

    private void showHome(CommandContext ctx) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.primary("xhelp:commands", ctx.tr("help.home.commands.button")));
        buttons.add(Button.secondary("xhelp:privacy", "プライバシーポリシー"));
        buttons.add(Button.secondary("xhelp:user-editor", ctx.tr("help.home.user-editor.button")));
        if (ctx.getEvent().getGuild() != null && ctx.getEvent().getMember() != null
                && ctx.getEvent().getMember().hasPermission(Permission.MANAGE_SERVER)) {
            buttons.add(Button.success("xhelp:guild-editor", ctx.tr("help.home.guild-editor.button")));
        }
        if (ctx.getEngine().getConfiguration().botAdministratorIds().contains(ctx.getEvent().getUser().getIdLong())) {
            buttons.add(Button.danger("xhelp:xross-admin-editor", ctx.tr("help.home.admin-editor.button")));
        }
        Container helpCard = Container.of(
                TextDisplay.of("# " + ctx.getProductName() + " " + ctx.tr("help.title")),
                TextDisplay.of(ctx.tr("help.home.description") + productLinks(ctx)),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("**" + ctx.tr("help.home.commands.title") + "**\n" + ctx.tr("help.home.commands.description")),
                TextDisplay.of("**" + ctx.tr("help.home.editor.title") + "**\n" + ctx.tr("help.home.editor.description")),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(buttons)
        ).withAccentColor(new Color(88, 101, 242));

        Container engineCredit = Container.of(
                TextDisplay.of("-# " + ENGINE_CREDIT)
        ).withAccentColor(new Color(88, 101, 242));

        ctx.getEvent().replyComponents(helpCard, engineCredit)
                .useComponentsV2(true).setEphemeral(true).queue();
    }

    private void showCommandDetail(CommandContext ctx, CommandService commandService, String requestedName) {
        String name = requestedName.trim();
        if (name.startsWith("/")) name = name.substring(1);
        CommandCatalogEntry command = commandService.getCommandCatalogEntry(name, guildId(ctx), ctx.getEvent().getUser().getIdLong()).orElse(null);
        if (command == null) {
            ctx.replyError(ctx.tr("error.not-found") + ": " + name);
            return;
        }
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(ctx.getProductName() + " " + ctx.tr("help.title")
                        + ": " + displayCommandName(command))
                .setColor(new Color(0, 204, 102))
                .setDescription(command.description())
                .setFooter(ctx.tr("help.provided-by") + ": " + command.pluginName());
        if (command.usage() != null && !command.usage().isBlank()) embed.addField(ctx.tr("help.usage"), "`" + command.usage() + "`", false);
        if (command.detail() != null && !command.detail().isBlank()) embed.addField(ctx.tr("help.detail"), command.detail(), false);
        if (!command.examples().isEmpty()) {
            embed.addField(ctx.tr("help.examples"), command.examples().stream().map(example -> "- `" + example + "`").collect(Collectors.joining("\n")), false);
        }
        var engineCredit = new EmbedBuilder()
                .setDescription(ENGINE_CREDIT)
                .setColor(new Color(88, 101, 242))
                .build();
        ctx.getEvent().replyEmbeds(embed.build(), engineCredit).setEphemeral(true).queue();
    }

    private String displayCommandName(CommandCatalogEntry command) {
        return command.contextMenu() ? command.name() : "/" + command.name();
    }

    private static long guildId(CommandContext ctx) {
        return ctx.getEvent().getGuild() == null ? 0L : ctx.getEvent().getGuild().getIdLong();
    }

    private static String productLinks(CommandContext ctx) {
        var product = ctx.getEngine().getConfiguration().product();
        List<String> links = new ArrayList<>();
        if (product.homepageUrl() != null) links.add("[ホームページ](" + product.homepageUrl() + ")");
        if (product.privacyPolicyUrl() != null) links.add("[プライバシー](" + product.privacyPolicyUrl() + ")");
        if (product.termsUrl() != null) links.add("[利用規約](" + product.termsUrl() + ")");
        return links.isEmpty() ? "" : "\n" + String.join(" ・ ", links);
    }
}
