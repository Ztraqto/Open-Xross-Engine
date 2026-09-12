package com.ztraqto.openxross.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

class PluginArtifactSecurityTest {
    @TempDir Path directory;

    private Path jar(String classPath, boolean index) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        Path jar = directory.resolve("plugin.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry(index ? "META-INF/INDEX.LIST" : "plugin.json"));
            output.write((index ? "JarIndex-Version: 1.0\n\nexternal.jar\nprobe\n" : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @Test void rejectsManifestDependenciesEvenWhenApprovedHashMatches() throws Exception {
        Path jar = jar("file:/unapproved/helper.jar", false);
        String fingerprint = hash(jar);
        assertThrows(SecurityException.class, () -> PluginManager.createVerifiedSnapshot(jar.toFile(), "test", fingerprint));
    }

    @Test void rejectsJarIndexLoadingOutsideTheFingerprint() throws Exception {
        Path jar = jar(null, true);
        String fingerprint = hash(jar);
        assertThrows(SecurityException.class, () -> PluginManager.createVerifiedSnapshot(jar.toFile(), "test", fingerprint));
    }

    @Test void rejectsAlternateManifestSpellingRecognizedByJava() throws Exception {
        Path jar = directory.resolve("mixed-case.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("meta-inf/manifest.mf"));
            output.write("Manifest-Version: 1.0\r\nClass-Path: file:/unapproved.jar\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try (var archive = new java.util.jar.JarFile(jar.toFile())) {
            assertNotNull(archive.getManifest(), "This spelling must exercise Java's manifest fallback");
        }
        String fingerprint = hash(jar);
        assertThrows(SecurityException.class, () -> PluginManager.createVerifiedSnapshot(jar.toFile(), "test", fingerprint));
    }

    @Test void keepsStandaloneArtifactAndRejectsPostApprovalReplacement() throws Exception {
        Path jar = jar(null, false);
        String fingerprint = hash(jar);
        Path snapshot = PluginManager.createVerifiedSnapshot(jar.toFile(), "test", fingerprint);
        try {
            assertEquals(fingerprint, hash(snapshot));
            Files.writeString(jar, "changed after approval");
            assertThrows(SecurityException.class, () -> PluginManager.createVerifiedSnapshot(jar.toFile(), "test", fingerprint));
            assertEquals(fingerprint, hash(snapshot));
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }
}
