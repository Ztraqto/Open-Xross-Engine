package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrossArgumentsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDatabaseSetupAndMigrationFlagsWithoutValues() throws Exception {
        Path config = temporaryDirectory.resolve("xross.config.json");
        Files.writeString(config, "{}\n");

        XrossConfiguration parsed = XrossArguments.parse(new String[]{
                "--config", config.toString(),
                "--mode", "database",
                "--db-backend", "postgres",
                "--auto-db-setup",
                "--migrate-local-to-postgres"
        });

        assertEquals(XrossDbBackend.POSTGRES, parsed.database().backend());
        assertTrue(parsed.database().autoSetup());
        assertTrue(parsed.database().migrateLocalToPostgres());
    }

    @Test
    void readsDatabaseNetworkIsolationFromConfig() throws Exception {
        Path config = temporaryDirectory.resolve("isolated-xross.config.json");
        Files.writeString(config, """
                {
                  "database": {
                    "backend": "local",
                    "bindAddress": "127.0.0.1",
                    "serverUrl": "http://127.0.0.1",
                    "port": 17420
                  }
                }
                """);

        XrossConfiguration parsed = XrossArguments.parse(new String[]{
                "--config", config.toString(),
                "--mode", "database"
        });

        assertEquals("127.0.0.1", parsed.database().bindAddress());
        assertEquals("http://127.0.0.1", parsed.database().serverUrl());
        assertEquals(17420, parsed.database().port());
    }
}
