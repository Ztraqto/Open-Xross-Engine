package com.ztraqto.openxross.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Bootstrap configuration for privileged OpenXross system plugins.
 *
 * <p>System plugins are loaded before internal services and Discord. They are
 * therefore trusted infrastructure code and are intentionally separated from
 * normal application plugins.</p>
 */
public final class XrossSystemPluginConfiguration {

    public static final String BUILTIN_XROSSDB_PROVIDER = "xrossdb";

    private final Path directory;
    private final String databaseProvider;
    private final boolean requireTrustedFingerprints;
    private final Path trustFile;

    private XrossSystemPluginConfiguration(Builder builder) {
        this.directory = Objects.requireNonNull(builder.directory, "directory").toAbsolutePath().normalize();
        this.databaseProvider = normalizeProvider(builder.databaseProvider);
        this.requireTrustedFingerprints = builder.requireTrustedFingerprints;
        this.trustFile = Objects.requireNonNull(builder.trustFile, "trustFile").toAbsolutePath().normalize();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path directory() {
        return directory;
    }

    public String databaseProvider() {
        return databaseProvider;
    }

    public boolean usesBuiltinXrossDb() {
        return BUILTIN_XROSSDB_PROVIDER.equals(databaseProvider);
    }

    public boolean requireTrustedFingerprints() {
        return requireTrustedFingerprints;
    }

    public Path trustFile() {
        return trustFile;
    }

    private static String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return BUILTIN_XROSSDB_PROVIDER;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("System database provider id is invalid: " + value);
        }
        return normalized;
    }

    public static final class Builder {
        private Path directory = Path.of(XrossLocalConfiguration.string(
                "systemPlugins.directory",
                "XROSS_SYSTEM_PLUGIN_DIR",
                "system-plugins"
        ));
        private String databaseProvider = XrossLocalConfiguration.string(
                "systemPlugins.databaseProvider",
                "XROSS_DB_PROVIDER",
                BUILTIN_XROSSDB_PROVIDER
        );
        private boolean requireTrustedFingerprints = XrossLocalConfiguration.bool(
                "systemPlugins.requireTrustedFingerprints",
                "XROSS_SYSTEM_PLUGIN_REQUIRE_TRUST",
                true
        );
        private Path trustFile = Path.of(XrossLocalConfiguration.string(
                "systemPlugins.trustFile",
                "XROSS_SYSTEM_PLUGIN_TRUST_FILE",
                "system-plugins/trusted.sha256"
        ));

        private Builder() {
        }

        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        public Builder directory(String directory) {
            this.directory = Path.of(directory);
            return this;
        }

        public Builder databaseProvider(String databaseProvider) {
            this.databaseProvider = databaseProvider;
            return this;
        }

        public Builder requireTrustedFingerprints(boolean requireTrustedFingerprints) {
            this.requireTrustedFingerprints = requireTrustedFingerprints;
            return this;
        }

        public Builder trustFile(Path trustFile) {
            this.trustFile = trustFile;
            return this;
        }

        public Builder trustFile(String trustFile) {
            this.trustFile = Path.of(trustFile);
            return this;
        }

        public XrossSystemPluginConfiguration build() {
            return new XrossSystemPluginConfiguration(this);
        }
    }
}
