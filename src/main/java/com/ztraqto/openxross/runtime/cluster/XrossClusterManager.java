package com.ztraqto.openxross.runtime.cluster;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.cluster.XrossClusterAssignment;
import com.ztraqto.openxross.api.cluster.XrossClusterStatus;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.config.XrossClusterConfiguration;

/**
 * @deprecated Since 1.2.1. Use {@link XrossOrchestrator}.
 * This compatibility wrapper delegates to the renamed Xross Orchestrator API.
 */
@Deprecated(since = "1.2.1", forRemoval = false)
public final class XrossClusterManager implements AutoCloseable {
    private final XrossOrchestrator delegate;

    public XrossClusterManager(
            XrossEngine engine,
            XrossClusterConfiguration configuration,
            XrossDbClient databaseClient,
            String discordToken
    ) {
        this(new XrossOrchestrator(engine, configuration, databaseClient, discordToken));
    }

    public XrossClusterManager(XrossOrchestrator delegate) {
        this.delegate = delegate;
    }

    public XrossClusterAssignment joinAndResolveInitialAssignment() throws Exception { return delegate.joinAndResolveInitialAssignment(); }
    public void runWithStartupLease(ThrowingRunnable action) throws Exception { delegate.runWithStartupLease(action::run); }
    public void markInitialRuntimeRunning() { delegate.markInitialRuntimeRunning(); }
    public void startBackground() { delegate.startBackground(); }
    public XrossClusterStatus status() { return delegate.status(); }
    public boolean isLeader() { return delegate.isLeader(); }
    public String nodeId() { return delegate.nodeId(); }
    public XrossClusterAssignment localAssignment() { return delegate.localAssignment(); }
    public XrossOrchestrator orchestrator() { return delegate; }
    @Override public void close() { delegate.close(); }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
