package com.ztraqto.openxross.core.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import com.ztraqto.openxross.api.command.CommandContext;
import com.ztraqto.openxross.api.command.SlashCommand;
import com.ztraqto.openxross.config.XrossProductConfiguration;
import com.ztraqto.openxross.XrossEngine;

import java.awt.Color;

/** Public, in-Discord privacy summary for the embedding Bot product. */
public final class PrivacyPolicyCommand extends SlashCommand {
    private final XrossEngine engine;

    public PrivacyPolicyCommand(XrossEngine engine) {
        super("privacy", "View the Bot privacy policy");
        this.engine = engine;
        setHelp("/privacy", "Displays how this Bot handles Discord and service data.");
    }

    @Override
    public void execute(CommandContext context) {
        context.getEvent().replyEmbeds(createEmbed(
                engine.getConfiguration().product(), engine.getProductName()
        )).setEphemeral(true).queue();
    }

    public static MessageEmbed createEmbed(XrossProductConfiguration product, String name) {
        if (product.privacyPolicyUrl() != null) {
            String links = "[プライバシーポリシー](" + product.privacyPolicyUrl() + ")"
                + (product.termsUrl() == null ? "" : " ・ [利用規約](" + product.termsUrl() + ")")
                + (product.homepageUrl() == null ? "" : " ・ [ホームページ](" + product.homepageUrl() + ")");
            return new EmbedBuilder()
                    .setTitle(name + " プライバシーと利用条件")
                    .setColor(new Color(88, 101, 242))
                    .setDescription("最新の内容は、運営者が公開している公式Webページをご確認ください。\n\n" + links)
                    .setFooter(name + " · 最終更新 " + product.privacyPolicyUpdatedAt())
                    .build();
        }
        return new EmbedBuilder()
                .setTitle(name + " プライバシーポリシー")
                .setColor(new Color(88, 101, 242))
                .setDescription(name + "は、Discordサーバー機能を提供するために必要な範囲でのみデータを扱います。")
                .addField("収集する情報", "DiscordのユーザーID・サーバーID・チャンネルID・ロールID、コマンド入力、各機能の設定、ならびに有効化した機能に必要なメッセージ・VC参加状態を扱います。", false)
                .addField("利用目的", "設定の保存、Bot機能の実行、モデレーション、バックアップ、監査ログ、翻訳、障害調査および不正利用防止に利用します。", false)
                .addField("保存場所と期間", "設定と運用データはBot管理者が管理するサーバー上のPostgreSQL・保護されたランタイム領域に保存されます。保持期間は機能設定・バックアップ世代・管理者の削除操作に従います。", false)
                .addField("第三者提供", "データを販売しません。翻訳機能を使用した場合、翻訳対象テキストは翻訳プロバイダーへ送信される場合があります。Discordのサービス利用にはDiscordのプライバシーポリシーも適用されます。", false)
                .addField("あなたの選択", "サーバー管理者は設定・ログ・バックアップを管理できます。個人データに関する問い合わせや削除依頼は、そのBotを管理するサーバー管理者へ連絡してください。", false)
                .setFooter(name + " · 最終更新 " + product.privacyPolicyUpdatedAt())
                .build();
    }
}
