# OpenXrossEngine 1.4.0 — System Plugin / Shard Architecture

この文書は Ztraqto Ads 開発前に OpenXrossEngine へ加えた基盤変更の仕様書です。Codex は Ztraqto Ads 側を実装する前に本ファイルを読んでください。

## 目的

今回の改修には2つの目的があります。

1. OpenXrossEngine内部のインフラ実装を `System Plugin` で差し替えられるようにする。
2. 複数Shard運用時にGuildの担当判定・状態取得・終了前処理をEngine標準APIとして扱えるようにする。

Ztraqto Adsでは内蔵XrossDBを使用せず Cloudflare D1 を共通DBにする予定です。D1そのものはOpenXrossEngineへ直書きせず、後から `XrossDbClientProvider` を実装するSystem Pluginとして追加してください。

---

# 1. 起動シーケンス

OpenXrossEngine 1.1では起動順序を以下に変更しています。

```text
XrossEngine.start()
  1. XrossConfigurationを保持
  2. SystemPluginManager生成
  3. system-plugins/*.jar を読み込み
  4. System PluginがProviderを登録
  5. SystemProviderRegistryをseal
  6. DATABASE roleがあれば従来XrossDB Runtimeを起動
  7. BOT role用のXrossDbClientを解決
       - databaseProvider=xrossdb -> 従来XrossDB
       - その他 -> System PluginのXrossDbClientProvider
  8. XrossBotRuntime起動
  9. Internal Services初期化
 10. Discord ShardManager接続
 11. 通常Application Pluginを読み込み
 12. System Plugin.onEngineReady()
```

停止順序は以下です。

```text
System Plugin.onEngineStopping()
  -> Shard lifecycle STOPPING通知
  -> Application Plugin unload
  -> Discord shutdown
  -> Internal Services shutdown
  -> XrossDbClient.close()
  -> XrossDB Runtime shutdown (使用時)
  -> System Plugin.onUnload()
  -> System Plugin ClassLoader close
```

`onEngineStopping()` はpreAd-2-RTなどがD1へ最終checkpointを行う用途に使えます。

---

# 2. System Plugin

通常Pluginとは別の特権プラグインです。

通常Plugin:

```text
plugins/*.jar
plugin.json
extends XrossPlugin
Discord接続後にロード
Hot Reloadあり
```

System Plugin:

```text
system-plugins/*.jar
system-plugin.json
extends XrossSystemPlugin
Service/Discordより前にロード
Hot Reloadなし
変更には再起動が必要
```

System PluginはJVM内部のインフラへアクセスできるため、通常Pluginより強い信頼が必要です。

## system-plugin.json

```json
{
  "id": "cloudflare-d1",
  "name": "Cloudflare D1 Provider",
  "version": "1.0.0",
  "main": "com.ztraqto.openxross.system.d1.CloudflareD1SystemPlugin",
  "description": "Cloudflare Worker/D1 storage provider",
  "authors": ["Ztraqto"],
  "engineApi": "1"
}
```

IDは以下に一致する必要があります。

```text
[a-z0-9][a-z0-9._-]{0,63}
```

## XrossSystemPlugin lifecycle

```java
public abstract class XrossSystemPlugin {
    public void onLoad() throws Exception {}
    public void registerProviders(SystemProviderRegistry registry) throws Exception {}
    public void onEngineReady() throws Exception {}
    public void onEngineStopping() throws Exception {}
    public void onUnload() throws Exception {}
}
```

`registerProviders()`終了後、全System Plugin読み込み完了時にRegistryはsealされます。Engine起動後のProvider追加は禁止です。

## Provider Registry

```java
registry.register(MyProvider.class, provider);
registry.find(MyProvider.class, "provider-id");
registry.require(MyProvider.class, "provider-id");
registry.list(MyProvider.class);
```

Providerは以下を実装します。

```java
public interface XrossSystemProvider {
    String id();
    default int priority() { return 0; }
}
```

同じProvider contract内で同じIDを2回登録すると起動失敗になります。

System PluginがProviderを一部登録した後に例外を出した場合、そのPluginが追加したProviderはrollbackされます。

---

# 3. 外部DB Provider

新規API:

```java
public interface XrossDbClientProvider extends XrossSystemProvider {
    XrossDbClient createClient(
        XrossEngine engine,
        XrossConfiguration configuration
    ) throws Exception;
}
```

Ztraqto AdsのCloudflare D1実装はこのinterfaceを利用してください。

