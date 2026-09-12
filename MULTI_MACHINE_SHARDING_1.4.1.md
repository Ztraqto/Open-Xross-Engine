# OpenXrossEngine 1.4.1 multi-machine sharding

This document is the operational and implementation contract for distributed sharding in OpenXrossEngine 1.4.1. Codex and contributors should read this before changing cluster behavior.

## Goal

A production bot should be able to run the same OpenXrossEngine deployment on multiple machines without manually calculating which Guild belongs to which worker.

The normal AUTO deployment is:

```text
                    Shared XrossDbClient
                   (control-plane store)
                           |
           +---------------+---------------+
           |               |               |
        Node A          Node B          Node C
       LEADER*          WORKER          WORKER
      shards 0,3       shards 1,4      shards 2,5
```

`LEADER*` is a renewable role. If that node disappears, another leader-eligible node can acquire the lease.

The shared store can be XrossDB backed by PostgreSQL or an external System Plugin provider such as a future Cloudflare D1 provider. The Engine core does not depend on Cloudflare.

## Quick start

On every machine:

1. Deploy the same OpenXrossEngine version and the same application/System Plugin artifacts.
2. Configure the same Discord bot token.
3. Configure the same `clusterId`.
4. Use a shared `XrossDbClient` backend/provider.
5. Give every machine a unique `nodeId`. Unique hostnames work by default; containers should set it explicitly.
6. Set `cluster.mode=auto`.
7. Use `shards.mode=auto_scale` if Discord's recommendation should increase the global shard total automatically.

Example:

```json
{
  "cluster": {
    "mode": "auto",
    "clusterId": "my-bot-production",
    "nodeId": "node-a",
    "leaderEligible": true,
    "maxShardsPerNode": 0,
    "heartbeatSeconds": 10,
    "nodeTimeoutSeconds": 45,
    "coordinationLossTimeoutSeconds": 25,
    "leaderLeaseSeconds": 30,
    "reconcileSeconds": 10,
    "formationDelaySeconds": 10,
    "joinTimeoutSeconds": 180,
    "transitionTimeoutSeconds": 180,
    "startupLeaseSeconds": 180
  },
  "shards": {
    "mode": "auto_scale",
    "totalShards": 1,
    "shardIds": [],
    "autoScaleCheckSeconds": 300,
    "autoScaleMaxShards": 0
  }
}
```

All nodes can share that file and set only `XROSS_NODE_ID` differently.

## Cluster modes

### OFF

No distributed coordination. This is the 1.1.x single-process path.

### STATIC

Traditional multi-process deployment. Operators manually assign shard IDs while keeping the same global `totalShards` on every process.

### AUTO

OpenXross owns cluster membership and shard assignment. Explicit local shard IDs are not required. The leader computes the topology from active nodes and the global shard mode.

## Shard total vs local shard IDs

Discord routes Guild events using:

```text
shard_id = (guild_id >> 22) % num_shards
```

Every machine must therefore agree on the same `num_shards` / `totalShards`. A node only starts the shard IDs assigned to it.

OpenXross stores one global topology generation containing:

```text
generation
totalShards
phase
leaderNodeId
assignments[nodeId] = [shard ids]
```

## Leader election

Leader election uses a shared lease record with expected-revision compare-and-set semantics.

A leader:

- renews its lease;
- reads active node heartbeats;
- retrieves Discord's recommended shard count when required;
- computes deterministic assignments;
- publishes topology generations.

Leader status is not tied to shard 0. A worker owning any shard can become leader if `leaderEligible=true` and it wins the lease.

## Node membership

Every node periodically writes:

```text
nodeId
engineVersion
startedAt
heartbeatAt
leaderEligible
maxShards
preparedGeneration
runningGeneration
localShards
state
```

A node is considered dead only after `nodeTimeoutSeconds`.

`coordinationLossTimeoutSeconds` MUST remain lower than `nodeTimeoutSeconds`. This ordering means a partitioned worker should shut down its local Discord sessions before the leader can time it out and assign those shard IDs elsewhere.

## Low-churn assignment

`XrossClusterPlanner` balances the required shard count across active nodes while retaining as many previous assignments as possible.

Adding a machine therefore moves only enough shards to balance capacity instead of rebuilding every assignment arbitrarily.

`maxShardsPerNode=0` means unlimited. A positive value caps that node's assignment. If aggregate active capacity is below the global shard total, planning fails rather than silently dropping shards.

## Topology transition protocol

Runtime rebalancing deliberately favors correctness over zero downtime.

```text
1. Leader calculates next topology.
2. Leader writes generation N as PREPARING.
3. Every live node observes N.
4. Each node stops all old-generation local Discord shards.
5. Node records preparedGeneration=N.
6. Leader waits until every active node is prepared.
7. Leader promotes generation N to ACTIVE.
8. Each node obtains the startup lease.
9. Node starts only its new assigned shard IDs.
10. Node records runningGeneration=N.
```

A new generation is not planned while the current active topology is still waiting for assigned nodes to report it as running.

