package com.ztraqto.openxross.config;

import com.ztraqto.openxross.api.runtime.XrossRole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class XrossArguments {

    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
            "config", "mode", "token", "token-file", "db-bind", "db-url", "db-port", "db-token", "db-backend", "postgres-url", "postgres-user", "postgres-password",
            "shards-total", "shard-ids", "shard-mode", "shard-ready-timeout", "shard-shutdown-timeout",
            "shard-auto-check", "shard-auto-cooldown", "shard-auto-max",
            "cluster-mode", "cluster-id", "node-id", "cluster-max-shards", "cluster-heartbeat",
            "cluster-node-timeout", "cluster-loss-timeout", "cluster-leader-lease", "cluster-reconcile",
            "cluster-formation-delay", "cluster-join-timeout", "cluster-transition-timeout", "cluster-startup-lease",
            "cluster-leader-eligible",
            "system-plugin-dir", "db-provider", "system-plugin-trust-file", "require-system-plugin-trust",
            "editor-url", "editor-secret", "editor-ttl-seconds", "admins", "auto-db-setup", "migrate-local-to-postgres"
    );
    private static final Set<String> FLAG_OPTIONS = Set.of("auto-db-setup", "migrate-local-to-postgres", "require-system-plugin-trust");
    private static final Set<String> SECRET_CLI_OPTIONS = Set.of("token", "db-token", "postgres-password", "editor-secret");

    private XrossArguments() {
    }

    public static XrossConfiguration parse(String[] args) throws IOException {
        Map<String, String> options = parseOptions(args == null ? new String[0] : args);
        warnDeprecatedSecretOptions(options);
        if (options.containsKey("config")) {
            XrossLocalConfiguration.load(Path.of(options.get("config")));
        } else {
            XrossLocalConfiguration.loadDefault();
        }
        String mode = options.getOrDefault("mode", "combined").toLowerCase(Locale.ROOT);

        XrossDbConfiguration.Builder database = XrossDbConfiguration.builder();
        apply(options, "db-bind", database::bindAddress);
        apply(options, "db-url", database::serverUrl);
        apply(options, "db-port", value -> database.port(parseInteger(value, "db-port")));
        apply(options, "db-backend", value -> database.backend(XrossDbBackend.parse(value)));
        apply(options, "postgres-url", database::postgresUrl);
        apply(options, "postgres-user", database::postgresUser);
        apply(options, "postgres-password", database::postgresPassword);
        apply(options, "db-token", database::authToken);
        database.autoSetup(options.containsKey("auto-db-setup"));
        database.migrateLocalToPostgres(options.containsKey("migrate-local-to-postgres"));

        XrossShardConfiguration.Builder shards = XrossShardConfiguration.builder();
        apply(options, "shards-total", value -> shards.totalShards(parseInteger(value, "shards-total")));
        apply(options, "shard-ids", value -> shards.shardIds(parseIntegerList(value, "shard-ids")));
        apply(options, "shard-mode", value -> shards.mode(XrossShardMode.parse(value)));
        apply(options, "shard-ready-timeout", value -> shards.readyTimeoutSeconds(parseInteger(value, "shard-ready-timeout")));
        apply(options, "shard-shutdown-timeout", value -> shards.shutdownTimeoutSeconds(parseInteger(value, "shard-shutdown-timeout")));
        apply(options, "shard-auto-check", value -> shards.autoScaleCheckSeconds(parseInteger(value, "shard-auto-check")));
        apply(options, "shard-auto-cooldown", value -> shards.autoScaleCooldownSeconds(parseInteger(value, "shard-auto-cooldown")));
        apply(options, "shard-auto-max", value -> shards.autoScaleMaxShards(parseInteger(value, "shard-auto-max")));

        XrossClusterConfiguration.Builder cluster = XrossClusterConfiguration.builder();
        apply(options, "cluster-mode", value -> cluster.mode(XrossClusterMode.parse(value)));
        apply(options, "cluster-id", cluster::clusterId);
        apply(options, "node-id", cluster::nodeId);
        apply(options, "cluster-max-shards", value -> cluster.maxShardsPerNode(parseInteger(value, "cluster-max-shards")));
        apply(options, "cluster-heartbeat", value -> cluster.heartbeatSeconds(parseInteger(value, "cluster-heartbeat")));
        apply(options, "cluster-node-timeout", value -> cluster.nodeTimeoutSeconds(parseInteger(value, "cluster-node-timeout")));
        apply(options, "cluster-loss-timeout", value -> cluster.coordinationLossTimeoutSeconds(parseInteger(value, "cluster-loss-timeout")));
        apply(options, "cluster-leader-lease", value -> cluster.leaderLeaseSeconds(parseInteger(value, "cluster-leader-lease")));
        apply(options, "cluster-reconcile", value -> cluster.reconcileSeconds(parseInteger(value, "cluster-reconcile")));
        apply(options, "cluster-formation-delay", value -> cluster.formationDelaySeconds(parseInteger(value, "cluster-formation-delay")));
        apply(options, "cluster-join-timeout", value -> cluster.joinTimeoutSeconds(parseInteger(value, "cluster-join-timeout")));
        apply(options, "cluster-transition-timeout", value -> cluster.transitionTimeoutSeconds(parseInteger(value, "cluster-transition-timeout")));
        apply(options, "cluster-startup-lease", value -> cluster.startupLeaseSeconds(parseInteger(value, "cluster-startup-lease")));
        apply(options, "cluster-leader-eligible", value -> cluster.leaderEligible(parseBoolean(value, "cluster-leader-eligible")));

        XrossSystemPluginConfiguration.Builder systemPlugins = XrossSystemPluginConfiguration.builder();
        apply(options, "system-plugin-dir", systemPlugins::directory);
        apply(options, "db-provider", systemPlugins::databaseProvider);
        apply(options, "system-plugin-trust-file", systemPlugins::trustFile);
        if (options.containsKey("require-system-plugin-trust")) {
            systemPlugins.requireTrustedFingerprints(true);
        }

        XrossEditorConfiguration.Builder editor = XrossEditorConfiguration.builder();
        apply(options, "editor-url", editor::editorUrl);
        apply(options, "editor-secret", editor::signingSecret);
        apply(options, "editor-ttl-seconds", value -> editor.sessionTtl(
                Duration.ofSeconds(parseInteger(value, "editor-ttl-seconds"))
        ));

        XrossConfiguration.Builder configuration = XrossConfiguration.builder()
                .database(database.build())
                .shards(shards.build())
                .cluster(cluster.build())
                .systemPlugins(systemPlugins.build())
                .editor(editor.build());

        if (options.containsKey("admins")) {
            configuration.botAdministratorIds(parseLongList(options.get("admins"), "admins"));
        }

        switch (mode) {
            case "bot" -> configuration.roles(XrossRole.BOT);
            case "database", "db", "xrossdb" -> configuration.roles(XrossRole.DATABASE);
            case "combined", "both" -> configuration.roles(XrossRole.BOT, XrossRole.DATABASE);
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        }

        if (!"database".equals(mode) && !"db".equals(mode) && !"xrossdb".equals(mode)) {
            configuration.discordToken(loadDiscordToken(options));
        } else {
            configuration.discordToken(null);
        }

        return configuration.build();
    }

    public static String usage() {
        return String.join(System.lineSeparator(),
                "Xross Engine options:",
                "  --config <path> (default: " + XrossLocalConfiguration.DEFAULT_FILE_NAME + ")",
                "  --mode <combined|bot|database>",
                "  Prefer DISCORD_TOKEN_FILE / DISCORD_TOKEN / discord.token; --token is deprecated",
                "  --db-bind <address> --db-url <url> --db-port <port>",
                "  --db-backend <postgres|local|memory>",
                "  Prefer XROSS_DB_TOKEN_FILE / XROSS_DB_TOKEN / database.authToken; --db-token is deprecated",
                "  --postgres-url <jdbc-url> --postgres-user <user>; prefer XROSS_POSTGRES_PASSWORD_FILE for the password",
                "  --auto-db-setup (create the configured PostgreSQL role and database if missing)",
                "  --migrate-local-to-postgres (copy local XrossDB data without overwriting conflicts)",
                "  --shards-total <count> --shard-ids <0,1,...>",
                "  --shard-mode <fixed|recommended|auto-scale>",
                "  --shard-ready-timeout <seconds> --shard-shutdown-timeout <seconds>",
                "  --shard-auto-check <seconds> --shard-auto-cooldown <seconds> --shard-auto-max <count|0>",
                "  --cluster-mode <off|static|auto> --cluster-id <id> --node-id <unique-node-id>",
                "  --cluster-max-shards <count|0> --cluster-heartbeat <seconds> --cluster-reconcile <seconds>",
                "  --cluster-node-timeout <seconds> --cluster-loss-timeout <seconds>",
                "  --system-plugin-dir <path> --db-provider <xrossdb|provider-id>",
                "  --system-plugin-trust-file <path> --require-system-plugin-trust",
                "  --editor-url <url>; prefer XROSS_EDITOR_SECRET_FILE / XROSS_EDITOR_SECRET for the secret",
                "  --admins <discord-user-id,...>"
        );
    }

    private static Map<String, String> parseOptions(String[] args) {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }

            String withoutPrefix = argument.substring(2);
            String key;
            String value;
            int equalsIndex = withoutPrefix.indexOf('=');
            if (equalsIndex >= 0) {
                key = withoutPrefix.substring(0, equalsIndex);
                value = withoutPrefix.substring(equalsIndex + 1);
            } else {
                key = withoutPrefix;
                if (FLAG_OPTIONS.contains(key)) {
                    value = "true";
                } else if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --" + key);
                } else {
                    value = args[++index];
                }
            }

            if (!SUPPORTED_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("Unknown option: --" + key);
            }
            if (FLAG_OPTIONS.contains(key) && !"true".equals(value)) {
                throw new IllegalArgumentException("--" + key + " does not accept a value.");
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("Empty value for --" + key);
            }
            if (options.putIfAbsent(key, value.trim()) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + key);
            }
        }
        return options;
    }

    private static String loadDiscordToken(Map<String, String> options) throws IOException {
        String explicitToken = options.get("token");
        if (explicitToken != null) {
            return explicitToken;
        }

        String configuredToken = XrossLocalConfiguration.secret("discord.token", "DISCORD_TOKEN", "DISCORD_TOKEN_FILE");
        if (configuredToken != null) {
            return configuredToken;
        }

        Path tokenFile = Path.of(options.getOrDefault("token-file", "key.xet")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(tokenFile)) {
            throw new IOException("Discord token was not found. Set discord.token in "
                    + XrossLocalConfiguration.DEFAULT_FILE_NAME + " or provide --token-file.");
        }

        String token = Files.readString(tokenFile).trim();
        if (token.isEmpty()) {
            throw new IOException("Discord token file is empty: " + tokenFile);
        }
        return token;
    }


    private static void warnDeprecatedSecretOptions(Map<String, String> options) {
        for (String key : SECRET_CLI_OPTIONS) {
            if (options.containsKey(key)) {
                System.err.println("[OpenXross security] --" + key
                        + " is deprecated because command-line secrets may be exposed by process listings or shell history. "
                        + "Use the documented environment or *_FILE secret source instead.");
            }
        }
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false.");
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer.", exception);
        }
    }

    private static int[] parseIntegerList(String value, String name) {
        List<Integer> values = new ArrayList<>();
        for (String part : value.split(",")) {
            values.add(parseInteger(part.trim(), name));
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static long[] parseLongList(String value, String name) {
        List<Long> values = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                values.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must contain Discord numeric IDs.", exception);
            }
        }
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private static void apply(Map<String, String> options, String key, java.util.function.Consumer<String> consumer) {
        String value = options.get(key);
        if (value != null) {
            consumer.accept(value);
        }
    }
}
