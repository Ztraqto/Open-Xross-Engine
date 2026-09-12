package com.ztraqto.openxross.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AITerminalSettingsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsProfilesAndRateLimitsWithoutExposingApiKeyInToString() throws Exception {
        Path file = temporaryDirectory.resolve("xecute.aits.settings.json");
        Files.writeString(file, """
                {
                  "version": 1,
                  "global": {"maxConcurrentRequests": 3},
                  "profiles": {
                    "local": {
                      "baseUrl": "http://127.0.0.1:1234/v1",
                      "model": "test-model",
                      "apiKey": "secret-value",
                      "rateLimit": {
                        "maxRequests": 2,
                        "windowSeconds": 10,
                        "maxConcurrentRequests": 1,
                        "maxQueuedRequests": 5
                      }
                    }
                  }
                }
                """);

        AITerminalSettings settings = AITerminalSettings.load(file);
        AITerminalSettings.Profile profile = settings.profiles().get("local");

        assertEquals(3, settings.maxConcurrentRequests());
        assertEquals(2, profile.rateLimit().maxRequests());
        assertEquals(1, profile.rateLimit().maxConcurrentRequests());
        assertFalse(profile.toString().contains("secret-value"));
    }

    @Test
    void rejectsUnsupportedProtocol() throws Exception {
        Path file = temporaryDirectory.resolve("unsupported.json");
        Files.writeString(file, """
                {
                  "profiles": {
                    "bad": {
                      "protocol": "native-gemini",
                      "baseUrl": "https://example.com/v1",
                      "model": "test"
                    }
                  }
                }
                """);

        assertThrows(Exception.class, () -> AITerminalSettings.load(file));
    }
}
