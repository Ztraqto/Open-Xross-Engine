package com.ztraqto.openxross.api.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import com.ztraqto.openxross.service.LocaleService;

/**
 * コマンド実行時の情報を保持するコンテキスト。
 * 多言語対応 (i18n) 機能を含む。
 */
public class CommandContext {

    private final XrossEngine engine;
    private final SlashCommandInteractionEvent event;
    private final SlashCommand command; // 【追加】実行中のコマンド情報

    // コンストラクタを変更
    public CommandContext(XrossEngine engine, SlashCommandInteractionEvent event, SlashCommand command) {
        this.engine = engine;
        this.event = event;
        this.command = command;
    }

    public XrossEngine getEngine() {
        return engine;
    }

    /** Product name configured by the host Bot or detected after Discord login. */
    public String getProductName() {
        return engine.getProductName();
    }

    public SlashCommandInteractionEvent getEvent() {
        return event;
    }

    public SlashCommand getCommand() {
        return command;
    }

    // --- Option Helpers ---

    public OptionMapping getOption(String name) {
        return event.getOption(name);
    }

    public String getOptionAsString(String name) {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }

    // --- Reply Helpers ---

    public void reply(String message, boolean ephemeral) {
        event.reply(message).setEphemeral(ephemeral).queue();
    }

    public void replyError(String message) {
        event.reply("❌ " + message).setEphemeral(true).queue();
    }

    public void replyErrorTr(String key, Object... args) {
        replyError(tr(key, args));
    }

    // --- i18n (多言語対応) Helpers ---

    /**
     * 翻訳キーを使ってメッセージを取得し、返信する。
     * @param key 言語ファイルのキー (例: "msg.success")
     * @param args プレースホルダー({0}, {1}...)に埋め込む引数
     */
    public void replyTr(String key, Object... args) {
        String text = tr(key, args);
        reply(text, false); // デフォルトでは全員に見えるように返信
    }

    /**
     * エフェメラル（自分だけ見える）で翻訳メッセージを返信する。
     */
    public void replyTrEphemeral(String key, Object... args) {
        String text = tr(key, args);
        reply(text, true);
    }

    /**
     * 現在のギルド設定に基づいて翻訳テキストを取得する。
     * コマンド内部で文字列加工したい場合に使う。
     */
    public String tr(String key, Object... args) {
        // 1. LocaleServiceを取得
        LocaleService service = engine.getServiceManager().getService(LocaleService.class);
        if (service == null) {
            return key; // サービスが死んでいる場合はキーをそのまま返す
        }

        // 2. 所属するプラグインを取得
        XrossPlugin plugin = command.getPlugin();

        // 3. ギルドIDを取得 (DMの場合は0Lとする)
        long guildId = (event.getGuild() != null) ? event.getGuild().getIdLong() : 0L;
        long userId = event.getUser().getIdLong();

        // 4. システムコマンド(Pluginがnull)の場合の対策
        if (plugin == null) {
            // システム用の言語ファイルを見るか、あるいはそのまま返すか
            // 今回は "SYSTEM" という仮想IDで処理させる例
            return service.getSystemText(guildId, userId, key, args);
        }

        // 5. 翻訳実行
        return service.get(plugin, guildId, userId, key, args);
    }
}
