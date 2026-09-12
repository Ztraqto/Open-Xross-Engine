package com.ztraqto.openxross.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class XrossDbConfiguration {

    public static final int DEFAULT_PORT = 7420;

    private final String bindAddress;
    private final String serverUrl;
    private final int port;
    private final XrossDbBackend backend;
    private final String postgresUrl;
    private final String postgresUser;
    private final String postgresPassword;
    private final boolean autoSetup;
    private final String setupAdminUrl;
    private final String setupAdminUser;
    private final String setupAdminPassword;
    private final String localPath;
    private final boolean migrateLocalToPostgres;
    private final String authToken;
    private final boolean allowInsecureExternalHttp;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int readRetryCount;

    private XrossDbConfiguration(Builder builder) {
        this.bindAddress = requireText(builder.bindAddress, "bindAddress");
        this.serverUrl = normalizeServerUrl(builder.serverUrl);
        this.port = validatePort(builder.port);
        this.backend = Objects.requireNonNull(builder.backend, "backend");
        this.postgresUrl = backend == XrossDbBackend.POSTGRES
                ? requireText(builder.postgresUrl, "postgresUrl") : normalizeOptional(builder.postgresUrl);
        this.postgresUser = backend == XrossDbBackend.POSTGRES
                ? requireText(builder.postgresUser, "postgresUser") : normalizeOptional(builder.postgresUser);
        this.postgresPassword = normalizeOptional(builder.postgresPassword);
        this.autoSetup = builder.autoSetup;
        this.setupAdminUrl = normalizeOptional(builder.setupAdminUrl);
        this.setupAdminUser = requireText(builder.setupAdminUser, "setupAdminUser");
        this.setupAdminPassword = normalizeOptional(builder.setupAdminPassword);
        this.localPath = requireText(builder.localPath, "localPath");
        this.migrateLocalToPostgres = builder.migrateLocalToPostgres;
        this.authToken = normalizeOptional(builder.authToken);
        this.allowInsecureExternalHttp = builder.allowInsecureExternalHttp;
        this.connectTimeout = requirePositive(builder.connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(builder.requestTimeout, "requestTimeout");
        this.readRetryCount = validateRetryCount(builder.readRetryCount);
        if (autoSetup && backend != XrossDbBackend.POSTGRES) {
            throw new IllegalArgumentException("--auto-db-setup requires database.backend=postgres.");
        }
        if (migrateLocalToPostgres && backend != XrossDbBackend.POSTGRES) {
            throw new IllegalArgumentException("--migrate-local-to-postgres requires database.backend=postgres.");
        }
        if (authToken != null && authToken.length() < 32) {
            throw new IllegalArgumentException("XROSS_DB_TOKEN must contain at least 32 characters.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String bindAddress() {
        return bindAddress;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public int port() {
        return port;
    }

    public XrossDbBackend backend() {
        return backend;
    }

    public String postgresUrl() {
        return postgresUrl;
    }

    public String postgresUser() {
        return postgresUser;
    }

    public String postgresPassword() {
        return postgresPassword;
    }

    public boolean autoSetup() {
        return autoSetup;
    }

    public String setupAdminUrl() {
        return setupAdminUrl;
    }

    public String setupAdminUser() {
        return setupAdminUser;
    }

    public String setupAdminPassword() {
        return setupAdminPassword;
    }

    public String localPath() {
        return localPath;
    }

    public boolean migrateLocalToPostgres() {
        return migrateLocalToPostgres;
    }

    public String authToken() {
        return authToken;
    }

    public boolean allowInsecureExternalHttp() {
        return allowInsecureExternalHttp;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int readRetryCount() {
        return readRetryCount;
    }

    public URI serverBaseUri() {
        return URI.create(serverUrl + ":" + port);
    }

    public void validateServerSecurity() {
        if (!isLoopback(bindAddress) && !allowInsecureExternalHttp) {
            throw new IllegalArgumentException(
                    "Direct non-loopback XrossDB HTTP is disabled by default. "
                            + "Bind to loopback and place a TLS reverse proxy in front, or explicitly set "
                            + "database.allowInsecureExternalHttp=true only on a trusted private network."
            );
        }
        if (!isLoopback(bindAddress) && authToken == null) {
            throw new IllegalArgumentException("XrossDB authentication is required when binding outside loopback.");
        }
    }

    public void validateRemoteClient() {
        if (authToken == null) {
            throw new IllegalArgumentException("XrossDB authentication is required in remote Bot mode.");
        }
        URI uri = serverBaseUri();
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException(
                    "Remote XrossDB connections must use HTTPS so the bearer token is not sent in cleartext."
            );
        }
    }

    private static String normalizeServerUrl(String value) {
        String normalized = requireText(value, "serverUrl");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        URI uri = URI.create(normalized);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("serverUrl must include a scheme and host, for example http://xrossdb.internal");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("serverUrl must use http or https.");
        }
        if (uri.getPort() != -1) {
            throw new IllegalArgumentException("Specify the XrossDB port with port(), not inside serverUrl.");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("serverUrl must not contain a path.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("serverUrl must not contain credentials, a query or a fragment.");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value.trim();
    }

    private static int validatePort(int value) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535.");
        }
        return value;
    }

    private static int validateRetryCount(int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException("readRetryCount must be between 0 and 10.");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        String normalized = address;
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "127.0.0.1".equals(normalized)
                || "localhost".equalsIgnoreCase(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    public static final class Builder {
        private String bindAddress = XrossLocalConfiguration.string(
                "database.bindAddress", "XROSS_DB_BIND", "127.0.0.1");
        private String serverUrl = XrossLocalConfiguration.string(
                "database.serverUrl", "XROSS_DB_URL", "http://127.0.0.1");
        private int port = Math.toIntExact(XrossLocalConfiguration.longValue(
                "database.port", "XROSS_DB_PORT", DEFAULT_PORT));
        private XrossDbBackend backend = XrossDbBackend.parse(
                XrossLocalConfiguration.string("database.backend", "XROSS_DB_BACKEND", "postgres"));
        private String postgresUrl = XrossLocalConfiguration.string("database.postgresUrl", "XROSS_POSTGRES_URL", "jdbc:postgresql://localhost:7654/xross");
        private String postgresUser = XrossLocalConfiguration.string("database.postgresUser", "XROSS_POSTGRES_USER", "xross_bot");
        private String postgresPassword = XrossLocalConfiguration.secret("database.postgresPassword", "XROSS_POSTGRES_PASSWORD", "XROSS_POSTGRES_PASSWORD_FILE");
        private boolean autoSetup;
        private String setupAdminUrl = XrossLocalConfiguration.string(
                "database.setup.adminUrl", "XROSS_POSTGRES_ADMIN_URL");
        private String setupAdminUser = XrossLocalConfiguration.string(
                "database.setup.adminUser", "XROSS_POSTGRES_ADMIN_USER", "postgres");
        private String setupAdminPassword = XrossLocalConfiguration.secret(
                "database.setup.adminPassword", "XROSS_POSTGRES_ADMIN_PASSWORD", "XROSS_POSTGRES_ADMIN_PASSWORD_FILE");
        private String localPath = XrossLocalConfiguration.string(
                "database.localPath", "XROSS_LOCAL_DB_PATH", "data/xross-local.json");
        private boolean migrateLocalToPostgres;
        private String authToken = XrossLocalConfiguration.secret("database.authToken", "XROSS_DB_TOKEN", "XROSS_DB_TOKEN_FILE");
        private boolean allowInsecureExternalHttp = XrossLocalConfiguration.bool(
                "database.allowInsecureExternalHttp", "XROSS_DB_ALLOW_INSECURE_EXTERNAL_HTTP", false);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private int readRetryCount = 2;

        private Builder() {
        }

        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder backend(XrossDbBackend backend) {
            this.backend = backend;
            return this;
        }

        public Builder postgresUrl(String postgresUrl) {
            this.postgresUrl = postgresUrl;
            return this;
        }

        public Builder postgresUser(String postgresUser) {
            this.postgresUser = postgresUser;
            return this;
        }

        public Builder postgresPassword(String postgresPassword) {
            this.postgresPassword = postgresPassword;
            return this;
        }

        public Builder autoSetup(boolean autoSetup) {
            this.autoSetup = autoSetup;
            return this;
        }

        public Builder setupAdminUrl(String setupAdminUrl) {
            this.setupAdminUrl = setupAdminUrl;
            return this;
        }

        public Builder setupAdminUser(String setupAdminUser) {
            this.setupAdminUser = setupAdminUser;
            return this;
        }

        public Builder setupAdminPassword(String setupAdminPassword) {
            this.setupAdminPassword = setupAdminPassword;
            return this;
        }

        public Builder localPath(String localPath) {
            this.localPath = localPath;
            return this;
        }

        public Builder migrateLocalToPostgres(boolean migrateLocalToPostgres) {
            this.migrateLocalToPostgres = migrateLocalToPostgres;
            return this;
        }

        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder allowInsecureExternalHttp(boolean allowInsecureExternalHttp) {
            this.allowInsecureExternalHttp = allowInsecureExternalHttp;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder readRetryCount(int readRetryCount) {
            this.readRetryCount = readRetryCount;
            return this;
        }

        public XrossDbConfiguration build() {
            return new XrossDbConfiguration(this);
        }
    }
}
