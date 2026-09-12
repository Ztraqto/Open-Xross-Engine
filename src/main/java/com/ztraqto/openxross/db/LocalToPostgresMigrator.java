package com.ztraqto.openxross.db;

import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Restart-safe, non-destructive migration from local JSON storage to PostgreSQL. */
public final class LocalToPostgresMigrator {
    private static final Logger logger = LoggerFactory.getLogger(LocalToPostgresMigrator.class);

    private LocalToPostgresMigrator() {
    }

    public static void run(Path localPath, XrossDbRepository target) {
        Path source = localPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Local XrossDB file was not found: " + source);
        }

        int copied = 0;
        int unchanged = 0;
        try (MapXrossDbRepository local = MapXrossDbRepository.local(source)) {
            List<XrossDbRecord> pending = new ArrayList<>();
            for (XrossDbRecord record : local.snapshot()) {
                var existing = target.read(record.key());
                if (existing.isPresent()) {
                    if (existing.get().payload().equals(record.payload())) {
                        unchanged++;
                        continue;
                    }
                    throw new XrossDbConflictException(
                            "PostgreSQL already contains different data for " + record.key()
                                    + ". Existing data was not overwritten.");
                }
                pending.add(record);
            }
            for (XrossDbRecord record : pending) {
                target.write(record.key(), record.payload(), 0L);
                copied++;
            }
        }
        logger.info(
                "Local-to-PostgreSQL migration completed: {} copied, {} already identical. Source retained at {}.",
                copied, unchanged, source);
    }
}
