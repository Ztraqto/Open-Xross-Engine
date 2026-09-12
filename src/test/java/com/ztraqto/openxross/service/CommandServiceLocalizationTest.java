package com.ztraqto.openxross.service;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.core.command.XrossConsoleCommand;
import com.ztraqto.openxross.core.command.XrossSystemCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandServiceLocalizationTest {

    @Test
    void usesJapaneseAsNativeTextAndRegistersEnglishLocalization() {
        GuildSettingsService settings = new GuildSettingsService(null);
        LocaleService locales = new LocaleService(settings, new UserSettingsService(null));
        locales.init(null);
        try {
            CommandService service = new CommandService(null, locales);
            service.register(null, new XrossConsoleCommand());

            var command = service.buildCommandData().get(0);
            assertEquals("非公開のXross Engineコンソールを設定します", command.getDescription());
            assertEquals(
                    "Configure the private Xross Engine Discord console",
                    command.getDescriptionLocalizations().get(DiscordLocale.ENGLISH_US)
            );
            var action = command.getOptions().get(0);
            assertEquals("コンソールに対して行う操作", action.getDescription());
            // Discord slash-command option names remain API-safe ASCII identifiers;
            // their user-facing descriptions and choices carry localization.
            assertEquals("action", action.getName());
            assertEquals("このチャンネルに接続", action.getChoices().get(0).getName());
            assertEquals(
                    "Bind this channel",
                    action.getChoices().get(0).getNameLocalizations().get(DiscordLocale.ENGLISH_US)
            );
        } finally {
            locales.shutdown();
        }
    }

    @Test
    void includesListenerBackedCommandsInTheCommandCatalog() {
        CommandService service = new CommandService(null, null);
        service.register(null, new XrossConsoleCommand());
        service.register(null, Commands.slash("legacy", "Legacy listener-backed command"));

        var catalog = service.getCommandCatalog(0L);
        assertEquals(2, catalog.size());
        assertTrue(catalog.stream().anyMatch(command ->
                command.name().equals("legacy")
                        && command.description().equals("Legacy listener-backed command")
                        && command.usage().equals("/legacy")
        ));
    }

    @Test
    void xrossCommandUsesRealSubcommandsIncludingVersionAndConfirmedDeletion() {
        CommandService service = new CommandService(null, null);
        service.register(null, new XrossSystemCommand());

        var command = service.buildCommandData().get(0);
        assertTrue(command.getSubcommands().stream().anyMatch(sub -> sub.getName().equals("version")));
        var deleteGuild = command.getSubcommands().stream().filter(sub -> sub.getName().equals("delete-guild")).findFirst().orElseThrow();
        var deleteGlobal = command.getSubcommands().stream().filter(sub -> sub.getName().equals("delete-global")).findFirst().orElseThrow();
        assertEquals(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN, deleteGuild.getOptions().get(0).getType());
        assertEquals(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN, deleteGlobal.getOptions().get(0).getType());
    }

    @Test
    void registersSubcommandGroupsForPluginCommands() {
        CommandService service = new CommandService(null, null);
        service.register(null, new GroupedCommand());

        var command = service.buildCommandData().get(0);
        assertEquals("word", command.getSubcommandGroups().get(0).getName());
        assertEquals("add", command.getSubcommandGroups().get(0).getSubcommands().get(0).getName());
    }

    private static final class GroupedCommand extends SlashCommand {
        private GroupedCommand() {
            super("grouped", "Grouped command");
            addSubcommandGroup(new SubcommandGroupData("word", "Words")
                    .addSubcommands(new SubcommandData("add", "Add word")));
        }

        @Override public void execute(CommandContext context) { }
    }
}
