package com.ztraqto.openxross.runtime.cluster;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.XrossVersion;
import com.ztraqto.openxross.api.cluster.XrossClusterAssignment;
import com.ztraqto.openxross.api.cluster.XrossClusterNodeState;
import com.ztraqto.openxross.api.cluster.XrossClusterPhase;
import com.ztraqto.openxross.api.cluster.XrossClusterStatus;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbConflictException;
import com.ztraqto.openxross.api.database.XrossDbRecord;
import com.ztraqto.openxross.config.XrossClusterConfiguration;
import com.ztraqto.openxross.config.XrossShardMode;
import com.ztraqto.openxross.runtime.shard.DiscordGatewayBotInfo;
import com.ztraqto.openxross.runtime.shard.DiscordGatewayBotInfoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenXross multi-machine shard coordinator: Xross Orchestrator.
 *
 * <p>Xross Orchestrator uses the configured shared {@link XrossDbClient} for
 * node heartbeats, leader leases and topology generations. A node fails closed
 * and disconnects its Discord shards when the shared coordination store is unavailable
 * long enough to make split-brain reassignment possible.</p>
 */
public class XrossOrchestrator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(XrossOrchestrator.class);

    private final XrossEngine engine;
    private final XrossClusterConfiguration configuration;
    private final XrossDbClusterStore store;
    private final String discordToken;
    private final DiscordGatewayBotInfoClient gatewayClient = new DiscordGatewayBotInfoClient();
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ScheduledExecutorService executor;
    private volatile boolean leader;
    private volatile String leaderNodeId;
    private volatile long leaderEpoch;
    private volatile long lastCoordinationSuccess = System.currentTimeMillis();
    private volatile boolean coordinationHealthy = true;
    private volatile XrossClusterTopology observedTopology;
    private volatile XrossClusterAssignment localAssignment;
    private volatile long lastGatewayCheck;
    private volatile Integer cachedRecommendedShards;
    private volatile long localPreparedGeneration;
    private volatile long localRunningGeneration;

    public XrossOrchestrator(
            XrossEngine engine,
            XrossClusterConfiguration configuration,
            XrossDbClient databaseClient,
            String discordToken
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = new XrossDbClusterStore(Objects.requireNonNull(databaseClient, "databaseClient"), configuration.clusterId());
        this.discordToken = Objects.requireNonNull(discordToken, "discordToken");
    }

    /**
     * Registers this node, elects/observes the leader and returns its initial
     * shard assignment. This is invoked before the local JDA runtime starts.
     */
    public XrossClusterAssignment joinAndResolveInitialAssignment() throws Exception {
        store.client().verifyConnection();
        touchSuccess();
        heartbeat(XrossClusterNodeState.JOINING, localPreparedGeneration, localRunningGeneration, List.of());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.joinTimeoutSeconds());
        boolean formationWaited = false;
        while (!closed.get() && System.nanoTime() < deadline) {
            try {
                heartbeat(XrossClusterNodeState.JOINING, localPreparedGeneration, localRunningGeneration, localAssignment == null ? List.of() : localAssignment.shardIds());
                electOrRenewLeader();
                Optional<XrossClusterTopology> topology = store.readTopology();
                touchSuccess();

                if (leader) {
                    if (topology.isEmpty() && !formationWaited && configuration.formationDelaySeconds() > 0) {
                        formationWaited = true;
                        Thread.sleep(TimeUnit.SECONDS.toMillis(configuration.formationDelaySeconds()));
                        continue;
                    }
                    reconcileAsLeader(topology.orElse(null), true);
                    topology = store.readTopology();
                    touchSuccess();
                }

                if (topology.isPresent()) {
                    XrossClusterTopology current = topology.get();
                    observedTopology = current;
                    if (current.phase() == XrossClusterPhase.PREPARING) {
                        markPrepared(current.generation());
                    } else if (current.assignments().containsKey(configuration.nodeId())) {
                        XrossClusterAssignment assignment = assignmentFor(current);
                        localAssignment = assignment;
                        return assignment;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (Exception exception) {
                logger.warn("Xross Orchestrator join attempt failed; retrying.", exception);
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException(
                "Xross Orchestrator node " + configuration.nodeId() + " did not receive a shard assignment within "
                        + configuration.joinTimeoutSeconds() + " seconds."
        );
    }

    /** Serializes JDA startup across machines to keep IDENTIFY concurrency safe. */
    public void runWithStartupLease(ThrowingRunnable action) throws Exception {
        Objects.requireNonNull(action, "action");
        acquireStartupLease();
        try {
            action.run();
        } finally {
            try {
                store.releaseStartupLease(configuration.nodeId());
                touchSuccess();
            } catch (Exception exception) {
                logger.warn("Could not release Orchestrator startup lease immediately.", exception);
            }
        }
    }

    public void markInitialRuntimeRunning() {
        XrossClusterAssignment assignment = localAssignment;
        if (assignment == null) return;
        try {
            localPreparedGeneration = assignment.generation();
            localRunningGeneration = assignment.generation();
            heartbeat(
                    assignment.shardIds().isEmpty() ? XrossClusterNodeState.STANDBY : XrossClusterNodeState.RUNNING,
                    localPreparedGeneration, localRunningGeneration, assignment.shardIds()
            );
        } catch (Exception exception) {
            logger.warn("Could not publish initial cluster running state.", exception);
        }
    }

    public synchronized void startBackground() {
        if (executor != null || closed.get()) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Xross-Orchestrator-" + configuration.nodeId());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::backgroundTick, 0, configuration.reconcileSeconds(), TimeUnit.SECONDS);
    }

    public XrossClusterStatus status() {
        XrossClusterTopology topology = observedTopology;
        XrossClusterAssignment assignment = localAssignment;
        return new XrossClusterStatus(
                true,
                configuration.clusterId(),
                configuration.nodeId(),
                leader,
                leaderNodeId,
                topology == null ? 0 : topology.generation(),
                topology == null ? 0 : topology.totalShards(),
                assignment == null ? List.of() : assignment.shardIds(),
                topology == null ? null : topology.phase(),
                coordinationHealthy
        );
    }

    public boolean isLeader() { return leader; }
    public String nodeId() { return configuration.nodeId(); }
    public XrossClusterAssignment localAssignment() { return localAssignment; }

    private void backgroundTick() {
        if (closed.get()) return;
        try {
            XrossClusterAssignment assignment = localAssignment;
            List<Integer> localShards = assignment == null ? List.of() : assignment.shardIds();
            XrossClusterNodeState heartbeatState = localPreparedGeneration > localRunningGeneration
                    ? XrossClusterNodeState.PREPARED
                    : (engine.isBotRuntimeRunning() ? XrossClusterNodeState.RUNNING : XrossClusterNodeState.STANDBY);
            heartbeat(heartbeatState, localPreparedGeneration, localRunningGeneration, localShards);
            electOrRenewLeader();
            Optional<XrossClusterTopology> topology = store.readTopology();
            touchSuccess();
            if (leader) {
                reconcileAsLeader(topology.orElse(null), false);
                topology = store.readTopology();
                touchSuccess();
            }
            topology.ifPresent(this::synchronizeLocalTopology);
        } catch (Exception exception) {
            logger.warn("Xross Orchestrator coordination tick failed.", exception);
            handleCoordinationFailure();
        }
    }

    private void synchronizeLocalTopology(XrossClusterTopology topology) {
        observedTopology = topology;
        try {
            if (topology.phase() == XrossClusterPhase.PREPARING) {
                XrossClusterAssignment current = localAssignment;
                if (current == null || current.generation() < topology.generation()) {
                    engine.prepareClusterTopology(topology.totalShards());
                    markPrepared(topology.generation());
                }
                return;
            }

            if (!topology.assignments().containsKey(configuration.nodeId())) {
                // The node has been deliberately removed from the active plan.
                engine.suspendBotRuntimeForClusterLoss();
                localAssignment = null;
                localPreparedGeneration = topology.generation();
                localRunningGeneration = topology.generation();
                heartbeat(XrossClusterNodeState.STANDBY, localPreparedGeneration, localRunningGeneration, List.of());
                return;
            }

            XrossClusterAssignment desired = assignmentFor(topology);
            XrossClusterAssignment current = localAssignment;
            boolean differs = current == null
                    || current.generation() != desired.generation()
                    || current.totalShards() != desired.totalShards()
                    || !current.shardIds().equals(desired.shardIds());
            if (!differs && engine.isBotRuntimeRunning() == !desired.shardIds().isEmpty()) {
                return;
            }

            runWithStartupLease(() -> engine.activateClusterAssignment(desired));
            localAssignment = desired;
            localPreparedGeneration = desired.generation();
            localRunningGeneration = desired.generation();
            heartbeat(
                    desired.shardIds().isEmpty() ? XrossClusterNodeState.STANDBY : XrossClusterNodeState.RUNNING,
                    localPreparedGeneration, localRunningGeneration, desired.shardIds()
            );
            logger.info("Orchestrator topology generation {} active on node {} with shards {}.",
                    desired.generation(), configuration.nodeId(), desired.shardIds());
        } catch (Exception exception) {
            logger.error("Failed to apply orchestrator topology generation {} on node {}.", topology.generation(), configuration.nodeId(), exception);
        }
    }

    private void reconcileAsLeader(XrossClusterTopology current, boolean initialJoin) throws Exception {
        if (!leader) return;
        long now = System.currentTimeMillis();
        List<XrossClusterNodeRecord> activeNodes = activeNodes(now);
        if (activeNodes.isEmpty()) return;

        if (current != null && current.phase() == XrossClusterPhase.PREPARING) {
            if (allPrepared(activeNodes, current.generation())) {
                XrossClusterTopology active = new XrossClusterTopology(
                        current.generation(), current.totalShards(), XrossClusterPhase.ACTIVE,
                        configuration.nodeId(), current.createdAt(), current.reason(), current.assignments()
                );
                updateTopology(current, active);
                logger.info("Orchestrator topology generation {} passed preparation barrier and is ACTIVE.", current.generation());
            } else if (now - current.createdAt() > TimeUnit.SECONDS.toMillis(configuration.transitionTimeoutSeconds())) {
                logger.warn("Orchestrator topology generation {} is still waiting for nodes to prepare: {}.",
                        current.generation(), unpreparedNodes(activeNodes, current.generation()));
            }
            return;
        }

        int targetTotal = resolveTargetShardTotal(current);
        Map<String, List<Integer>> previous = current == null ? Map.of() : current.assignments();
        Map<String, List<Integer>> desired = XrossClusterPlanner.plan(targetTotal, activeNodes, previous);

        if (current != null && current.phase() == XrossClusterPhase.ACTIVE
                && !allRunningForTopology(activeNodes, current)) {
            return;
        }

        if (current == null) {
            ensureSessionBudget(targetTotal);
            XrossClusterTopology initial = new XrossClusterTopology(
                    1L, targetTotal, XrossClusterPhase.ACTIVE, configuration.nodeId(), now,
                    "initial-cluster-formation", desired
            );
            store.writeTopology(initial, 0L);
            observedTopology = initial;
            logger.info("Created initial OpenXross orchestrator topology with {} shards across {} node(s).", targetTotal, activeNodes.size());
            return;
        }

        if (current.totalShards() == targetTotal && current.assignments().equals(desired)) {
            return;
        }

        ensureSessionBudget(targetTotal);
        String reason = current.totalShards() != targetTotal
                ? "discord-recommended-shards-changed"
                : "cluster-membership-changed";
        XrossClusterTopology preparing = new XrossClusterTopology(
                current.generation() + 1L,
                targetTotal,
                XrossClusterPhase.PREPARING,
                configuration.nodeId(),
                now,
                reason,
                desired
        );
        updateTopology(current, preparing);
        logger.warn("Published orchestrator topology generation {} PREPARING ({} shards, {} nodes, reason={}).",
                preparing.generation(), targetTotal, activeNodes.size(), reason);
    }

    private int resolveTargetShardTotal(XrossClusterTopology current) throws Exception {
        var shards = engine.getConfiguration().shards();
        if (shards.mode() == XrossShardMode.FIXED) {
            return shards.totalShards();
        }
        long now = System.currentTimeMillis();
        if (cachedRecommendedShards == null || now - lastGatewayCheck >= TimeUnit.SECONDS.toMillis(shards.autoScaleCheckSeconds())) {
            DiscordGatewayBotInfo info = gatewayClient.fetch(discordToken);
            int recommended = shards.clampAutoScaleTarget(info.recommendedShards());
            cachedRecommendedShards = recommended;
            lastGatewayCheck = now;
        }
        int recommended = cachedRecommendedShards;
        if (shards.mode() == XrossShardMode.AUTO_SCALE && current != null) {
            return Math.max(current.totalShards(), recommended);
        }
        return recommended;
    }

    private List<XrossClusterNodeRecord> activeNodes(long now) {
        long cutoff = now - TimeUnit.SECONDS.toMillis(configuration.nodeTimeoutSeconds());
        List<XrossClusterNodeRecord> all = store.listNodes();
        touchSuccess();
        List<XrossClusterNodeRecord> active = all.stream()
                .filter(node -> node.heartbeatAt() >= cutoff)
                .sorted(Comparator.comparing(XrossClusterNodeRecord::nodeId))
                .toList();
        for (XrossClusterNodeRecord node : all) {
            if (node.heartbeatAt() < cutoff) {
                // Best-effort cleanup. A stale record is ignored even if delete fails.
                try {
                    if (now - node.heartbeatAt() > TimeUnit.SECONDS.toMillis(configuration.nodeTimeoutSeconds() * 4L)) {
                        store.deleteNode(node.nodeId());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return active;
    }

    private boolean allRunningForTopology(List<XrossClusterNodeRecord> activeNodes, XrossClusterTopology topology) {
        for (XrossClusterNodeRecord node : activeNodes) {
            if (!topology.assignments().containsKey(node.nodeId())) continue;
            if (node.runningGeneration() < topology.generation()) return false;
        }
        return true;
    }

    private void ensureSessionBudget(int totalShards) throws Exception {
        DiscordGatewayBotInfo info = gatewayClient.fetch(discordToken);
        touchSuccess();
        if (!info.canStartShardSet(totalShards)) {
            throw new IllegalStateException(
                    "Discord session start limit has only " + info.sessionStartsRemaining()
                            + " IDENTIFY calls remaining, but orchestrator topology activation requires up to "
                            + totalShards + ". Retry after reset_after=" + info.sessionResetAfterMillis() + "ms."
            );
        }
    }

    private boolean allPrepared(List<XrossClusterNodeRecord> activeNodes, long generation) {
        return activeNodes.stream().allMatch(node -> node.preparedGeneration() >= generation);
    }

    private List<String> unpreparedNodes(List<XrossClusterNodeRecord> activeNodes, long generation) {
        return activeNodes.stream().filter(node -> node.preparedGeneration() < generation).map(XrossClusterNodeRecord::nodeId).toList();
    }

    private void markPrepared(long generation) throws Exception {
        XrossClusterAssignment current = localAssignment;
        localPreparedGeneration = Math.max(localPreparedGeneration, generation);
        heartbeat(XrossClusterNodeState.PREPARED, localPreparedGeneration,
                localRunningGeneration, current == null ? List.of() : current.shardIds());
    }

    private XrossClusterAssignment assignmentFor(XrossClusterTopology topology) {
        return new XrossClusterAssignment(topology.generation(), topology.totalShards(), topology.shardsFor(configuration.nodeId()));
    }

    private void updateTopology(XrossClusterTopology expected, XrossClusterTopology replacement) {
        Optional<XrossDbRecord> raw = store.readRaw("topology", "active");
        touchSuccess();
        if (raw.isEmpty()) {
            store.writeTopology(replacement, 0L);
            touchSuccess();
            return;
        }
        XrossClusterTopology latest = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(raw.get().payload(), XrossClusterTopology.class);
        if (expected != null && latest.generation() != expected.generation()) return;
        try {
            store.writeTopology(replacement, raw.get().revision());
            touchSuccess();
        } catch (XrossDbConflictException ignored) {
            logger.debug("Orchestrator topology changed concurrently; another leader tick won the CAS.");
        }
    }

    private void heartbeat(XrossClusterNodeState state, long preparedGeneration, long runningGeneration, List<Integer> shards) {
        XrossClusterNodeRecord node = new XrossClusterNodeRecord(
                configuration.nodeId(), XrossVersion.current(), startedAt, System.currentTimeMillis(),
                configuration.leaderEligible(), configuration.maxShardsPerNode(),
                preparedGeneration, runningGeneration, shards, state
        );
        store.writeNode(node);
        touchSuccess();
    }

    private void electOrRenewLeader() {
        long now = System.currentTimeMillis();
        if (!configuration.leaderEligible()) {
            leader = false;
            store.readLeader().ifPresent(value -> leaderNodeId = value.nodeId());
            touchSuccess();
            return;
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<XrossDbRecord> raw = store.readLeaderRaw();
            touchSuccess();
            long revision = raw.map(XrossDbRecord::revision).orElse(0L);
            XrossClusterLease current = raw
                    .map(record -> new com.fasterxml.jackson.databind.ObjectMapper().convertValue(record.payload(), XrossClusterLease.class))
                    .orElse(null);
            boolean canTake = current == null || current.leaseUntil() <= now || configuration.nodeId().equals(current.nodeId());
            if (!canTake) {
                leader = false;
                leaderNodeId = current.nodeId();
                leaderEpoch = current.epoch();
                return;
            }
            long epoch = current == null ? 1L : (configuration.nodeId().equals(current.nodeId()) ? current.epoch() : current.epoch() + 1L);
            XrossClusterLease next = new XrossClusterLease(
                    configuration.nodeId(), now + TimeUnit.SECONDS.toMillis(configuration.leaderLeaseSeconds()), epoch
            );
            try {
                store.writeLeader(next, revision);
                touchSuccess();
                leader = true;
                leaderNodeId = configuration.nodeId();
                leaderEpoch = epoch;
                return;
            } catch (XrossDbConflictException ignored) {
            }
        }
        leader = false;
    }

    private void acquireStartupLease() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.startupLeaseSeconds());
        while (!closed.get() && System.nanoTime() < deadline) {
            long now = System.currentTimeMillis();
            Optional<XrossDbRecord> raw = store.readStartupLeaseRaw();
            touchSuccess();
            long revision = raw.map(XrossDbRecord::revision).orElse(0L);
            XrossClusterLease current = raw
                    .map(record -> new com.fasterxml.jackson.databind.ObjectMapper().convertValue(record.payload(), XrossClusterLease.class))
                    .orElse(null);
            boolean available = current == null || current.leaseUntil() <= now || configuration.nodeId().equals(current.nodeId());
            if (available) {
                XrossClusterLease next = new XrossClusterLease(
                        configuration.nodeId(), now + TimeUnit.SECONDS.toMillis(configuration.startupLeaseSeconds()),
                        current == null ? 1 : current.epoch() + 1
                );
                try {
                    store.writeStartupLease(next, revision);
                    touchSuccess();
                    return;
                } catch (XrossDbConflictException ignored) {
                }
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("Timed out waiting for the OpenXross cluster JDA startup lease.");
    }

    private void handleCoordinationFailure() {
        long lostFor = System.currentTimeMillis() - lastCoordinationSuccess;
        if (lostFor < TimeUnit.SECONDS.toMillis(configuration.coordinationLossTimeoutSeconds())) return;
        if (!coordinationHealthy) return;
        coordinationHealthy = false;
        leader = false;
        logger.error(
                "Xross Orchestrator coordination has been unavailable for {} ms. Stopping local Discord shards fail-closed to prevent split brain.",
                lostFor
        );
        engine.suspendBotRuntimeForClusterLoss();
        try {
            heartbeat(XrossClusterNodeState.COORDINATION_LOST, 0, 0, List.of());
        } catch (Exception ignored) {
        }
    }

    private void touchSuccess() {
        lastCoordinationSuccess = System.currentTimeMillis();
        if (!coordinationHealthy) {
            coordinationHealthy = true;
            logger.info("Xross Orchestrator coordination recovered on node {}.", configuration.nodeId());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
        try {
            heartbeat(XrossClusterNodeState.STOPPING, 0, 0, List.of());
        } catch (Exception ignored) {
        }
        try {
            store.deleteNode(configuration.nodeId());
        } catch (Exception ignored) {
        }
        try {
            if (leader) {
                Optional<XrossDbRecord> raw = store.readLeaderRaw();
                if (raw.isPresent()) {
                    XrossClusterLease lease = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(raw.get().payload(), XrossClusterLease.class);
                    if (configuration.nodeId().equals(lease.nodeId())) {
                        store.client().delete(new com.ztraqto.openxross.api.database.XrossDbKey(
                                "xross-cluster", configuration.clusterId() + "--" + XrossDbClusterStore.orchestratorCollectionName(), "leader"), raw.get().revision());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try { store.client().close(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
