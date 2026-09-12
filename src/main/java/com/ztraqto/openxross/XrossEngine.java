package com.ztraqto.openxross;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import com.ztraqto.openxross.api.cluster.XrossClusterAssignment;
import com.ztraqto.openxross.api.cluster.XrossClusterStatus;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.runtime.XrossRole;
import com.ztraqto.openxross.api.system.SystemProviderRegistry;
import com.ztraqto.openxross.api.system.XrossDbClientProvider;
import com.ztraqto.openxross.config.XrossArguments;
import com.ztraqto.openxross.config.XrossConfiguration;
import com.ztraqto.openxross.config.XrossShardConfiguration;
import com.ztraqto.openxross.config.XrossShardMode;
import com.ztraqto.openxross.core.PluginManager;
import com.ztraqto.openxross.core.ServiceManager;
import com.ztraqto.openxross.core.SystemPluginManager;
import com.ztraqto.openxross.core.bus.PluginBus;
import com.ztraqto.openxross.db.RemoteXrossDbClient;
import com.ztraqto.openxross.runtime.XrossBotRuntime;
import com.ztraqto.openxross.runtime.XrossDatabaseRuntime;
import com.ztraqto.openxross.runtime.cluster.XrossClusterManager;
import com.ztraqto.openxross.runtime.cluster.XrossOrchestrator;
import com.ztraqto.openxross.runtime.shard.DiscordGatewayBotInfo;
import com.ztraqto.openxross.runtime.shard.DiscordGatewayBotInfoClient;
import com.ztraqto.openxross.runtime.shard.ShardAutoScaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class XrossEngine {

    private static final Logger logger = LoggerFactory.getLogger(XrossEngine.class);
    private static XrossEngine instance;

    private final long startTime;
    private final Set<XrossRole> activeRoles = EnumSet.noneOf(XrossRole.class);

    private XrossConfiguration configuration;
    private XrossDatabaseRuntime databaseRuntime;
    private XrossBotRuntime botRuntime;
    private SystemPluginManager systemPluginManager;
    private ShardAutoScaler shardAutoScaler;
    private XrossOrchestrator orchestrator;
    private boolean running;

    public XrossEngine() {
        startTime = System.currentTimeMillis();
        instance = this;
    }

    public synchronized void start(String token) throws Exception {
        start(XrossConfiguration.combined(token));
    }

    public synchronized void start(String[] args) throws Exception {
        start(XrossArguments.parse(args));
    }

    public synchronized void start(XrossConfiguration configuration) throws Exception {
        if (running || databaseRuntime != null || botRuntime != null || systemPluginManager != null || orchestrator != null) {
            throw new IllegalStateException("Xross Engine is already running or starting.");
        }

        this.configuration = resolveInitialShardTopology(configuration);
        logger.info("Starting OpenXrossEngine {} with roles {}...", XrossVersion.current(), this.configuration.roles());

        try {
            systemPluginManager = new SystemPluginManager(this, this.configuration.systemPlugins());
            systemPluginManager.loadAll();

            if (this.configuration.hasRole(XrossRole.DATABASE)) {
                databaseRuntime = new XrossDatabaseRuntime(this.configuration.database());
                databaseRuntime.start();
                activeRoles.add(XrossRole.DATABASE);
            }

            if (this.configuration.hasRole(XrossRole.BOT) && this.configuration.cluster().autoManaged()) {
                XrossDbClient clusterClient = resolveDatabaseClient();
                orchestrator = new XrossOrchestrator(
                        this,
                        this.configuration.cluster(),
                        clusterClient,
                        this.configuration.discordToken()
                );
                XrossClusterAssignment assignment = orchestrator.joinAndResolveInitialAssignment();
                this.configuration = this.configuration.withShards(
                        this.configuration.shards().withClusterAssignment(
                                assignment.totalShards(), assignment.shardIdArray()
                        )
                );
                if (!assignment.shardIds().isEmpty()) {
                    orchestrator.runWithStartupLease(this::startBotRuntime);
                    activeRoles.add(XrossRole.BOT);
                } else {
                    logger.info("Xross Orchestrator node {} started as a shard standby (no current shard assignment).",
                            this.configuration.cluster().nodeId());
                }
                orchestrator.markInitialRuntimeRunning();
            } else if (this.configuration.hasRole(XrossRole.BOT)) {
                startBotRuntime();
                activeRoles.add(XrossRole.BOT);
            }

            running = true;
            systemPluginManager.notifyEngineReady();

            if (orchestrator != null) {
                orchestrator.startBackground();
            } else if (this.configuration.hasRole(XrossRole.BOT) && this.configuration.shards().autoScaleEnabled()) {
                shardAutoScaler = new ShardAutoScaler(this, this.configuration.discordToken());
                shardAutoScaler.start();
            }
            logger.info("OpenXrossEngine {} started with active roles {}.", XrossVersion.current(), activeRoles);
        } catch (Exception exception) {
            stopInternal();
            throw exception;
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    public synchronized boolean isRunning() { return running; }
    public synchronized boolean isBotRuntimeRunning() { return botRuntime != null; }

    public synchronized boolean hasRole(XrossRole role) { return activeRoles.contains(role); }

    public synchronized Set<XrossRole> getActiveRoles() {
        if (activeRoles.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(EnumSet.copyOf(activeRoles));
    }

    public static XrossEngine getInstance() { return instance; }
    public JDA getJda() { return botRuntime != null ? botRuntime.getJda() : null; }
    public ShardManager getShardManager() { return botRuntime != null ? botRuntime.getShardManager() : null; }
    public ServiceManager getServiceManager() { return botRuntime != null ? botRuntime.getServiceManager() : null; }
    public PluginManager getPluginManager() { return botRuntime != null ? botRuntime.getPluginManager() : null; }
    public PluginBus getPluginBus() { return botRuntime != null ? botRuntime.getPluginBus() : null; }
    public SystemPluginManager getSystemPluginManager() { return systemPluginManager; }
    public SystemProviderRegistry getSystemProviderRegistry() { return systemPluginManager != null ? systemPluginManager.providers() : null; }
    public XrossConfiguration getConfiguration() { return configuration; }
    public XrossOrchestrator getOrchestrator() { return orchestrator; }
    /** @deprecated Since 1.2.1. Use {@link #getOrchestrator()}. */
    @Deprecated(since = "1.2.1", forRemoval = false)
    public XrossClusterManager getClusterManager() { return orchestrator == null ? null : new XrossClusterManager(orchestrator); }
    public XrossClusterStatus getClusterStatus() {
        if (orchestrator != null) return orchestrator.status();
        if (configuration != null && configuration.cluster().enabled()) {
            return new XrossClusterStatus(
                    true, configuration.cluster().clusterId(), configuration.cluster().nodeId(), false, null,
                    0, configuration.shards().totalShards(), configuration.shards().shardIds().stream().sorted().toList(),
                    null, true
            );
        }
        return XrossClusterStatus.disabled();
    }
    public String getVersion() { return XrossVersion.current(); }

    public String getProductName() {
        if (configuration != null && configuration.product().displayName() != null) return configuration.product().displayName();
        JDA jda = getJda();
        if (jda != null && jda.getSelfUser() != null) return jda.getSelfUser().getName();
        return "OpenXross Bot";
    }

    public long getStartTime() { return startTime; }

    /** Called by the single-process AUTO_SCALE controller. Only increases are accepted. */
    public synchronized boolean scaleShardsUp(int requestedTotal, DiscordGatewayBotInfo gatewayInfo) throws Exception {
        if (configuration.cluster().autoManaged()) {
            throw new IllegalStateException("Local shard scaling is disabled in AUTO cluster mode; Xross Orchestrator leader owns topology changes.");
        }
        if (!running || botRuntime == null || !configuration.shards().autoScaleEnabled()) return false;
        int current = configuration.shards().totalShards();
        int target = configuration.shards().clampAutoScaleTarget(requestedTotal);
        if (target <= current) return false;
        if (configuration.shards().usesExplicitShardIds()) {
            throw new IllegalStateException("AUTO_SCALE cannot reshard a process with explicit shard IDs.");
        }
        if (gatewayInfo != null && !gatewayInfo.canStartShardSet(target)) return false;

        XrossConfiguration previous = configuration;
        XrossShardConfiguration nextShards = previous.shards().withResolvedTotalShards(target);
        logger.warn("Resharding OpenXross from {} to {} total shards.", current, target);

        if (botRuntime != null) botRuntime.notifyTopologyChanging(current, target);
        botRuntime.stop();
        botRuntime = null;
        activeRoles.remove(XrossRole.BOT);
        configuration = previous.withShards(nextShards);

        try {
            startBotRuntime();
            activeRoles.add(XrossRole.BOT);
            botRuntime.notifyTopologyChanged(current, target);
            logger.info("Automatic reshard completed: {} -> {}.", current, target);
            return true;
        } catch (Exception scaleFailure) {
            logger.error("Automatic reshard to {} failed; attempting rollback to {}.", target, current, scaleFailure);
            configuration = previous;
            try {
                startBotRuntime();
                activeRoles.add(XrossRole.BOT);
                if (botRuntime != null) botRuntime.notifyTopologyChanged(target, current);
            } catch (Exception rollbackFailure) {
                scaleFailure.addSuppressed(rollbackFailure);
                logger.error("Shard rollback failed; Bot runtime remains offline.", rollbackFailure);
            }
            throw scaleFailure;
        }
    }

    /** Cluster barrier step: stop all locally-owned old-generation shards. */
    public synchronized void prepareClusterTopology(int newTotalShards) {
        int previousTotal = configuration.shards().totalShards();
        if (botRuntime != null) {
            botRuntime.notifyTopologyChanging(previousTotal, newTotalShards);
            botRuntime.stop();
            botRuntime = null;
        }
        activeRoles.remove(XrossRole.BOT);
    }

    /** Cluster activation step invoked after every live node passed PREPARING. */
    public synchronized void activateClusterAssignment(XrossClusterAssignment assignment) throws Exception {
        int previousTotal = configuration.shards().totalShards();
        if (botRuntime != null) {
            botRuntime.stop();
            botRuntime = null;
            activeRoles.remove(XrossRole.BOT);
        }
        configuration = configuration.withShards(
                configuration.shards().withClusterAssignment(assignment.totalShards(), assignment.shardIdArray())
        );
        if (assignment.shardIds().isEmpty()) {
            logger.info("Cluster node {} is active as standby for topology generation {}.",
                    configuration.cluster().nodeId(), assignment.generation());
            return;
        }
        startBotRuntime();
        activeRoles.add(XrossRole.BOT);
        if (botRuntime != null) botRuntime.notifyTopologyChanged(previousTotal, assignment.totalShards());
    }

    /** Fail-closed split-brain protection used when the shared Xross Orchestrator state is unavailable. */
    public synchronized void suspendBotRuntimeForClusterLoss() {
        if (botRuntime == null) return;
        logger.error("Suspending local Discord shards because Xross Orchestrator coordination is unavailable or this node is unassigned.");
        botRuntime.stop();
        botRuntime = null;
        activeRoles.remove(XrossRole.BOT);
    }

    private void startBotRuntime() throws Exception {
        XrossDbClient databaseClient = resolveDatabaseClient();
        XrossBotRuntime runtime = new XrossBotRuntime(this, configuration.discordToken(), databaseClient);
        try {
            runtime.start();
            botRuntime = runtime;
        } catch (Exception exception) {
            runtime.stop();
            throw exception;
        }
    }

    private XrossConfiguration resolveInitialShardTopology(XrossConfiguration original) {
        if (!original.hasRole(XrossRole.BOT) || original.cluster().autoManaged()) return original;
        XrossShardConfiguration shards = original.shards();
        if (shards.mode() == XrossShardMode.FIXED) return original;

        try {
            DiscordGatewayBotInfo gateway = new DiscordGatewayBotInfoClient().fetch(original.discordToken());
            int recommended = gateway.recommendedShards();
            if (shards.autoScaleMaxShards() > 0 && recommended > shards.autoScaleMaxShards()) {
                logger.error(
                        "Discord recommends {} shards but autoScaleMaxShards={} caps OpenXross below that value. "
                                + "This ceiling is a deployment safety guard, not a substitute for Discord's required topology.",
                        recommended,
                        shards.autoScaleMaxShards()
                );
            }
            int resolved = shards.mode() == XrossShardMode.RECOMMENDED
                    ? shards.clampAutoScaleTarget(recommended)
                    : Math.max(shards.totalShards(), shards.clampAutoScaleTarget(recommended));
            if (resolved != shards.totalShards()) {
                logger.info("Resolved Discord shard total {} -> {} using mode {}.", shards.totalShards(), resolved, shards.mode());
                return original.withShards(shards.withResolvedTotalShards(resolved));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Shard recommendation lookup was interrupted; using configured shard total {}.", shards.totalShards());
        } catch (Exception exception) {
            logger.warn("Could not resolve Discord shard recommendation; using configured shard total {}.", shards.totalShards(), exception);
        }
        return original;
    }

    private XrossDbClient resolveDatabaseClient() throws Exception {
        if (!configuration.systemPlugins().usesBuiltinXrossDb()) {
            String providerId = configuration.systemPlugins().databaseProvider();
            XrossDbClientProvider provider = systemPluginManager.providers().require(XrossDbClientProvider.class, providerId);
            XrossDbClient client = provider.createClient(this, configuration);
            if (client == null) throw new IllegalStateException("System database provider returned null: " + providerId);
            try {
                client.verifyConnection();
            } catch (Exception exception) {
                try { client.close(); } catch (Exception ignored) {}
                throw new IllegalStateException("System database provider failed connection verification: " + providerId, exception);
            }
            logger.info("Using verified system database provider '{}'.", providerId);
            return client;
        }
        if (databaseRuntime != null) return databaseRuntime.createInProcessClient();
        RemoteXrossDbClient remoteClient = new RemoteXrossDbClient(configuration.database());
        remoteClient.verifyConnection();
        return remoteClient;
    }

    private void stopInternal() {
        boolean hadRuntime = botRuntime != null || databaseRuntime != null || orchestrator != null;
        if (hadRuntime) logger.info("Stopping OpenXrossEngine {}...", XrossVersion.current());

        if (shardAutoScaler != null) {
            shardAutoScaler.close();
            shardAutoScaler = null;
        }
        if (systemPluginManager != null) systemPluginManager.notifyEngineStopping();
        if (botRuntime != null) {
            botRuntime.stop();
            botRuntime = null;
        }
        if (orchestrator != null) {
            orchestrator.close();
            orchestrator = null;
        }
        if (databaseRuntime != null) {
            databaseRuntime.stop();
            databaseRuntime = null;
        }
        if (systemPluginManager != null) {
            systemPluginManager.unloadAll();
            systemPluginManager = null;
        }
        activeRoles.clear();
        running = false;
        if (hadRuntime) logger.info("OpenXrossEngine {} stopped.", XrossVersion.current());
    }
}
