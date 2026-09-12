# Multi-machine AUTO cluster example

Deploy the same OpenXrossEngine tree/artifacts to every machine and use a shared persistence provider.

The only setting that normally differs per node is the node ID:

```bash
# node A
export XROSS_CLUSTER_MODE=auto
export XROSS_CLUSTER_ID=my-bot-production
export XROSS_NODE_ID=node-a

# node B
export XROSS_CLUSTER_MODE=auto
export XROSS_CLUSTER_ID=my-bot-production
export XROSS_NODE_ID=node-b

# node C
export XROSS_CLUSTER_MODE=auto
export XROSS_CLUSTER_ID=my-bot-production
export XROSS_NODE_ID=node-c
```

Use `shards.mode=auto_scale` when the elected leader should increase the global shard total according to Discord Get Gateway Bot.

Do not point each node at its own local JSON/memory database. All AUTO cluster nodes must share the same cluster-safe `XrossDbClient` dataset.

For containers/orchestrators, make `XROSS_NODE_ID` stable and unique. Deploy identical `plugins/` and `system-plugins/` artifacts to all nodes.
