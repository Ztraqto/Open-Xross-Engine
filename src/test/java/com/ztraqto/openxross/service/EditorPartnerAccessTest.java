package com.ztraqto.openxross.service;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import com.ztraqto.openxross.config.XrossEditorConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorPartnerAccessTest {

    @Test
    void nonAiBotsDoNotRequireAiReportSettingsDuringApply() {
        GuildSettingsService settings = new GuildSettingsService(null);
        EditorSessionService editor = new EditorSessionService(null, settings, XrossEditorConfiguration.builder().build());

        assertDoesNotThrow(() -> editor.validateAiReportConfiguration(1L, Map.of()));
    }

    @Test
    void onlyPartnerCanEnableAiReport() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> enable = Map.of("ai-report.enabled", BooleanNode.TRUE);
        Map<String, com.fasterxml.jackson.databind.JsonNode> disable = Map.of("ai-report.enabled", BooleanNode.FALSE);

        assertThrows(IllegalArgumentException.class, () -> EditorSessionService.validateAiReportAccess(enable, false));
        assertDoesNotThrow(() -> EditorSessionService.validateAiReportAccess(enable, true));
        assertDoesNotThrow(() -> EditorSessionService.validateAiReportAccess(disable, false));
        assertDoesNotThrow(() -> EditorSessionService.validateAiReportAccess(Map.of(
                "ai-report.enabled", BooleanNode.TRUE,
                "ai-report.provider", TextNode.valueOf("openai"),
                "ai-report.secondary-provider", TextNode.valueOf("openai")
        ), false));
        assertThrows(IllegalArgumentException.class, () -> EditorSessionService.validateAiReportAccess(Map.of(
                "ai-report.enabled", BooleanNode.TRUE,
                "ai-report.provider", TextNode.valueOf("openai"),
                "ai-report.secondary-provider", TextNode.valueOf("internal-mini")
        ), false));
    }
}
