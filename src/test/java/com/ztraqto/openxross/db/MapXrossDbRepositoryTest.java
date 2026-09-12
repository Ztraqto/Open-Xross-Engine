package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.database.XrossDbMutation;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapXrossDbRepositoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void localBackendPersistsPluginApprovalRecords() {
        Path file = temporaryDirectory.resolve("xross-local.json");
        XrossDbKey key = new XrossDbKey("plugin-approval", "fingerprints", "plugin-a");
        try (MapXrossDbRepository repository = MapXrossDbRepository.local(file)) {
            repository.write(key, MAPPER.createObjectNode().put("sha256", "abc123"), 0L);
        }

        try (MapXrossDbRepository reopened = MapXrossDbRepository.local(file)) {
            assertEquals("abc123", reopened.read(key).orElseThrow().payload().path("sha256").asText());
        }
    }

    @Test
    void batchConflictLeavesEveryRecordUnchanged() {
        XrossDbKey existing = new XrossDbKey("test", "records", "existing");
        XrossDbKey newKey = new XrossDbKey("test", "records", "new");
        try (MapXrossDbRepository repository = MapXrossDbRepository.memory()) {
            repository.write(existing, MAPPER.createObjectNode().put("value", 1), 0L);

            assertThrows(XrossDbConflictException.class, () -> repository.applyBatch(List.of(
                    XrossDbMutation.write(newKey, MAPPER.createObjectNode().put("value", 2), 0L),
                    XrossDbMutation.write(existing, MAPPER.createObjectNode().put("value", 3), 99L)
            )));

            assertFalse(repository.read(newKey).isPresent());
            assertEquals(1, repository.read(existing).orElseThrow().payload().path("value").asInt());
        }
    }
}
