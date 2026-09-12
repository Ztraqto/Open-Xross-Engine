package com.ztraqto.openxross.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresAutoSetupTest {

    @Test
    void derivesDatabaseAndMaintenanceUrl() {
        String target = "jdbc:postgresql://db.internal:5432/my_bot?sslmode=require";
        assertEquals("my_bot", PostgresAutoSetup.databaseName(target));
        assertEquals(
                "jdbc:postgresql://db.internal:5432/postgres?sslmode=require",
                PostgresAutoSetup.maintenanceUrl(target));
    }

    @Test
    void rejectsAmbiguousOrUnsafeDatabaseNames() {
        assertThrows(IllegalArgumentException.class,
                () -> PostgresAutoSetup.databaseName("jdbc:postgresql:my_bot"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresAutoSetup.databaseName("jdbc:postgresql://localhost/bad%2Fname"));
    }
}
