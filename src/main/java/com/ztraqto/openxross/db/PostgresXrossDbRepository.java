package com.ztraqto.openxross.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.ztraqto.openxross.api.database.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** PostgreSQL-backed XrossDB records in one validated schema. */
public final class PostgresXrossDbRepository implements XrossDbRepository {
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 100;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DataSource dataSource;
    private final String schema;

    public static HikariDataSource createDataSource(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("XrossPostgres");
        config.setMaximumPoolSize(12);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    public PostgresXrossDbRepository(DataSource dataSource, String schema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (!schema.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid PostgreSQL schema: " + schema);
        this.schema = schema;
        initializeSchema();
    }

    @Override
    public Optional<XrossDbRecord> read(XrossDbKey key) {
        String sql = "SELECT payload, revision, updated_at FROM " + table() + " WHERE namespace=? AND collection_name=? AND record_key=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(record(key, result)) : Optional.empty();
            }
        } catch (Exception exception) {
            throw new XrossDbException("Failed to read " + key, exception);
        }
    }

    @Override
    public XrossDbRecord write(XrossDbKey key, JsonNode payload, long expectedRevision) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required.");
        }
        validateExpectedRevision(expectedRevision);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                XrossDbRecord record = write(connection, key, payload, expectedRevision);
                connection.commit();
                return record;
            } catch (Exception exception) {
                connection.rollback();
                throw rethrow("Failed to write " + key, exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new XrossDbException("Failed to open transaction", exception);
        }
    }

    @Override
    public boolean delete(XrossDbKey key, long expectedRevision) {
        validateExpectedRevision(expectedRevision);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean deleted = delete(connection, key, expectedRevision);
                connection.commit();
                return deleted;
            } catch (Exception exception) {
                connection.rollback();
                throw rethrow("Failed to delete " + key, exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new XrossDbException("Failed to open transaction", exception);
        }
    }

    @Override
    public XrossDbPage scanPage(String namespace, String collection, String afterKey, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        String normalizedNamespace = XrossDbKey.validateName(namespace, "namespace");
        String normalizedCollection = XrossDbKey.validateName(collection, "collection");
        String after = afterKey == null || afterKey.isBlank() ? null : afterKey;
        String sql = "SELECT record_key,payload,revision,updated_at FROM " + table()
                + " WHERE namespace=? AND collection_name=?"
                + (after == null ? "" : " AND record_key>?")
                + " ORDER BY record_key LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedNamespace);
            statement.setString(2, normalizedCollection);
            int index = 3;
            if (after != null) {
                statement.setString(index++, after);
            }
            statement.setInt(index, limit + 1);
            List<XrossDbRecord> records = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(record(
                            new XrossDbKey(normalizedNamespace, normalizedCollection, result.getString(1)),
                            result
                    ));
                }
            }
            String next = null;
            if (records.size() > limit) {
                records.remove(records.size() - 1);
                next = records.get(records.size() - 1).key().key();
            }
            return new XrossDbPage(records, next);
        } catch (Exception exception) {
            throw new XrossDbException(
                    "Failed to scan " + normalizedNamespace + "/" + normalizedCollection,
                    exception
            );
        }
    }

    @Override
    public XrossDbBatchResult applyBatch(List<XrossDbMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return new XrossDbBatchResult(List.of(), List.of());
        }
        if (mutations.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("A batch must not exceed " + MAX_BATCH_SIZE + " mutations.");
        }
        mutations.forEach(mutation -> {
            if (mutation == null) {
                throw new IllegalArgumentException("A batch must not contain null mutations.");
            }
            validateExpectedRevision(mutation.expectedRevision());
        });

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<XrossDbRecord> written = new ArrayList<>();
                List<XrossDbKey> deleted = new ArrayList<>();
                for (XrossDbMutation mutation : mutations) {
                    if (mutation.operation() == XrossDbMutation.Operation.WRITE) {
                        written.add(write(
                                connection,
                                mutation.key(),
                                mutation.payload(),
                                mutation.expectedRevision()
                        ));
                    } else if (delete(connection, mutation.key(), mutation.expectedRevision())) {
                        deleted.add(mutation.key());
                    }
                }
                connection.commit();
                return new XrossDbBatchResult(written, deleted);
            } catch (Exception exception) {
                connection.rollback();
                throw rethrow("Failed to apply batch", exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new XrossDbException("Failed to open transaction", exception);
        }
    }

    @Override
    public void close() {
    }

    /**
     * Performs optimistic writes in one PostgreSQL statement. The revision
     * predicate is part of the UPDATE/INSERT itself, so two connections cannot
     * both commit against the same expected revision.
     */
    private XrossDbRecord write(
            Connection connection,
            XrossDbKey key,
            JsonNode payload,
            long expectedRevision
    ) throws Exception {
        long updatedAt = System.currentTimeMillis();
        String json = mapper.writeValueAsString(payload);
        String sql;
        if (expectedRevision == XrossDbClient.ANY_REVISION) {
            sql = "INSERT INTO " + table() + " AS target"
                    + " (namespace,collection_name,record_key,payload,revision,updated_at)"
                    + " VALUES (?,?,?,?::jsonb,1,?)"
                    + " ON CONFLICT (namespace,collection_name,record_key) DO UPDATE"
                    + " SET payload=EXCLUDED.payload,revision=target.revision+1,updated_at=EXCLUDED.updated_at"
                    + " RETURNING payload,revision,updated_at";
        } else if (expectedRevision == 0L) {
            sql = "INSERT INTO " + table()
                    + " (namespace,collection_name,record_key,payload,revision,updated_at)"
                    + " VALUES (?,?,?,?::jsonb,1,?)"
                    + " ON CONFLICT (namespace,collection_name,record_key) DO NOTHING"
                    + " RETURNING payload,revision,updated_at";
        } else {
            sql = "UPDATE " + table()
                    + " SET payload=?::jsonb,revision=revision+1,updated_at=?"
                    + " WHERE namespace=? AND collection_name=? AND record_key=? AND revision=?"
                    + " RETURNING payload,revision,updated_at";
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (expectedRevision == XrossDbClient.ANY_REVISION || expectedRevision == 0L) {
                bind(statement, key);
                statement.setString(4, json);
                statement.setLong(5, updatedAt);
            } else {
                statement.setString(1, json);
                statement.setLong(2, updatedAt);
                statement.setString(3, key.namespace());
                statement.setString(4, key.collection());
                statement.setString(5, key.key());
                statement.setLong(6, expectedRevision);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return record(key, result);
                }
            }
        }

        long currentRevision = revision(connection, key);
        throw conflict(key, expectedRevision, currentRevision);
    }

    private boolean delete(Connection connection, XrossDbKey key, long expectedRevision) throws SQLException {
        String sql = "DELETE FROM " + table()
                + " WHERE namespace=? AND collection_name=? AND record_key=?"
                + (expectedRevision == XrossDbClient.ANY_REVISION ? "" : " AND revision=?")
                + " RETURNING revision";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, key);
            if (expectedRevision != XrossDbClient.ANY_REVISION) {
                statement.setLong(4, expectedRevision);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return true;
                }
            }
        }

        if (expectedRevision == XrossDbClient.ANY_REVISION) {
            return false;
        }
        long currentRevision = revision(connection, key);
        if (expectedRevision == 0L && currentRevision == 0L) {
            return false;
        }
        throw conflict(key, expectedRevision, currentRevision);
    }

    private long revision(Connection connection, XrossDbKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM " + table()
                        + " WHERE namespace=? AND collection_name=? AND record_key=?"
        )) {
            bind(statement, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private void initializeSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            statement.execute("CREATE TABLE IF NOT EXISTS " + table()
                    + " (namespace TEXT NOT NULL, collection_name TEXT NOT NULL,"
                    + " record_key TEXT NOT NULL, payload JSONB NOT NULL,"
                    + " revision BIGINT NOT NULL, updated_at BIGINT NOT NULL,"
                    + " PRIMARY KEY(namespace,collection_name,record_key))");
        } catch (SQLException exception) {
            throw new XrossDbException("Failed to initialize PostgreSQL schema " + schema, exception);
        }
    }

    private String table() {
        return schema + ".xross_records";
    }

    private static void bind(PreparedStatement statement, XrossDbKey key) throws SQLException {
        statement.setString(1, key.namespace());
        statement.setString(2, key.collection());
        statement.setString(3, key.key());
    }

    private XrossDbRecord record(XrossDbKey key, ResultSet result) throws Exception {
        return new XrossDbRecord(
                key,
                mapper.readTree(result.getString("payload")),
                result.getLong("revision"),
                result.getLong("updated_at")
        );
    }

    private static void validateExpectedRevision(long expectedRevision) {
        if (expectedRevision < 0L && expectedRevision != XrossDbClient.ANY_REVISION) {
            throw new IllegalArgumentException("expectedRevision must be non-negative or ANY_REVISION.");
        }
    }

    private static XrossDbConflictException conflict(
            XrossDbKey key,
            long expectedRevision,
            long currentRevision
    ) {
        return new XrossDbConflictException(
                "Revision conflict for " + key + ": expected "
                        + expectedRevision + " but was " + currentRevision
        );
    }

    private static XrossDbException rethrow(String message, Exception exception) {
        return exception instanceof XrossDbException databaseException
                ? databaseException
                : new XrossDbException(message, exception);
    }
}