OpenXross内部の `GuildSettingsService` / `PluginApprovalService` / `StorageService` 等は引き続き `XrossDbClient` を利用します。そのためD1 Provider側で既存 `XrossDbClient` contractを満たせば、既存Serviceを大規模に書き換える必要はありません。

## 推奨D1構成

BotからD1へ直接SQL接続しないでください。

```text
OpenXross / Ztraqto Ads Bot
        |
        | HTTPS + Service Authentication
        v
Cloudflare Worker API
        |
        | D1 Binding
        v
Cloudflare D1
```

System Plugin内の `XrossDbClient` 実装がWorker APIへアクセスします。

最低限実装が必要な既存contract:

```text
read
write
 delete
scanPage
applyBatch
verifyConnection
close
```

revisionによるoptimistic concurrencyの意味を維持してください。

Ztraqto Adsでは別途、金融処理はWorker側で原子的に行い、ShardからD1を直接更新する設計にはしない予定です。

---

# 4. System Plugin設定

`xross.config.example.json` (copy to private `xross.config.json` before running):

```json
{
  "systemPlugins": {
    "directory": "system-plugins",
    "databaseProvider": "xrossdb",
    "requireTrustedFingerprints": false,
    "trustFile": "system-plugins/trusted.sha256"
  }
}
```

Ztraqto Ads + D1 Provider導入後は例として:

```json
{
  "systemPlugins": {
    "directory": "system-plugins",
    "databaseProvider": "cloudflare-d1",
    "requireTrustedFingerprints": true,
    "trustFile": "system-plugins/trusted.sha256"
  }
}
```

CLI:

```text
--system-plugin-dir <path>
--db-provider <xrossdb|provider-id>
--system-plugin-trust-file <path>
--require-system-plugin-trust
```

Environment fallback:

```text
XROSS_SYSTEM_PLUGIN_DIR
XROSS_DB_PROVIDER
XROSS_SYSTEM_PLUGIN_TRUST_FILE
XROSS_SYSTEM_PLUGIN_REQUIRE_TRUST
```

## Fingerprint trust

`requireTrustedFingerprints=false` は開発向けです。System Pluginは任意コードを実行できるため、本番ではtrueを推奨します。

`trusted.sha256`:

```text
# SHA-256 [optional comment]
0123456789abcdef...  cloudflare-d1-1.0.0.jar
```

行の最初の64桁SHA-256だけが検証に利用されます。

読み込み時にはJAR fingerprintを検証した後、一時snapshotを作ってそのsnapshotからClassLoaderを生成します。起動中のJAR差し替えによるTOCTOUを避けるためです。

---

# 5. Shard強化

従来の `XrossShardConfiguration` はJDAの `setShardsTotal` / `setShards` に渡すだけでした。

1.1では以下を追加しています。

## Guild -> Shard

```java
int shardId = configuration.shardIdForGuild(guildId);
boolean owned = configuration.ownsGuild(guildId);
```

式:

```text
(guildId >>> 22) % totalShards
```

Discordの決定的なGuild割当と同じ考え方です。

Shard総数を変えない限り、同じGuildは同じShard IDになります。

## Shard range

```java
XrossShardConfiguration.builder()
    .totalShards(16)
    .shardRange(4, 7)
    .build();
```

これはShard 4,5,6,7のみを現在プロセスが担当する設定です。

## Timeout

```json
{
  "shards": {
    "totalShards": 16,
    "shardIds": [4, 5, 6, 7],
    "readyTimeoutSeconds": 120,
    "shutdownTimeoutSeconds": 5
  }
}
```

CLI:

```text
--shard-ready-timeout <seconds>
--shard-shutdown-timeout <seconds>
```

Environment:

```text
XROSS_SHARDS_TOTAL
XROSS_SHARD_IDS
XROSS_SHARD_READY_TIMEOUT_SECONDS
XROSS_SHARD_SHUTDOWN_TIMEOUT_SECONDS
```

---

# 6. ShardService

新規Internal Service:

```java
ShardService shardService = engine
    .getServiceManager()
    .getService(ShardService.class);
```

主要API:

```java
int shardIdForGuild(long guildId)
boolean ownsGuild(long guildId)
Optional<JDA> shardForGuild(long guildId)
List<XrossShardSnapshot> snapshots()
void addLifecycleListener(XrossShardLifecycleListener listener)
void removeLifecycleListener(XrossShardLifecycleListener listener)
```

Snapshot:

