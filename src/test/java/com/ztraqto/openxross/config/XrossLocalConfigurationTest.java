package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XrossLocalConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPortableScalarAndArrayValues() throws Exception {
        Path file = temporaryDirectory.resolve("bot.config.json");
        Files.writeString(file, """
                {
                  "discord": {"token": "abc", "administratorIds": ["123", 456]},
                  "editor": {"attachmentTransport": true}
                }
                """);

        XrossLocalConfiguration.load(file);

        assertEquals("abc", XrossLocalConfiguration.string("discord.token", null));
        assertEquals(java.util.List.of("123", "456"),
                XrossLocalConfiguration.strings("discord.administratorIds", null));
        assertEquals(true, XrossLocalConfiguration.bool("editor.attachmentTransport", null, false));
    }

    @Test
    void rejectsDuplicateJsonKeys() throws Exception {
        Path file = temporaryDirectory.resolve("duplicate.config.json");
        Files.writeString(file, "{\"discord\":{},\"discord\":{}}");

        assertThrows(Exception.class, () -> XrossLocalConfiguration.load(file));
    }
}
