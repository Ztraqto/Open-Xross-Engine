package com.ztraqto.openxross.api.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuildSettingDefinitionTest {

    @Test
    void emptyListTemplateAcceptsTextFieldsAndOptionalDescription() {
        GuildSettingDefinition definition = GuildSettingDefinition.builder("coolol", "coolol.custom-words", SettingType.LIST)
                .label("Words").description("Custom words")
                .choice("id", "ID").choice("word", "Word").choice("description", "Description")
                .defaultValue(JsonNodeFactory.instance.arrayNode()).range(0, 20).build();
        var value = JsonNodeFactory.instance.arrayNode();
        var item = value.addObject();
        item.put("id", "W123"); item.put("word", "test"); item.put("description", "");

        assertEquals(value, definition.validate(value));
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validatesObjectListsFromTheirDeclaredFields() throws Exception {
        GuildSettingDefinition definition = GuildSettingDefinition.builder("test", "test.categories", SettingType.LIST)
                .choice("id", "ID").choice("meaning", "Meaning").choice("points", "Points")
                .defaultValue(MAPPER.readTree("[{\"id\":\"SPAM\",\"meaning\":\"Repeated advertising\",\"points\":60}]"))
                .range(1, 20).build();

        assertDoesNotThrow(() -> definition.validate(MAPPER.readTree(
                "[{\"id\":\"FAKE\",\"meaning\":\"Deceptive content\",\"points\":40}]")));
        assertThrows(IllegalArgumentException.class, () -> definition.validate(MAPPER.readTree(
                "[{\"id\":\"fake\",\"meaning\":\"Deceptive content\",\"points\":40}]")));
    }
}