```text
shardId
totalShards
JDA status
guildCount
gatewayPing
```

Lifecycle:

```java
shardService.addLifecycleListener(new XrossShardLifecycleListener() {
    @Override
    public void onShardReady(int shardId, JDA shard) {
        // restore ephemeral state if needed
    }

    @Override
    public void onShardStopping(int shardId, JDA shard) {
        // flush/checkpoint state before shutdown
    }
});
```

Ztraqto AdsではpreAd-2-RTのRAM状態をShard単位で持ち、`onShardStopping`で必要な長期学習状態だけD1へcheckpointする設計が推奨です。

---

# 7. Ztraqto Adsでのデータ境界

このEngine改修ではZtraqto Ads固有のテーブルや広告アルゴリズムは実装していません。

予定されている境界:

```text
D1 persistent
  Guild Settings: 1 Guild = 1 row
  preAd-2-RT persistent state: 1 Guild = 1 row
  Campaign
  Credit Ledger
  Earnings Ledger
  Stripe Events
  Referral/Fraud

Shard RAM ephemeral
  current activity counters
  1m/5m/15m windows
  current trend
  current hot channels
  cooldowns
  delivery candidates
```

`MESSAGE_CREATE`ごとにD1を書き込まないでください。

---

# 8. 通常Pluginとの互換性

既存の `XrossPlugin` / `plugins/` / Plugin approval / hot reload機構は残しています。

既定値:

```text
systemPlugins.databaseProvider = xrossdb
```

なのでSystem Pluginを何も入れなければ従来通りXrossDBを利用します。

通常PluginはSystem Pluginより後にロードされるため、外部DB Providerが選択されている場合でも既存 `StorageService` / `PluginDataStore` は同じ `XrossDbClient` interface経由で利用できます。

---

# 9. Security notes

System Pluginはsandboxではありません。

- JVM内で任意コードを実行可能
- Service/DB/Secretsへ到達可能
- hot reloadしない
- 本番ではSHA-256 trustを有効化する
- System Plugin JARの更新時は新fingerprintを明示的にtrustする
- D1 SecretやWorker Service Tokenを通常Pluginへ公開しない

Cloudflare D1 System Pluginを実装するときは、Worker Service Authentication、timestamp、nonce、request body hash、HMACまたは同等のリプレイ耐性のある署名方式を検討してください。

---

# 10. 新規/変更された主要ファイル

新規:

```text
src/main/java/org/ztraqto/xross/api/system/XrossSystemProvider.java
src/main/java/org/ztraqto/xross/api/system/XrossDbClientProvider.java
src/main/java/org/ztraqto/xross/api/system/SystemProviderRegistry.java
src/main/java/org/ztraqto/xross/api/system/SystemPluginMeta.java
src/main/java/org/ztraqto/xross/api/system/SystemPluginContext.java
src/main/java/org/ztraqto/xross/api/system/XrossSystemPlugin.java
src/main/java/org/ztraqto/xross/core/SystemPluginManager.java
src/main/java/org/ztraqto/xross/config/XrossSystemPluginConfiguration.java
src/main/java/org/ztraqto/xross/api/shard/XrossShardLifecycleListener.java
src/main/java/org/ztraqto/xross/api/shard/XrossShardSnapshot.java
src/main/java/org/ztraqto/xross/service/ShardService.java
examples/system-plugin-template/
```

主な変更:

```text
XrossEngine.java
XrossBotRuntime.java
ServiceManager.java
XrossConfiguration.java
XrossShardConfiguration.java
XrossArguments.java
xross.config.example.json
build.gradle
README.md
```

---

# 11. Validation status

この作業環境は外部ネットワークへ接続できず、Gradle Wrapperが `services.gradle.org` からGradle 7.4.2を取得できなかったため、フル `./gradlew clean test` は実行できませんでした。

代わりに以下を実施済みです。

- Java 17 (`javac --release 17`) で変更対象クラスを既存compiled API + dependency stubsに対してコンパイル確認
- SystemProviderRegistryの登録/取得/sealのsmoke確認
- Shard assignment formulaのsmoke確認
- JUnitテストソース追加

Codexがネットワーク/Gradle cacheのある環境で最初に以下を実行してください。

```bash
./gradlew clean test
```

その後、System Plugin templateのビルドも確認してください。

```bash
cd examples/system-plugin-template
../../gradlew clean build
```

---

# 12. Ztraqto Ads開発時の次の作業