Discord permits parallel sessions and different `num_shards` values for zero-downtime handoff. OpenXross 1.4.1 does not enable that behavior because a generic Engine also needs robust fencing/deduplication of duplicate events. A later release can add a fenced parallel handoff protocol.

## JDA startup serialization

JDA's `ShardManager` supports selecting a subset of shard IDs for a JVM/server. Its session controller handles login throttling inside a local JDA deployment, but it is not a shared object across machines.

OpenXross therefore uses a shared startup lease. Only one node at a time creates its local JDA shard set during topology activation. This is conservative and intentionally easier to reason about than a distributed implementation of Discord's `max_concurrency` buckets.

Before a topology requiring new sessions is published, the leader checks Get Gateway Bot session-start allowance. 1.4.1 conservatively requires enough remaining starts for the full shard total.

## Shard 0

Discord sends Gateway events without a `guild_id`, including DMs and some entitlement/subscription events, only to shard 0. In a cluster, whichever node currently owns shard 0 owns those events.

Do not implement global DM processing independently on every node.

## Coordination failure and split-brain protection

If a node cannot contact the shared cluster store for `coordinationLossTimeoutSeconds`, it calls the Engine fail-closed path and disconnects its local Discord shards.

The leader does not consider that node dead until the longer `nodeTimeoutSeconds` expires.

This timing order is an important safety property and must not be reversed.

When coordination returns, the node reads the active topology and can rejoin its current assignment.

## Shared database contract

AUTO mode requires a truly shared store.

Valid examples:

- a shared PostgreSQL-backed XrossDB;
- a remote shared XrossDB service;
- an external System Plugin implementing `XrossDbClient`, such as Cloudflare Worker + D1.

Invalid:

- a separate local JSON/XrossDB file on each worker;
- independent in-memory databases on each worker.

When a node runs combined BOT+DATABASE with the built-in XrossDB in AUTO mode, OpenXross 1.4.1 requires the PostgreSQL backend.

### Required CAS behavior

The following calls are synchronization primitives, not optional optimization:

```text
write(key, payload, expectedRevision)
delete(key, expectedRevision)
```

A provider MUST atomically reject stale expected revisions with `XrossDbConflictException` (or the equivalent behavior required by `XrossDbClient`).

A Cloudflare D1 System Plugin must implement this transactionally through its Worker API. If it treats `expectedRevision` as advisory, leader election and topology transitions are unsafe.

## Application plugins and approvals

Application Plugin instances remain local to a JVM. OpenXross does not transmit executable JARs over the cluster.

Deployment requirement:

```text
plugins/ on Node A == plugins/ on Node B == plugins/ on Node C
```

Approval facts are stored in the shared persistence layer. An approval may be performed on the node that receives the Xross Console Discord interaction; other nodes poll the shared approval state and activate/deny the matching local artifact.

Fingerprint matching still applies. If a node has a different JAR fingerprint, it does not magically become the approved artifact.

A future `PluginArtifactProvider` System Plugin can automate trusted artifact rollout, but it is intentionally outside 1.4.1.

## Xross Console

There is no fixed "console shard".

- Terminal/STDIN Xross Console is process-local.
- Discord `/xross` is received on whichever node owns the Guild's shard.
- `/xross version` reports Engine version, cluster mode, node ID, leader/worker state, global shard total, and local shard IDs.
- Mutating global operations must store their authoritative state in shared persistence or use a cluster control API. They must not depend on one shard's RAM.

## Auto-scale behavior

With `shards.mode=auto_scale`, the cluster leader periodically checks Discord Get Gateway Bot.

1. If Discord recommendation <= current total, nothing changes.
2. If recommendation > current total, leader plans a new global topology.
3. Session-start budget is checked.
4. PREPARING/ACTIVE transition runs across all nodes.

Automatic down-scaling is intentionally disabled in 1.4.1.

For large bots, Get Gateway Bot remains the authority for a valid shard count.

## Operational recommendations

- Use explicit stable `cluster.nodeId` values in Docker/Kubernetes.
- Run at least two `leaderEligible` nodes if automatic failover matters.
- Use a highly available shared database/provider.
- Keep clocks reasonably synchronized (NTP) because leases use wall-clock timestamps.
- Keep `coordinationLossTimeoutSeconds < nodeTimeoutSeconds`.
- Deploy identical Engine/System Plugin/Application Plugin versions before a rebalance.
- Monitor remaining Discord session starts before large maintenance events.
- Treat the cluster store as control-plane infrastructure; latency and availability affect safe shard ownership.

## Future work

Potential post-1.2 work:

- fencing tokens propagated into worker operations;
- parallel zero-downtime topology handoff;
- distributed `max_concurrency` bucket scheduler instead of whole-node startup serialization;
- trusted Plugin artifact distribution provider;
- optional node labels/weights for heterogeneous machines;
- rolling Engine-version compatibility negotiation;
- cluster administration command (`/xross cluster status`, drain, rebalance).
