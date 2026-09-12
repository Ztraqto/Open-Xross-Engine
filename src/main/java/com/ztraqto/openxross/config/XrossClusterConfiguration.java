package com.ztraqto.openxross.config;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Configuration for OpenXross multi-process sharding.
 *
 * <p>AUTO mode uses the configured shared XrossDbClient as the persistence layer
 * for Xross Orchestrator. Every machine can run the same configuration; only
 * nodeId needs to be unique when hostnames are not unique.</p>
 */
public final class XrossClusterConfiguration {

    private final XrossClusterMode mode;
    private final String clusterId;
    private final String nodeId;
    private final boolean leaderEligible;
    private final int maxShardsPerNode;
    private final int heartbeatSeconds;
    private final int nodeTimeoutSeconds;
    private final int coordinationLossTimeoutSeconds;
    private final int leaderLeaseSeconds;
    private final int reconcileSeconds;
    private final int formationDelaySeconds;
    private final int joinTimeoutSeconds;
    private final int transitionTimeoutSeconds;
    private final int startupLeaseSeconds;

    private XrossClusterConfiguration(Builder builder) {
        this.mode = builder.mode;
        this.clusterId = normalizeId(builder.clusterId, "clusterId");
        this.nodeId = normalizeId(builder.nodeId, "nodeId");
        this.leaderEligible = builder.leaderEligible;
        this.maxShardsPerNode = builder.maxShardsPerNode;
        this.heartbeatSeconds = positive(builder.heartbeatSeconds, "heartbeatSeconds");
        this.nodeTimeoutSeconds = positive(builder.nodeTimeoutSeconds, "nodeTimeoutSeconds");
        this.coordinationLossTimeoutSeconds = positive(builder.coordinationLossTimeoutSeconds, "coordinationLossTimeoutSeconds");
        this.leaderLeaseSeconds = positive(builder.leaderLeaseSeconds, "leaderLeaseSeconds");
        this.reconcileSeconds = positive(builder.reconcileSeconds, "reconcileSeconds");
        this.formationDelaySeconds = nonNegative(builder.formationDelaySeconds, "formationDelaySeconds");
        this.joinTimeoutSeconds = positive(builder.joinTimeoutSeconds, "joinTimeoutSeconds");
        this.transitionTimeoutSeconds = positive(builder.transitionTimeoutSeconds, "transitionTimeoutSeconds");
        this.startupLeaseSeconds = positive(builder.startupLeaseSeconds, "startupLeaseSeconds");

        if (maxShardsPerNode < 0) {
            throw new IllegalArgumentException("maxShardsPerNode cannot be negative.");
        }
        if (nodeTimeoutSeconds <= coordinationLossTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "nodeTimeoutSeconds must be greater than coordinationLossTimeoutSeconds so a partitioned node stops before reassignment."
            );
        }
        if (leaderLeaseSeconds <= heartbeatSeconds) {
            throw new IllegalArgumentException("leaderLeaseSeconds must be greater than heartbeatSeconds.");
        }
        if (mode == XrossClusterMode.AUTO && !leaderEligible && joinTimeoutSeconds < nodeTimeoutSeconds) {
            throw new IllegalArgumentException("Non-leader AUTO nodes should use joinTimeoutSeconds >= nodeTimeoutSeconds.");
        }
    }

    public static Builder builder() { return new Builder(); }
    public static XrossClusterConfiguration off() { return builder().mode(XrossClusterMode.OFF).build(); }

    public XrossClusterMode mode() { return mode; }
    public boolean enabled() { return mode != XrossClusterMode.OFF; }
    public boolean autoManaged() { return mode == XrossClusterMode.AUTO; }
    public String clusterId() { return clusterId; }
    public String nodeId() { return nodeId; }
    public boolean leaderEligible() { return leaderEligible; }
    public int maxShardsPerNode() { return maxShardsPerNode; }
    public int heartbeatSeconds() { return heartbeatSeconds; }
    public int nodeTimeoutSeconds() { return nodeTimeoutSeconds; }
    public int coordinationLossTimeoutSeconds() { return coordinationLossTimeoutSeconds; }
    public int leaderLeaseSeconds() { return leaderLeaseSeconds; }
    public int reconcileSeconds() { return reconcileSeconds; }
    public int formationDelaySeconds() { return formationDelaySeconds; }
    public int joinTimeoutSeconds() { return joinTimeoutSeconds; }
    public int transitionTimeoutSeconds() { return transitionTimeoutSeconds; }
    public int startupLeaseSeconds() { return startupLeaseSeconds; }

    public static final class Builder {
        private XrossClusterMode mode = XrossClusterMode.parse(
                XrossLocalConfiguration.string("cluster.mode", "XROSS_CLUSTER_MODE", "off")
        );
        private String clusterId = XrossLocalConfiguration.string("cluster.clusterId", "XROSS_CLUSTER_ID", "default");
        private String nodeId = XrossLocalConfiguration.string("cluster.nodeId", "XROSS_NODE_ID", defaultNodeId());
        private boolean leaderEligible = XrossLocalConfiguration.bool("cluster.leaderEligible", "XROSS_CLUSTER_LEADER_ELIGIBLE", true);
        private int maxShardsPerNode = (int) XrossLocalConfiguration.longValue("cluster.maxShardsPerNode", "XROSS_CLUSTER_MAX_SHARDS_PER_NODE", 0L);
        private int heartbeatSeconds = (int) XrossLocalConfiguration.longValue("cluster.heartbeatSeconds", "XROSS_CLUSTER_HEARTBEAT_SECONDS", 10L);
        private int nodeTimeoutSeconds = (int) XrossLocalConfiguration.longValue("cluster.nodeTimeoutSeconds", "XROSS_CLUSTER_NODE_TIMEOUT_SECONDS", 45L);
        private int coordinationLossTimeoutSeconds = (int) XrossLocalConfiguration.longValue("cluster.coordinationLossTimeoutSeconds", "XROSS_CLUSTER_LOSS_TIMEOUT_SECONDS", 25L);
        private int leaderLeaseSeconds = (int) XrossLocalConfiguration.longValue("cluster.leaderLeaseSeconds", "XROSS_CLUSTER_LEADER_LEASE_SECONDS", 30L);
        private int reconcileSeconds = (int) XrossLocalConfiguration.longValue("cluster.reconcileSeconds", "XROSS_CLUSTER_RECONCILE_SECONDS", 10L);
        private int formationDelaySeconds = (int) XrossLocalConfiguration.longValue("cluster.formationDelaySeconds", "XROSS_CLUSTER_FORMATION_DELAY_SECONDS", 10L);
        private int joinTimeoutSeconds = (int) XrossLocalConfiguration.longValue("cluster.joinTimeoutSeconds", "XROSS_CLUSTER_JOIN_TIMEOUT_SECONDS", 180L);
        private int transitionTimeoutSeconds = (int) XrossLocalConfiguration.longValue("cluster.transitionTimeoutSeconds", "XROSS_CLUSTER_TRANSITION_TIMEOUT_SECONDS", 180L);
        private int startupLeaseSeconds = (int) XrossLocalConfiguration.longValue("cluster.startupLeaseSeconds", "XROSS_CLUSTER_STARTUP_LEASE_SECONDS", 180L);

        private Builder() {}

        public Builder mode(XrossClusterMode mode) { this.mode = mode; return this; }
        public Builder clusterId(String clusterId) { this.clusterId = clusterId; return this; }
        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder leaderEligible(boolean value) { this.leaderEligible = value; return this; }
        public Builder maxShardsPerNode(int value) { this.maxShardsPerNode = value; return this; }
        public Builder heartbeatSeconds(int value) { this.heartbeatSeconds = value; return this; }
        public Builder nodeTimeoutSeconds(int value) { this.nodeTimeoutSeconds = value; return this; }
        public Builder coordinationLossTimeoutSeconds(int value) { this.coordinationLossTimeoutSeconds = value; return this; }
        public Builder leaderLeaseSeconds(int value) { this.leaderLeaseSeconds = value; return this; }
        public Builder reconcileSeconds(int value) { this.reconcileSeconds = value; return this; }
        public Builder formationDelaySeconds(int value) { this.formationDelaySeconds = value; return this; }
        public Builder joinTimeoutSeconds(int value) { this.joinTimeoutSeconds = value; return this; }
        public Builder transitionTimeoutSeconds(int value) { this.transitionTimeoutSeconds = value; return this; }
        public Builder startupLeaseSeconds(int value) { this.startupLeaseSeconds = value; return this; }
        public XrossClusterConfiguration build() { return new XrossClusterConfiguration(this); }
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be at least 1.");
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative.");
        return value;
    }

    private static String normalizeId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank.");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters: " + value);
        }
        return normalized;
    }

    private static String defaultNodeId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                return hostname.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
            }
        } catch (Exception ignored) {
        }
        return "node-" + ProcessHandle.current().pid();
    }
}