OpenXrossEngine側の次の実装候補は以下です。

1. `cloudflare-d1` System Pluginを別module/JARとして作る
2. Worker API仕様を定義する
3. D1上で `XrossDbClient` のrevision semanticsを実装する
4. Ztraqto Ads用Gateway Intentを必要最小限にする仕組みを追加する
5. preAd-2-RTのShard-local state/checkpointを実装する
6. Shard間で共有が必要なCampaign budget reservationはCloudflare Worker側へ集約する

D1実装をEngine本体へ直接追加しないでください。System Pluginとして維持することが今回の設計上の前提です。

---

# 12. 1.1 final additions for open-source readiness

## System Plugin dependency ordering

`system-plugin.json` now supports `requires`.

```json
{
  "id": "example-storage-consumer",
  "name": "Example Storage Consumer",
  "version": "1.0.0",
  "main": "com.example.ConsumerPlugin",
  "engineApi": "1",
  "requires": ["base-infrastructure"]
}
```

OpenXross performs discovery and validation for every System Plugin **before any plugin code is executed**.

Validation includes:

- duplicate plugin IDs
- unsupported `engineApi`
- missing required plugins
- self-dependencies
- duplicate dependency entries
- dependency cycles
- fingerprint trust when enabled

The loader then performs a deterministic topological load. Unload and `onEngineStopping()` callbacks run in reverse load order.

`requires` guarantees lifecycle order only. A System Plugin must not directly compile against implementation classes inside another plugin JAR because each plugin uses its own class loader. Shared provider contracts belong in OpenXross public API or in a separately shared API artifact.

## Public System Plugin API version

The current public bootstrap API major is available as:

```java
XrossSystemPlugin.API_MAJOR
```

A plugin manifest must set:

```json
"engineApi": "1"
```

Breaking changes to the System Plugin bootstrap contract require a new major API. Additive changes should remain compatible with existing API-major-1 plugins.

## External database runtime rule

`XrossRole.DATABASE` means "host built-in XrossDB". It is intentionally not a generic external storage role.

When `systemPlugins.databaseProvider` is not `xrossdb`, run the embedding application in BOT-only mode. OpenXross rejects `DATABASE` + external database provider combinations so a private XrossDB runtime cannot accidentally start beside a D1/HTTP provider.

An external `XrossDbClientProvider` is connection-verified during engine bootstrap. If verification fails, the client is closed and engine startup fails before Discord is connected.

## Expanded shard lifecycle

`XrossShardLifecycleListener` now exposes:

```java
onShardReady(...)
onShardDisconnected(...)
onShardResumed(...)
onShardRecreated(...)
onShardStopping(...)
onShardShutdown(...)
```

OpenXross translates JDA session events to these engine-level callbacks. Application plugins therefore do not need to depend directly on JDA session-event classes merely to maintain shard-local state.

Recommended state handling:

```text
READY / RESUMED / RECREATED
  -> rebuild/revalidate transient shard state as needed

DISCONNECTED
  -> stop assuming live Gateway delivery

STOPPING
  -> graceful final checkpoint (preAd-2-RT etc.)

SHUTDOWN
  -> release remaining shard-local resources
```

Callbacks are best-effort and should return quickly. Expensive persistence should be handed to an application-controlled executor with bounded shutdown semantics.

## Open-source boundary

OpenXrossEngine is being prepared for a future public source release. The following rules are intentional architecture constraints:

- Engine source must remain provider-neutral. Do not place Ztraqto Ads credentials, Cloudflare account IDs, D1 database IDs, Stripe secrets, or product-specific API endpoints in OpenXrossEngine.
- Cloudflare D1 integration belongs in a separate System Plugin/module implementing public OpenXross contracts.
- Application-specific policy belongs in the embedding Bot, not in Engine bootstrap code.
- Public API packages under `com.ztraqto.openxross.api` should avoid product-specific types.
- System Plugin JARs are privileged native JVM code, not a sandbox. Fingerprint trust is a deployment hardening mechanism, not a sandbox boundary.
- Secrets must be loaded from deployment configuration/environment and must never be committed in examples.
- Preserve backwards compatibility for normal `XrossPlugin` integrations wherever possible.

No open-source license has been selected in this development snapshot. Before a public repository release, the project owner must choose and add a `LICENSE` file and review dependency-license compatibility. Absence of a `LICENSE` must not be interpreted as an open-source grant.

## Release verification

Before tagging 1.1 or publishing source, run at minimum:

