package com.ztraqto.openxross.db;

import com.ztraqto.openxross.api.database.XrossDbException;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, idempotent first-run creation of one PostgreSQL role and database. */
public final class PostgresAutoSetup {
    private static final Logger logger = LoggerFactory.getLogger(PostgresAutoSetup.class);
    private static final Pattern JDBC_URL = Pattern.compile(
            "^jdbc:postgresql://([^/]+)/([^?/#]+)(\\?[^#]*)?$");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$-]{0,62}");

    private PostgresAutoSetup() {
    }

    public static void run(XrossDbConfiguration configuration) {
        if (!configuration.autoSetup()) return;

        String databaseName = databaseName(configuration.postgresUrl());
        String roleName = safeIdentifier(configuration.postgresUser(), "database.postgresUser");
        String adminUrl = configuration.setupAdminUrl() == null
                ? maintenanceUrl(configuration.postgresUrl())
                : configuration.setupAdminUrl();

        Properties properties = new Properties();
        properties.setProperty("user", configuration.setupAdminUser());
        if (configuration.setupAdminPassword() != null) {
            properties.setProperty("password", configuration.setupAdminPassword());
        }

        logger.info("Checking PostgreSQL role '{}' and database '{}' for first-run setup.", roleName, databaseName);
        try (Connection connection = DriverManager.getConnection(adminUrl, properties)) {
            ensureRole(connection, roleName, configuration.postgresPassword());
            ensureDatabase(connection, databaseName, roleName);
            logger.info("PostgreSQL first-run setup is ready for database '{}'.", databaseName);
        } catch (SQLException exception) {
            throw new XrossDbException(
                    "PostgreSQL auto setup failed. Verify database.setup administrator credentials and server access.",
                    exception);
        }
    }

    static String databaseName(String postgresUrl) {
        Matcher matcher = matcher(postgresUrl);
        String decoded = URLDecoder.decode(matcher.group(2), StandardCharsets.UTF_8);
        return safeIdentifier(decoded, "PostgreSQL database name");
    }

    static String maintenanceUrl(String postgresUrl) {
        Matcher matcher = matcher(postgresUrl);
        String query = matcher.group(3) == null ? "" : matcher.group(3);
        return "jdbc:postgresql://" + matcher.group(1) + "/postgres" + query;
    }

    private static Matcher matcher(String postgresUrl) {
        Matcher matcher = JDBC_URL.matcher(postgresUrl == null ? "" : postgresUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "--auto-db-setup requires a JDBC URL such as jdbc:postgresql://host:5432/database.");
        }
        return matcher;
    }

    private static String safeIdentifier(String value, String name) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a PostgreSQL identifier of 1-63 characters.");
        }
        return value;
    }

    private static void ensureRole(Connection connection, String roleName, String password) throws SQLException {
        if (exists(connection, "SELECT 1 FROM pg_roles WHERE rolname=?", roleName)) return;
        String sql = password == null
                ? formattedSql(connection, "SELECT format('CREATE ROLE %I LOGIN', ?)", roleName)
                : formattedSql(connection, "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', ?, ?)",
                        roleName, password);
        executeCreate(connection, sql, "42710");
        logger.info("Created PostgreSQL login role '{}'.", roleName);
    }

    private static void ensureDatabase(Connection connection, String databaseName, String roleName) throws SQLException {
        if (exists(connection, "SELECT 1 FROM pg_database WHERE datname=?", databaseName)) return;
        String sql = formattedSql(connection,
                "SELECT format('CREATE DATABASE %I OWNER %I', ?, ?)", databaseName, roleName);
        executeCreate(connection, sql, "42P04");
        logger.info("Created PostgreSQL database '{}' owned by '{}'.", databaseName, roleName);
    }

    private static boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String formattedSql(Connection connection, String query, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("PostgreSQL did not format setup SQL.");
                return result.getString(1);
            }
        }
    }

    private static void executeCreate(Connection connection, String sql, String duplicateState) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            if (!duplicateState.equals(exception.getSQLState())) throw exception;
        }
    }
}
