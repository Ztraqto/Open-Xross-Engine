package com.ztraqto.openxross.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginManagerTest {
    @Test
    void acceptsSymbolicLinkToPluginDirectory(@TempDir Path temporaryDirectory) throws Exception {
        Path target = Files.createDirectory(temporaryDirectory.resolve("release-plugins"));
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("plugins"), target);

        assertDoesNotThrow(() -> PluginManager.ensurePluginDirectory(link));
        assertTrue(Files.isDirectory(link));
    }
}
