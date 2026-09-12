package com.ztraqto.openxross.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class XrossShardConfiguration {

    private final XrossShardMode mode;
    private final int totalShards;
    private final Set<Integer> shardIds;
    private final boolean explicitShardIds;
    private final boolean coordinatedAssignment;
    private final int readyTimeoutSeconds;
    private final int shutdownTimeoutSeconds;
    private final int autoScaleCheckSeconds;
    private final int autoScaleCooldownSeconds;
    private final int autoScaleMaxShards;

    private XrossShardConfiguration(Builder builder) {
        if (builder.totalShards < 1) {
            throw new IllegalArgumentException("totalShards must be at least 1.");
        }
        if (builder.mode == null) {
            throw new IllegalArgumentException("Shard mode is required.");
        }

        LinkedHashSet<Integer> validatedIds = new LinkedHashSet<>();
        for (int shardId : builder.shardIds) {
            if (shardId < 0 || shardId >= builder.totalShards) {
                throw new IllegalArgumentException(
                        "Shard id " + shardId + " must be between 0 and " + (builder.totalShards - 1) + "."
                );
            }
            if (!validatedIds.add(shardId)) {
                throw new IllegalArgumentException("Duplicate shard id: " + shardId);
            }
        }

        if (builder.mode != XrossShardMode.FIXED && builder.explicitShardIds && !builder.coordinatedAssignment) {
            throw new IllegalArgumentException(
                    "RECOMMENDED/AUTO_SCALE require one process to own the complete shard set. "
                            + "Use FIXED mode for explicit shard IDs or enable coordinated assignment through Xross Orchestrator."
            );
        }

        if (validatedIds.isEmpty()) {
            for (int shardId = 0; shardId < builder.totalShards; shardId++) {
                validatedIds.add(shardId);
            }
        }

        if (builder.readyTimeoutSeconds < 1) {
            throw new IllegalArgumentException("readyTimeoutSeconds must be at least 1.");
        }
        if (builder.shutdownTimeoutSeconds < 1) {
            throw new IllegalArgumentException("shutdownTimeoutSeconds must be at least 1.");
        }
        if (builder.autoScaleCheckSeconds < 60) {
            throw new IllegalArgumentException("autoScaleCheckSeconds must be at least 60.");
        }
        if (builder.autoScaleCooldownSeconds < 60) {
            throw new IllegalArgumentException("autoScaleCooldownSeconds must be at least 60.");
        }
        if (builder.autoScaleMaxShards < 0) {
            throw new IllegalArgumentException("autoScaleMaxShards cannot be negative.");
        }
        if (builder.autoScaleMaxShards > 0 && builder.autoScaleMaxShards < builder.totalShards) {
            throw new IllegalArgumentException("autoScaleMaxShards cannot be lower than totalShards.");
        }

        this.mode = builder.mode;
        this.totalShards = builder.totalShards;
        this.shardIds = Collections.unmodifiableSet(new LinkedHashSet<>(validatedIds));
        this.explicitShardIds = builder.explicitShardIds;
        this.coordinatedAssignment = builder.coordinatedAssignment;
        this.readyTimeoutSeconds = builder.readyTimeoutSeconds;
        this.shutdownTimeoutSeconds = builder.shutdownTimeoutSeconds;
        this.autoScaleCheckSeconds = builder.autoScaleCheckSeconds;
        this.autoScaleCooldownSeconds = builder.autoScaleCooldownSeconds;
        this.autoScaleMaxShards = builder.autoScaleMaxShards;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static XrossShardConfiguration single() {
        return builder().build();
    }

    public XrossShardMode mode() {
        return mode;
    }

    public boolean autoScaleEnabled() {
        return mode == XrossShardMode.AUTO_SCALE;
    }

    public int totalShards() {
        return totalShards;
    }

    public Set<Integer> shardIds() {
        return shardIds;
    }

    public boolean usesExplicitShardIds() {
        return explicitShardIds;
    }

    public boolean coordinatedAssignment() {
        return coordinatedAssignment;
    }

    public boolean isDistributed() {
        return totalShards > 1 || shardIds.size() < totalShards;
    }

    public int readyTimeoutSeconds() {
        return readyTimeoutSeconds;
    }

    public int shutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public int autoScaleCheckSeconds() {
        return autoScaleCheckSeconds;
    }

    public int autoScaleCooldownSeconds() {
        return autoScaleCooldownSeconds;
    }

    /** 0 means no OpenXross-side ceiling. Discord may still impose its own requirements. */
    public int autoScaleMaxShards() {
        return autoScaleMaxShards;
    }

    public int clampAutoScaleTarget(int recommendedShards) {
        if (recommendedShards < 1) {
            throw new IllegalArgumentException("recommendedShards must be positive.");
        }
        return autoScaleMaxShards > 0 ? Math.min(recommendedShards, autoScaleMaxShards) : recommendedShards;
    }

    /** Discord deterministic guild-to-shard formula. */
    public int shardIdForGuild(long guildId) {
        if (guildId <= 0L) {
            throw new IllegalArgumentException("guildId must be a positive Discord snowflake.");
        }
        return (int) ((guildId >>> 22) % totalShards);
    }

    /** Whether this process is configured to own the guild's shard. */
    public boolean ownsGuild(long guildId) {
        return shardIds.contains(shardIdForGuild(guildId));
    }

    public int[] shardIdArray() {
        return shardIds.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Copy used when Discord resolves a new all-shards topology. */
    public XrossShardConfiguration withResolvedTotalShards(int resolvedTotal) {
        Builder builder = builder()
                .mode(mode)
                .totalShards(resolvedTotal)
                .shardIds()
                .readyTimeoutSeconds(readyTimeoutSeconds)
                .shutdownTimeoutSeconds(shutdownTimeoutSeconds)
                .autoScaleCheckSeconds(autoScaleCheckSeconds)
                .autoScaleCooldownSeconds(autoScaleCooldownSeconds)
                .autoScaleMaxShards(autoScaleMaxShards);
        return builder.build();
    }


    /** Assignment resolved by the OpenXross 1.2 Xross Orchestrator. */
    public XrossShardConfiguration withClusterAssignment(int resolvedTotal, int[] assignedShardIds) {
        return builder()
                .mode(mode)
                .totalShards(resolvedTotal)
                .shardIds(assignedShardIds)
                .coordinatedAssignment(true)
                .readyTimeoutSeconds(readyTimeoutSeconds)
                .shutdownTimeoutSeconds(shutdownTimeoutSeconds)
                .autoScaleCheckSeconds(autoScaleCheckSeconds)
                .autoScaleCooldownSeconds(autoScaleCooldownSeconds)
                .autoScaleMaxShards(autoScaleMaxShards)
                .build();
    }

    public static final class Builder {
        private XrossShardMode mode = XrossShardMode.parse(
                XrossLocalConfiguration.string("shards.mode", "XROSS_SHARD_MODE", "fixed")
        );
        private int totalShards = (int) XrossLocalConfiguration.longValue("shards.totalShards", "XROSS_SHARDS_TOTAL", 1L);
        private int[] shardIds;
        private boolean explicitShardIds;
        private boolean coordinatedAssignment;
        private int readyTimeoutSeconds = (int) XrossLocalConfiguration.longValue("shards.readyTimeoutSeconds", "XROSS_SHARD_READY_TIMEOUT_SECONDS", 120L);
        private int shutdownTimeoutSeconds = (int) XrossLocalConfiguration.longValue("shards.shutdownTimeoutSeconds", "XROSS_SHARD_SHUTDOWN_TIMEOUT_SECONDS", 5L);
        private int autoScaleCheckSeconds = (int) XrossLocalConfiguration.longValue("shards.autoScaleCheckSeconds", "XROSS_SHARD_AUTO_CHECK_SECONDS", 300L);
        private int autoScaleCooldownSeconds = (int) XrossLocalConfiguration.longValue("shards.autoScaleCooldownSeconds", "XROSS_SHARD_AUTO_COOLDOWN_SECONDS", 900L);
        private int autoScaleMaxShards = (int) XrossLocalConfiguration.longValue("shards.autoScaleMaxShards", "XROSS_SHARD_AUTO_MAX", 0L);

        private Builder() {
            int[] configured = configuredShardIds();
            this.shardIds = configured;
            this.explicitShardIds = configured.length > 0;
        }

        public Builder mode(XrossShardMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder totalShards(int totalShards) {
            this.totalShards = totalShards;
            return this;
        }

        public Builder shardIds(int... shardIds) {
            this.shardIds = shardIds == null ? new int[0] : Arrays.copyOf(shardIds, shardIds.length);
            this.explicitShardIds = this.shardIds.length > 0;
            return this;
        }

        Builder coordinatedAssignment(boolean value) {
            this.coordinatedAssignment = value;
            return this;
        }

        public Builder shardRange(int firstInclusive, int lastInclusive) {
            if (firstInclusive < 0 || lastInclusive < firstInclusive) {
                throw new IllegalArgumentException("Invalid shard range: " + firstInclusive + ".." + lastInclusive);
            }
            this.shardIds = java.util.stream.IntStream.rangeClosed(firstInclusive, lastInclusive).toArray();
            this.explicitShardIds = true;
            return this;
        }

        public Builder readyTimeoutSeconds(int readyTimeoutSeconds) {
            this.readyTimeoutSeconds = readyTimeoutSeconds;
            return this;
        }

        public Builder shutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
            this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
            return this;
        }

        public Builder autoScaleCheckSeconds(int seconds) {
            this.autoScaleCheckSeconds = seconds;
            return this;
        }

        public Builder autoScaleCooldownSeconds(int seconds) {
            this.autoScaleCooldownSeconds = seconds;
            return this;
        }

        public Builder autoScaleMaxShards(int maxShards) {
            this.autoScaleMaxShards = maxShards;
            return this;
        }

        public XrossShardConfiguration build() {
            return new XrossShardConfiguration(this);
        }

        private static int[] configuredShardIds() {
            List<String> configured = XrossLocalConfiguration.strings("shards.shardIds", "XROSS_SHARD_IDS");
            if (configured.isEmpty()) return new int[0];
            int[] values = new int[configured.size()];
            for (int index = 0; index < configured.size(); index++) {
                try {
                    values[index] = Integer.parseInt(configured.get(index));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("shards.shardIds must contain integers.", exception);
                }
            }
            return values;
        }
    }
}
