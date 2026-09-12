package com.ztraqto.openxross.service;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.CommandCatalogEntry;
import com.ztraqto.openxross.api.command.HelpInfo;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class CommandService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(CommandService.class);
    private XrossEngine engine;
    private final PauseService pauseService;
    private final LocaleService localeService;
    private final Map<String, SlashCommand> commandMap = new ConcurrentHashMap<>();
    private final Map<String, RawCommandRegistration> rawCommandMap = new ConcurrentHashMap<>();

    public CommandService(PauseService pauseService) {
        this(pauseService, null);
    }

    public CommandService(PauseService pauseService, LocaleService localeService) {
        this.pauseService = pauseService;
        this.localeService = localeService;
    }

    @Override
    public void init(XrossEngine engine) {
        this.engine = engine;
    }

    @Override
    public void shutdown() {
        commandMap.clear();
        rawCommandMap.clear();
    }

    @Override
    public String getName() {
        return "CommandService";
    }

    // 登録処理 (メモリへの追加のみ)
    public void register(XrossPlugin plugin, SlashCommand command) {
        if (commandMap.containsKey(command.getName()) || rawCommandMap.containsKey(command.getName())) {
            logger.warn("Duplicate command name detected: {}", command.getName());
            return;
        }
        command.setPlugin(plugin);
        commandMap.put(command.getName(), command);
        logger.info("Registered command: /{} (Plugin: {})", command.getName(), plugin != null ? plugin.getMeta().getId() : "SYSTEM");
    }

    public void register(XrossPlugin plugin, CommandData command) {
        if (commandMap.containsKey(command.getName()) || rawCommandMap.containsKey(command.getName())) {
            logger.warn("Duplicate command name detected: {}", command.getName());
            return;
        }
        rawCommandMap.put(command.getName(), new RawCommandRegistration(plugin, command));
        logger.info(
                "Registered listener-backed command: /{} (Plugin: {})",
                command.getName(),
                plugin != null ? plugin.getMeta().getId() : "SYSTEM"
        );
    }

    public void unregisterAll(XrossPlugin plugin) {
        commandMap.values().removeIf(cmd -> cmd.getPlugin() == plugin);
        rawCommandMap.values().removeIf(registration -> registration.plugin() == plugin);
    }

    // ===================================================================================
    // 同期処理 (手動実行用)
    // ===================================================================================

    /**
     * 現在のコマンド定義を Global Scope (全サーバー) に反映する。
     * 反映には最大1時間かかる場合がある。
     */
    public void syncCommandsGlobal() {
        JDA jda = engine.getJda();
        if (jda == null) return;

        List<CommandData> dataList = buildAllCommandData();

        logger.info("Syncing {} commands to GLOBAL...", dataList.size());
        jda.updateCommands().addCommands(dataList).queue(
                s -> logger.info("Global commands sync requested successfully."),
                e -> logger.error("Failed to sync global commands.", e)
        );
    }

    /**
     * 現在のコマンド定義を 指定したGuild (サーバー) にのみ反映する。
     * 即時反映されるため、開発や検証に使用する。
     */
    public void syncCommandsGuild(Guild guild) {
        if (guild == null) return;

        List<CommandData> dataList = buildAllCommandData();

        logger.info("Syncing {} commands to GUILD: {}...", dataList.size(), guild.getName());
        guild.updateCommands().addCommands(dataList).queue(
                s -> logger.info("Guild commands synced to '{}'.", guild.getName()),
                e -> logger.error("Failed to sync guild commands.", e)
        );
    }

    /** Removes every application command currently registered in global scope. */
    public CompletableFuture<Integer> deleteCommandsGlobal() {
        JDA jda = engine == null ? null : engine.getJda();
        if (jda == null) return CompletableFuture.failedFuture(new IllegalStateException("JDA is unavailable."));
        return jda.retrieveCommands().submit().thenCompose(existing ->
                jda.updateCommands().submit().thenApply(ignored -> existing.size()));
    }

    /** Removes every application command currently registered for one guild. */
    public CompletableFuture<Integer> deleteCommandsGuild(Guild guild) {
        if (guild == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Guild is required."));
        return guild.retrieveCommands().submit().thenCompose(existing ->
                guild.updateCommands().submit().thenApply(ignored -> existing.size()));
    }

    List<SlashCommandData> buildCommandData() {
        List<SlashCommandData> dataList = new ArrayList<>();

        for (SlashCommand cmd : commandMap.values()) {
            SlashCommandData data = Commands.slash(
                    cmd.getName(),
                    getDefaultText(cmd, "description", cmd.getDescription())
            );
            if (cmd.getDefaultMemberPermissions() != null) {
                data.setDefaultPermissions(cmd.getDefaultMemberPermissions());
            }
            data.setNameLocalizations(cmd.getNameLocalizations());
            data.setDescriptionLocalizations(cmd.getDescriptionLocalizations());
            applyLocalizations(cmd, data);

            // サブコマンドがあるかどうかで登録方法を変える
            if (!cmd.getSubcommands().isEmpty() || !cmd.getSubcommandGroups().isEmpty()) {
                // サブコマンドがある場合 (例: /manager add ...)
                data.addSubcommands(cmd.getSubcommands());
                data.addSubcommandGroups(cmd.getSubcommandGroups());
            } else {
                // サブコマンドがない場合 (例: /hello [user])
                data.addOptions(cmd.getOptions());
            }

            dataList.add(data);
        }
        return dataList;
    }

    List<CommandData> buildAllCommandData() {
        List<CommandData> data = new ArrayList<>(buildCommandData());
        rawCommandMap.values().stream()
                .map(RawCommandRegistration::command)
                .sorted(java.util.Comparator.comparing(CommandData::getName))
                .forEach(data::add);
        return data;
    }

    public String getLocalizedDescription(SlashCommand command, DiscordLocale locale) {
        String localized = getLocalizations(command, "description").get(locale);
        return localized == null ? command.getDescription(locale) : localized;
    }

    public String getLocalizedCommandText(
            SlashCommand command,
            long guildId,
            String suffix,
            String fallback
    ) {
        return getLocalizedCommandText(command, guildId, 0L, suffix, fallback);
    }

    public String getLocalizedCommandText(
            SlashCommand command,
            long guildId,
            long userId,
            String suffix,
            String fallback
    ) {
        if (localeService == null) {
            return fallback;
        }
        String key = commandKey(command, suffix);
        String translated = command.getPlugin() == null
                ? localeService.getSystemText(guildId, userId, key)
                : localeService.get(command.getPlugin(), guildId, userId, key);
        return key.equals(translated) ? fallback : translated;
    }

    private void applyLocalizations(SlashCommand command, SlashCommandData data) {
        getLocalizations(command, "name").forEach(data::setNameLocalization);
        getLocalizations(command, "description").forEach(data::setDescriptionLocalization);
        command.getOptions().forEach(option -> applyOptionLocalizations(command, "option", option));
        command.getSubcommands().forEach(subcommand -> applySubcommandLocalizations(command, subcommand));
    }

    private void applySubcommandLocalizations(SlashCommand command, SubcommandData subcommand) {
        String base = "subcommand." + subcommand.getName();
        subcommand.setDescription(getDefaultText(command, base + ".description", subcommand.getDescription()));
        getLocalizations(command, base + ".name").forEach(subcommand::setNameLocalization);
        getLocalizations(command, base + ".description").forEach(subcommand::setDescriptionLocalization);
        subcommand.getOptions().forEach(option ->
                applyOptionLocalizations(command, base + ".option", option)
        );
    }

    private void applyOptionLocalizations(SlashCommand command, String prefix, OptionData option) {
        String base = prefix + "." + option.getName();
        option.setDescription(getDefaultText(command, base + ".description", option.getDescription()));
        option.setNameLocalizations(Map.of());
        getLocalizations(command, base + ".description").forEach(option::setDescriptionLocalization);
        for (Command.Choice choice : option.getChoices()) {
            choice.setName(getDefaultText(
                    command,
                    base + ".choice." + choice.getAsString(),
                    choice.getName()
            ));
            getLocalizations(command, base + ".choice." + choice.getAsString())
                    .forEach(choice::setNameLocalization);
        }
    }

    private Map<DiscordLocale, String> getLocalizations(SlashCommand command, String suffix) {
        if (localeService == null) {
            return Map.of();
        }
        return localeService.getDiscordLocalizations(command.getPlugin(), commandKey(command, suffix));
    }

    private String getDefaultText(SlashCommand command, String suffix, String fallback) {
        if (localeService == null) {
            return fallback;
        }
        return localeService.getDefaultText(command.getPlugin(), commandKey(command, suffix), fallback);
    }

    private static String commandKey(SlashCommand command, String suffix) {
        return "cmd." + command.getName() + "." + suffix;
    }

    /**
     * 登録されている全コマンドのリストを取得する（読み取り専用）
     */
    public java.util.Collection<SlashCommand> getAllCommands() {
        return java.util.Collections.unmodifiableCollection(commandMap.values());
    }

    /**
     * 名前でコマンドを検索する
     */
    public SlashCommand getCommand(String name) {
        return commandMap.get(name);
    }

    public List<CommandCatalogEntry> getCommandCatalog(long guildId) {
        return getCommandCatalog(guildId, 0L);
    }

    public List<CommandCatalogEntry> getCommandCatalog(long guildId, long userId) {
        ArrayList<CommandCatalogEntry> entries = new ArrayList<>();
        commandMap.values().stream()
                .filter(command -> isCatalogVisible(command, userId))
                .forEach(command -> entries.add(toCatalogEntry(command, guildId, userId)));
        rawCommandMap.values().forEach(registration -> entries.add(toCatalogEntry(registration, guildId, userId)));
        entries.sort(Comparator
                .comparing(CommandCatalogEntry::pluginName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CommandCatalogEntry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    public Optional<CommandCatalogEntry> getCommandCatalogEntry(String name, long guildId) {
        return getCommandCatalogEntry(name, guildId, 0L);
    }

    public Optional<CommandCatalogEntry> getCommandCatalogEntry(String name, long guildId, long userId) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        SlashCommand command = commandMap.get(name);
        if (command != null) {
            return isCatalogVisible(command, userId)
                    ? Optional.of(toCatalogEntry(command, guildId, userId))
                    : Optional.empty();
        }
        RawCommandRegistration registration = rawCommandMap.get(name);
        return registration == null ? Optional.empty() : Optional.of(toCatalogEntry(registration, guildId, userId));
    }

    private CommandCatalogEntry toCatalogEntry(SlashCommand command, long guildId, long userId) {
        HelpInfo help = command.getHelpInfo();
        String description = getLocalizedCommandText(command, guildId, userId, "description", command.getDescription());
        String usage = help == null
                ? "/" + command.getName()
                : getLocalizedCommandText(command, guildId, userId, "help.usage", help.getUsage());
        String detail = help == null
                ? description
                : getLocalizedCommandText(command, guildId, userId, "help.detail", help.getDetail());
        return new CommandCatalogEntry(
                command.getName(),
                description,
                usage,
                detail,
                help == null ? List.of() : help.getExamples(),
                pluginId(command.getPlugin()),
                pluginName(command.getPlugin()),
                false
        );
    }

    private CommandCatalogEntry toCatalogEntry(RawCommandRegistration registration, long guildId, long userId) {
        CommandData command = registration.command();
        boolean contextMenu = !(command instanceof SlashCommandData);
        String description = command instanceof SlashCommandData slashCommand
                ? slashCommand.getDescription()
                : contextMenuDescription(guildId, userId);
        return new CommandCatalogEntry(
                command.getName(),
                description,
                contextMenu ? command.getName() : "/" + command.getName(),
                description,
                List.of(),
                pluginId(registration.plugin()),
                pluginName(registration.plugin()),
                contextMenu
        );
    }

    private String contextMenuDescription(long guildId, long userId) {
        if (localeService == null) {
            return "Context-menu action";
        }
        String translated = localeService.getSystemText(guildId, userId, "help.context-menu");
        return "help.context-menu".equals(translated) ? "Context-menu action" : translated;
    }

    private static String pluginId(XrossPlugin plugin) {
        return plugin == null ? "XROSS-SYSTEM" : plugin.getMeta().getId();
    }

    private static String pluginName(XrossPlugin plugin) {
        return plugin == null ? "System" : plugin.getMeta().getName();
    }

    private boolean isCatalogVisible(SlashCommand command, long userId) {
        // Before init there is no authenticated caller context (unit tests and
        // command-definition tooling). Runtime catalogs always have an engine.
        if (engine == null) return true;
        return switch (command.getName()) {
            case "xross-admin-editor" ->
                    engine.getConfiguration().botAdministratorIds().contains(userId);
            case "xross-console", "master-volume" -> {
                XrossConsoleService console = engine.getServiceManager().getService(XrossConsoleService.class);
                yield console != null && console.isAdministrator(userId);
            }
            default -> true;
        };
    }

    // ===================================================================================
    // 実行処理
    // ===================================================================================

    public void dispatch(SlashCommandInteractionEvent event) {
        String commandName = event.getName();
        SlashCommand command = commandMap.get(commandName);

        if (command == null) {
            RawCommandRegistration raw = rawCommandMap.get(commandName);
            if (raw != null) {
                long userId = event.getUser().getIdLong();
                if (!pauseService.canExecute(userId, raw.plugin().getMeta().getId(), commandName)) {
                    event.reply("笵・This command is currently paused.").setEphemeral(true).queue();
                }
                return;
            }
            // Discord上にはあるがBot内部にない場合
            event.reply("❌ Command definition not found in Engine.").setEphemeral(true).queue();
            return;
        }

        // System Command (Pluginがnull) はPauseチェックをバイパスする、等の特例措置を入れることも可能
        // ここでは通常通りチェックする
        String pluginId = (command.getPlugin() != null) ? command.getPlugin().getMeta().getId() : "SYSTEM";
        long userId = event.getUser().getIdLong();

        if (!pauseService.canExecute(userId, pluginId, commandName)) {
            event.reply("⛔ This command is currently paused.").setEphemeral(true).queue();
            return;
        }

        try {
            // 【修正】ここで command インスタンスを渡す
            CommandContext ctx = new CommandContext(engine, event, command);
            command.execute(ctx);
        } catch (Exception e) {
            logger.error("Error executing command: /" + commandName, e);
            event.reply("❌ Internal Error.").setEphemeral(true).queue();
        }
    }

    private record RawCommandRegistration(XrossPlugin plugin, CommandData command) {
    }
}
