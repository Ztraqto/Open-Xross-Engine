package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbKey;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToPostgresMigratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesRecordsAndRetainsLocalFile() {
        Path source = temporaryDirectory.resolve("xross-local.json");
        XrossDbKey approval = new XrossDbKey("plugin-approval", "fingerprints", "plugin-a");
        try (MapXrossDbRepository local = MapXrossDbRepository.local(source)) {
            local.write(approval, MAPPER.createObjectNode().put("approved", true), 0L);
        }
        try (MapXrossDbRepository target = MapXrossDbRepository.memory()) {
            LocalToPostgresMigrator.run(source, target);
            assertTrue(target.read(approval).orElseThrow().payload().path("approved").asBoolean());
        }
        assertTrue(Files.isRegularFile(source));
    }

    @Test
    void conflictPreflightDoesNotPartiallyCopy() {
        Path source = temporaryDirectory.resolve("conflict.json");
        XrossDbKey pending = new XrossDbKey("test", "records", "a-pending");
        XrossDbKey conflict = new XrossDbKey("test", "records", "z-conflict");
        try (MapXrossDbRepository local = MapXrossDbRepository.local(source)) {
            local.write(pending, MAPPER.createObjectNode().put("value", "local"), 0L);
            local.write(conflict, MAPPER.createObjectNode().put("value", "local"), 0L);
        }
        try (MapXrossDbRepository target = MapXrossDbRepository.memory()) {
            target.write(conflict, MAPPER.createObjectNode().put("value", "postgres"), 0L);
            assertThrows(XrossDbConflictException.class,
                    () -> LocalToPostgresMigrator.run(source, target));
            assertFalse(target.read(pending).isPresent());
            assertEquals("postgres", target.read(conflict).orElseThrow().payload().path("value").asText());
        }
    }
}
