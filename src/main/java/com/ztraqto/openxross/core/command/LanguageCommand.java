package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.service.LocaleService;

import java.util.Locale;
import java.util.Set;

public class LanguageCommand extends SlashCommand {

    public LanguageCommand(XrossEngine engine) {
        super("language", "Change bot language");

        LocaleService service = engine.getServiceManager().getService(LocaleService.class);
        Set<String> languages = service.getAvailableSystemLanguages();

        OptionData langOption = new OptionData(OptionType.STRING, "xross-lang", "Language code", true);

        for (String code : languages) {
            // 言語コードから表示名を取得 (例: "ja" -> "Japanese")
            Locale locale = Locale.forLanguageTag(code);
            String displayName = locale.getDisplayName(Locale.ENGLISH);

            // "Japanese (ja)" のような形式で選択肢に追加
            // Discordの制限で選択肢は最大25個まで
            if (langOption.getChoices().size() < 25) {
                langOption.addChoices(new Command.Choice(displayName + " (" + code + ")", code));
            }
        }

        // 言語ファイルが1つもない場合のフォールバック
        if (langOption.getChoices().isEmpty()) {
            langOption.addChoices(new Command.Choice("English (Default)", "en"));
        }

        addOption(langOption);

        setHelp(
                "/language <lang>",
                "Botの表示言語を変更します。設定はサーバーごとに保存されます。"
        );

        addExample("/language en (English)");
        addExample("/language ja (日本語)");
    }

    @Override
    public void execute(CommandContext ctx) {
        String lang = ctx.getOptionAsString("xross-lang");
        long guildId = ctx.getEvent().getGuild().getIdLong();

        LocaleService service = ctx.getEngine().getServiceManager().getService(LocaleService.class);
        service.setGuildLanguage(guildId, lang);

        ctx.replyTr("cmd.language.response.success", lang);
    }
}
