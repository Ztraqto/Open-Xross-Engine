package com.ztraqto.openxross.core.editor;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.api.settings.GuildSettingDefinition;
import com.ztraqto.openxross.api.settings.SettingType;
import com.ztraqto.openxross.api.settings.SettingScope;
import com.ztraqto.openxross.service.GuildSettingsService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSessionCodecTest {

    @Test
    void roundTripsSignedEditorAndApplyCodes() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
        EditorSessionCodec codec = new EditorSessionCodec(
                "12345678901234567890123456789012",
                Duration.ofMinutes(30),
                clock
        );
        List<GuildSettingDefinition> definitions = List.of(
                GuildSettingDefinition.builder("test-plugin", "test-plugin.language", SettingType.SELECT)
                        .label("Language")
                        .label("ja", "表示言語")
                        .label("en", "Display language")
                        .description("Language")
                        .description("ja", "表示に使用する言語")
                        .description("en", "Language used for display")
                        .defaultValue("ja")
                        .choice("ja", "Japanese")
                        .choice("en", "English")
                        .build(),
                GuildSettingDefinition.builder("xross", "xross.volume", SettingType.INTEGER)
                        .label("Volume")
                        .description("Volume")
                        .defaultValue(100)
                        .range(0, 100)
                        .build()
        );

        var launch = codec.createLaunch(
                123456789L,
                "Test Guild",
                Map.of("xross", "System", "test-plugin", "Test Plugin"),
                definitions,
                Map.of("test-plugin.language", TextNode.valueOf("ja"), "xross.volume", IntNode.valueOf(100))
        );
        var launchPayload = codec.decodeLaunchPayload(launch.token());
        assertEquals("Test Guild", launchPayload.guildName());
        assertEquals(Instant.parse("2026-07-17T00:30:00Z").getEpochSecond(), launch.authorization().expiresAt());
        assertEquals(launch.authorization().expiresAt(), launch.authorization().applyExpiresAt());
        assertEquals("System", launchPayload.categories().get("xross"));
        assertEquals("表示言語", launchPayload.definitions().get(0).labels().get("ja"));
        assertTrue(launchPayload.definitions().get(0).descriptions().isEmpty());

        String applyCode = codec.createApplyCode(
                launch.token(),
                Map.of("test-plugin.language", TextNode.valueOf("en"), "xross.volume", IntNode.valueOf(50))
        );
        var decoded = codec.decodeApply(applyCode, 123456789L);
        assertEquals("en", decoded.values().get("test-plugin.language").asText());
        assertEquals(50, decoded.values().get("xross.volume").intValue());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeApply(applyCode, 987654321L));

        EditorSessionCodec expiredCodec = new EditorSessionCodec(
                "12345678901234567890123456789012",
                Duration.ofMinutes(30),
                Clock.fixed(Instant.parse("2026-07-17T00:30:01Z"), ZoneOffset.UTC)
        );
        assertThrows(IllegalArgumentException.class, () -> expiredCodec.decodeApply(applyCode, 123456789L));
    }

    @Test
    void keepsCurrentBilingualPluginSchemaWithinDiscordMessageLimit() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
        EditorSessionCodec codec = new EditorSessionCodec(
                "12345678901234567890123456789012",
                Duration.ofMinutes(30),
                clock
        );
        ArrayList<GuildSettingDefinition> definitions = new ArrayList<>(new GuildSettingsService(null).getDefinitions());
        definitions.add(setting("makhara", "makhara.api-key", SettingType.STRING, "Gemini API key (write-only)", "Gemini APIキー（書き込み専用）", ""));
        definitions.add(setting("makhara", "makhara.api-model", SettingType.STRING, "Gemini model", "Geminiモデル", "gemini-1.5-flash"));
        definitions.add(GuildSettingDefinition.builder("makhara", "makhara.history-limit", SettingType.INTEGER)
                .label("Conversation history").label("ja", "会話履歴数").label("en", "Conversation history")
                .description("Conversation history limit.").defaultValue(20).range(0, 50).build());
        definitions.add(setting("makhara", "makhara.common-prompt", SettingType.STRING, "Server common prompt", "サーバー共通プロンプト", ""));
        definitions.add(setting("makhara", "makhara.active-profile", SettingType.STRING, "Context-menu profile", "コンテキストメニュー用プロファイル", ""));
        definitions.add(GuildSettingDefinition.builder("makhara", "makhara.allow-external-profiles", SettingType.BOOLEAN)
                .label("Allow external profiles").label("ja", "外部プロファイルを許可").label("en", "Allow external profiles")
                .description("Allow external profiles.").defaultValue(false).build());
        definitions.add(booleanSetting("welcome-guild-plugin", "welcome-guild-plugin.text-enabled", "テキスト参加通知", "Text join notification"));
        definitions.add(GuildSettingDefinition.builder("welcome-guild-plugin", "welcome-guild-plugin.target-channel", SettingType.CHANNEL)
                .label("通知チャンネル").label("ja", "通知チャンネル").label("en", "Notification channel")
                .description("Notification channel.").defaultValue("0").build());
        definitions.add(booleanSetting("welcome-guild-plugin", "welcome-guild-plugin.voice-enabled", "音声ウェルカム", "Voice welcome"));

        LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode> values = new LinkedHashMap<>();
        definitions.forEach(definition -> values.put(definition.key(), definition.defaultValue()));
        var launch = codec.createLaunch(
                123456789012345678L,
                "テスト用 Discord サーバー",
                Map.of("makhara", "MakharaCore", "welcome-guild-plugin", "Welcome Guild"),
                definitions,
                values
        );
        String url = "https://editor.xecute.ztraqto.jp/#" + launch.token();
        assertTrue(url.length() <= 1_800, "Editor URI length: " + url.length());
    }

    @Test
    void roundTripsReadOnlyEditorExtensionsWithoutAddingApplyValues() {
        EditorSessionCodec codec = new EditorSessionCodec(
                "12345678901234567890123456789012", Duration.ofMinutes(30)
        );
        var launch = codec.createLaunch(
                SettingScope.GUILD, 42L, 84L, "Graph Guild", Map.of(), List.of(), Map.of(),
                "ja", List.of(), List.of(), Map.of("erify", TextNode.valueOf("signed-graph-snapshot"))
        );
        var payload = codec.decodeLaunchPayload(launch.token());
        assertEquals("signed-graph-snapshot", payload.extensions().get("erify").asText());
        assertTrue(payload.values().isEmpty());
    }

    private static GuildSettingDefinition setting(
            String owner,
            String key,
            SettingType type,
            String englishLabel,
            String japaneseLabel,
            String defaultValue
    ) {
        return GuildSettingDefinition.builder(owner, key, type)
                .label(englishLabel).label("ja", japaneseLabel).label("en", englishLabel)
                .description(englishLabel + ".").defaultValue(defaultValue).range(0, 1_000).build();
    }

    private static GuildSettingDefinition booleanSetting(String owner, String key, String japaneseLabel, String englishLabel) {
        return GuildSettingDefinition.builder(owner, key, SettingType.BOOLEAN)
                .label(japaneseLabel).label("ja", japaneseLabel).label("en", englishLabel)
                .description(englishLabel + ".").defaultValue(true).build();
    }
}
