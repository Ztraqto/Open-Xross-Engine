package com.ztraqto.openxross.config;

import com.ztraqto.openxross.api.runtime.XrossRole;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class XrossConfiguration {

    private final Set<XrossRole> roles;
    private final String discordToken;
    private final XrossDbConfiguration database;
    private final XrossShardConfiguration shards;
    private final XrossEditorConfiguration editor;
    private final XrossProductConfiguration product;
    private final XrossSystemPluginConfiguration systemPlugins;
    private final XrossClusterConfiguration cluster;
    private final Set<Long> botAdministratorIds;

    private XrossConfiguration(Builder builder) {
        if (builder.roles.isEmpty()) {
            throw new IllegalArgumentException("At least one Xross role is required.");
        }
        this.roles = Collections.unmodifiableSet(EnumSet.copyOf(builder.roles));
        this.discordToken = normalizeOptional(builder.discordToken);
        this.database = Objects.requireNonNull(builder.database, "database");
        this.shards = Objects.requireNonNull(builder.shards, "shards");
        this.editor = Objects.requireNonNull(builder.editor, "editor");
        this.product = Objects.requireNonNull(builder.product, "product");
        this.systemPlugins = Objects.requireNonNull(builder.systemPlugins, "systemPlugins");
        this.cluster = Objects.requireNonNull(builder.cluster, "cluster");
        this.botAdministratorIds = Collections.unmodifiableSet(new LinkedHashSet<>(builder.botAdministratorIds));

        if (cluster.autoManaged() && !roles.contains(XrossRole.BOT)) {
            throw new IllegalArgumentException("AUTO cluster mode requires the BOT role.");
        }
        if (cluster.autoManaged()
                && roles.contains(XrossRole.DATABASE)
                && systemPlugins.usesBuiltinXrossDb()
                && database.backend() != XrossDbBackend.POSTGRES) {
            throw new IllegalArgumentException(
                    "AUTO cluster mode requires a shared coordination store. "
                            + "Combined BOT+DATABASE nodes using built-in XrossDB must use database.backend=postgres; "
                            + "otherwise use a shared remote XrossDB or an external XrossDbClientProvider."
            );
        }
        if (roles.contains(XrossRole.BOT) && discordToken == null) {
            throw new IllegalArgumentException("Discord token is required in Bot mode.");
        }
        if (roles.contains(XrossRole.DATABASE)) {
            if (!systemPlugins.usesBuiltinXrossDb()) {
                throw new IllegalArgumentException(
                        "The DATABASE role hosts built-in XrossDB and cannot be combined with an external database provider. "
                                + "Use BOT-only mode when systemPlugins.databaseProvider is not xrossdb."
                );
            }
            database.validateServerSecurity();
        }
        if (roles.contains(XrossRole.BOT) && !roles.contains(XrossRole.DATABASE) && systemPlugins.usesBuiltinXrossDb()) {
            if (database.backend() == XrossDbBackend.MEMORY) {
                throw new IllegalArgumentException("The memory database backend requires combined or database mode.");
            }
            if (database.autoSetup()) {
                throw new IllegalArgumentException("--auto-db-setup requires combined or database mode.");
            }
            if (database.migrateLocalToPostgres()) {
                throw new IllegalArgumentException("--migrate-local-to-postgres requires combined or database mode.");
            }
            database.validateRemoteClient();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static XrossConfiguration combined(String discordToken) {
        return builder()
                .roles(XrossRole.BOT, XrossRole.DATABASE)
                .discordToken(discordToken)
                .build();
    }

    public static XrossConfiguration combined(String discordToken, XrossDbConfiguration database) {
        return builder()
                .roles(XrossRole.BOT, XrossRole.DATABASE)
                .discordToken(discordToken)
                .database(database)
                .build();
    }

    public static XrossConfiguration bot(String discordToken, XrossDbConfiguration database) {
        return builder()
                .roles(XrossRole.BOT)
                .discordToken(discordToken)
                .database(database)
                .build();
    }

    public static XrossConfiguration database(XrossDbConfiguration database) {
        return builder()
                .roles(XrossRole.DATABASE)
                .discordToken(null)
                .database(database)
                .build();
    }

    public Set<XrossRole> roles() {
        return roles;
    }

    public boolean hasRole(XrossRole role) {
        return roles.contains(role);
    }

    public String discordToken() {
        return discordToken;
    }

    public XrossDbConfiguration database() {
        return database;
    }

    public XrossShardConfiguration shards() {
        return shards;
    }

    public XrossEditorConfiguration editor() {
        return editor;
    }

    public XrossProductConfiguration product() {
        return product;
    }

    public XrossSystemPluginConfiguration systemPlugins() {
        return systemPlugins;
    }

    public XrossClusterConfiguration cluster() {
        return cluster;
    }

    public XrossConfiguration withProduct(XrossProductConfiguration product) {
        return builder()
                .roles(roles.toArray(XrossRole[]::new))
                .discordToken(discordToken)
                .database(database)
                .shards(shards)
                .editor(editor)
                .product(product)
                .systemPlugins(systemPlugins)
                .cluster(cluster)
                .botAdministratorIds(botAdministratorIds.stream().mapToLong(Long::longValue).toArray())
                .build();
    }

    public XrossConfiguration withShards(XrossShardConfiguration shards) {
        return builder()
                .roles(roles.toArray(XrossRole[]::new))
                .discordToken(discordToken)
                .database(database)
                .shards(Objects.requireNonNull(shards, "shards"))
                .editor(editor)
                .product(product)
                .systemPlugins(systemPlugins)
                .cluster(cluster)
                .botAdministratorIds(botAdministratorIds.stream().mapToLong(Long::longValue).toArray())
                .build();
    }

    public Set<Long> botAdministratorIds() {
        return botAdministratorIds;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Builder {
        private EnumSet<XrossRole> roles = EnumSet.of(XrossRole.BOT, XrossRole.DATABASE);
        private String discordToken = XrossLocalConfiguration.secret("discord.token", "DISCORD_TOKEN", "DISCORD_TOKEN_FILE");
        private XrossDbConfiguration database = XrossDbConfiguration.builder().build();
        private XrossShardConfiguration shards = XrossShardConfiguration.single();
        private XrossEditorConfiguration editor = XrossEditorConfiguration.builder().build();
        private XrossProductConfiguration product = XrossProductConfiguration.openXross();
        private XrossSystemPluginConfiguration systemPlugins = XrossSystemPluginConfiguration.builder().build();
        private XrossClusterConfiguration cluster = XrossClusterConfiguration.builder().build();
        private Set<Long> botAdministratorIds = parseAdministratorIds(
                XrossLocalConfiguration.strings("discord.administratorIds", "XROSS_ADMIN_USER_IDS")
        );

        private Builder() {
        }

        public Builder roles(XrossRole... roles) {
            Objects.requireNonNull(roles, "roles");
            if (roles.length == 0) {
                throw new IllegalArgumentException("At least one Xross role is required.");
            }
            this.roles = EnumSet.copyOf(Arrays.asList(roles));
            return this;
        }

        public Builder discordToken(String discordToken) {
            this.discordToken = discordToken;
            return this;
        }

        public Builder database(XrossDbConfiguration database) {
            this.database = database;
            return this;
        }

        public Builder shards(XrossShardConfiguration shards) {
            this.shards = shards;
            return this;
        }

        public Builder editor(XrossEditorConfiguration editor) {
            this.editor = editor;
            return this;
        }

        public Builder product(XrossProductConfiguration product) {
            this.product = product;
            return this;
        }

        public Builder systemPlugins(XrossSystemPluginConfiguration systemPlugins) {
            this.systemPlugins = systemPlugins;
            return this;
        }

        public Builder cluster(XrossClusterConfiguration cluster) {
            this.cluster = cluster;
            return this;
        }

        public Builder botAdministratorIds(long... userIds) {
            LinkedHashSet<Long> values = new LinkedHashSet<>();
            if (userIds != null) {
                for (long userId : userIds) {
                    if (userId <= 0L) {
                        throw new IllegalArgumentException("Bot administrator IDs must be positive Discord IDs.");
                    }
                    values.add(userId);
                }
            }
            this.botAdministratorIds = values;
            return this;
        }

        public XrossConfiguration build() {
            return new XrossConfiguration(this);
        }

        private static Set<Long> parseAdministratorIds(Iterable<String> configuredValues) {
            LinkedHashSet<Long> values = new LinkedHashSet<>();
            for (String part : configuredValues) {
                String normalized = part.trim();
                if (!normalized.isEmpty()) {
                    values.add(Long.parseLong(normalized));
                }
            }
            return values;
        }
    }
}
