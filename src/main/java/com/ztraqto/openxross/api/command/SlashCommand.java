package com.ztraqto.openxross.api.command;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import com.ztraqto.openxross.api.plugin.XrossPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public abstract class SlashCommand {

    private final String name;
    private final String description;
    private final List<OptionData> options = new ArrayList<>();
    private final List<SubcommandData> subcommands = new ArrayList<>();
    private final List<SubcommandGroupData> subcommandGroups = new ArrayList<>();
    private final Map<DiscordLocale, String> nameLocalizations = new EnumMap<>(DiscordLocale.class);
    private final Map<DiscordLocale, String> descriptionLocalizations = new EnumMap<>(DiscordLocale.class);
    private XrossPlugin plugin;
    private HelpInfo helpInfo;
    private DefaultMemberPermissions defaultMemberPermissions;

    protected SlashCommand(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract void execute(CommandContext context);

    protected void addOption(OptionData option) {
        options.add(option);
    }

    protected void addSubcommand(SubcommandData subcommand) {
        subcommands.add(subcommand);
    }

    protected void addSubcommandGroup(SubcommandGroupData subcommandGroup) {
        subcommandGroups.add(subcommandGroup);
    }

    /**
     * @deprecated Define {@code cmd.<command>.name} in the locale JSON files instead.
     */
    @Deprecated
    protected final void setNameLocalization(DiscordLocale locale, String localizedName) {
        nameLocalizations.put(locale, localizedName);
    }

    /**
     * @deprecated Define {@code cmd.<command>.description} in the locale JSON files instead.
     */
    @Deprecated
    protected final void setDescriptionLocalization(DiscordLocale locale, String localizedDescription) {
        descriptionLocalizations.put(locale, localizedDescription);
    }

    protected void setHelp(String usage, String detail) {
        helpInfo = new HelpInfo(usage, detail);
    }

    /** Limits Discord-side visibility as well as the command's runtime authorization. */
    protected final void setDefaultMemberPermissions(DefaultMemberPermissions permissions) {
        defaultMemberPermissions = permissions;
    }

    protected void addExample(String example) {
        if (helpInfo == null) {
            helpInfo = new HelpInfo("", "");
        }
        helpInfo.addExample(example);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public final String getDescription(DiscordLocale locale) {
        return descriptionLocalizations.getOrDefault(locale, description);
    }

    public List<OptionData> getOptions() {
        return List.copyOf(options);
    }

    public List<SubcommandData> getSubcommands() {
        return List.copyOf(subcommands);
    }

    public List<SubcommandGroupData> getSubcommandGroups() {
        return List.copyOf(subcommandGroups);
    }

    public final Map<DiscordLocale, String> getNameLocalizations() {
        return Map.copyOf(nameLocalizations);
    }

    public final Map<DiscordLocale, String> getDescriptionLocalizations() {
        return Map.copyOf(descriptionLocalizations);
    }

    public XrossPlugin getPlugin() {
        return plugin;
    }

    public void setPlugin(XrossPlugin plugin) {
        this.plugin = plugin;
    }

    public HelpInfo getHelpInfo() {
        return helpInfo;
    }

    public DefaultMemberPermissions getDefaultMemberPermissions() {
        return defaultMemberPermissions;
    }
}