```text
./gradlew clean test
./gradlew jar
./gradlew javadoc
./gradlew :examples:...   (or build each example through its composite build)
```

Also verify:

- no real Discord token/API secret exists in tracked files or Git history
- `system-plugins/trusted.sha256` contains no deployment-specific hashes in the public repository
- example configuration contains placeholders only
- all public API additions have JavaDoc
- dependency vulnerability and license scans pass
- System Plugin dependency-cycle/missing-dependency behavior has integration tests using test JARs

The supplied development package was additionally source-compiled for the changed API/core classes in an offline verification environment. A full Gradle dependency-resolved test run still needs to be executed in an environment with the Gradle distribution/dependencies available.


> The 1.1.2 section below is historical. OpenXrossEngine 1.4.0 Xross Orchestrator behavior supersedes its single-process limitation; see `MULTI_MACHINE_SHARDING_1.4.0.md`.

# OpenXrossEngine 1.1.2 automatic shard extension

## Modes

`XrossShardMode` now provides:

- `FIXED`: preserve explicit configured topology.
- `RECOMMENDED`: query Discord Get Gateway Bot once before Bot runtime start and use the resolved total.
- `AUTO_SCALE`: resolve the startup total and periodically query Discord; if the recommendation rises, replace the Bot runtime with the larger all-shards topology. No automatic downscale is performed in 1.1.2.

`RECOMMENDED` and `AUTO_SCALE` require one process to own all shard IDs. Explicit partial `shardIds` are rejected. A multi-process auto-scaler belongs in a future System Plugin providing a distributed shard coordinator.

## AUTO_SCALE lifecycle

1. `ShardAutoScaler` polls authenticated Discord `GET /gateway/bot`.
2. If `recommended <= current`, no topology change occurs.
3. Cooldown and Discord `session_start_limit.remaining` are checked.
4. Engine emits `onShardTopologyChanging(current, target)`.
5. Existing shard/plugin state receives STOPPING callbacks before application-plugin unload.
6. Existing Bot runtime is stopped while Engine System Plugins remain active.
7. Engine starts a new Bot runtime using all shard IDs in the new total.
8. Engine emits `onShardTopologyChanged(current, target)`.
9. If startup fails, Engine attempts to restore the previous topology.

This controlled replacement can create a short Gateway interruption. Discord documents that sessions using different `num_shards` can overlap for zero-downtime handoff. 1.1.2 deliberately does not use that technique because a generic Engine needs distributed fencing/deduplication before parallel shard topologies can be considered safe.

## Configuration

```json
{
  "shards": {
    "mode": "auto_scale",
    "totalShards": 1,
    "shardIds": [],
    "readyTimeoutSeconds": 120,
    "shutdownTimeoutSeconds": 5,
    "autoScaleCheckSeconds": 300,
    "autoScaleCooldownSeconds": 900,
    "autoScaleMaxShards": 0
  }
}
```

`autoScaleMaxShards=0` means no OpenXross-side ceiling. A non-zero ceiling is a deployment safety guard and can conflict with Discord's recommended/required topology; deployments should normally leave it at zero unless they have an explicit operational reason.

## Version command

`/xross version` reports:

- OpenXrossEngine version (`1.1.2`),
- current shard mode,
- configured/active total shard count.

`/xross` uses real Discord subcommands in 1.1.2. The runtime retains a temporary fallback for stale 1.1.x `action` option interactions so an upgraded deployment can resynchronize commands.

---

# OpenXrossEngine 1.4.0 extension — multi-machine Xross Orchestrator

The 1.2.x line adds a distributed Xross Orchestrator on top of the 1.1 System Plugin/storage foundation; 1.4.0 is the current Ztraqto public-release candidate.

```text
System Plugin XrossDbClientProvider
        |
        v
shared XrossDbClient
        |
        +-- leader lease
        +-- node heartbeats
        +-- topology generations
        +-- startup lease
        +-- shared Plugin approvals
```

A future Cloudflare D1 provider must therefore support more than simple settings storage. Its expected-revision writes/deletes are cluster synchronization primitives and MUST be atomic.

In `cluster.mode=auto`, do not start a local-only database on each machine. Use shared PostgreSQL-backed XrossDB, one remote XrossDB service, or a cluster-safe external provider.

See `MULTI_MACHINE_SHARDING_1.4.0.md` and `CODEX_1.4.0_IMPLEMENTATION_NOTES.md` for the current 1.4.0 contract.
