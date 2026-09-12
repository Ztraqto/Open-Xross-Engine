# Security notes

OpenXrossEngine loads ordinary plugins and privileged System Plugins into the Bot JVM. Neither mechanism is a security sandbox.

## Release inputs and credentials

Use `release.bat` or `./gradlew stageRelease`. The public source archive uses the
allowlist in `gradle/release.gradle`; do not ZIP an operating Bot directory.
Runtime data, logs, plugin binaries, trust stores, local AI settings, `.env` files
and private keys do not belong in public source. `.gitignore` also covers the
legacy `xecute.aits.config.json` and `xecute.aits.settings.json` paths.
Review new resource paths before adding them to the release allowlist.

Dependencies are locked in `gradle.lockfile` and SHA-256 verified through
`gradle/verification-metadata.xml`. Treat updates to either file as changes to
trusted build inputs. Review upstream checksums and vulnerability advisories
before regenerating verification metadata; do not regenerate it merely to
silence a verification failure.

## Authentication and storage

OpenXrossEngine 1.4.1 removes the former Discord Bot Protocol (DBP) implementation. Do not depend on Discord messages as a hidden inter-process control channel; use authenticated, bounded external service APIs or the Xross Orchestrator storage contracts instead.

Keep XrossDB on loopback or a private network behind a TLS reverse proxy.
The gateway uses a bounded executor queue and closes active exchanges at
`XrossDbConfiguration.requestTimeout()` (10 seconds by default). Configure
header, connection and rate limits at the reverse proxy as well. Gateway bearer
credentials authorize the whole database and belong only to trusted Bot processes.

Console logs are visible to everyone who can read the selected Discord channel.
Use a private administrator channel and retain the default approvals-only mode
unless broader logs are necessary. Plugins must not log credentials or personal data.

## System Plugins

System Plugins load before Engine services and Discord. They can register infrastructure providers and therefore must be treated as fully trusted native application code.

For production deployments:

- enable `systemPlugins.requireTrustedFingerprints`;
- pin reviewed JAR SHA-256 fingerprints;
- package dependency classes inside the reviewed JAR; manifest `Class-Path`
  and JAR indexes that can load other archives are rejected before approval/loading;
- keep the trust file writable only by the deployment administrator;
- use least-privilege service credentials for external providers;
- never place API secrets in plugin manifests;
- restart the process after changing System Plugins.

## External providers

A remote storage provider such as Cloudflare D1 should be accessed through a narrowly scoped authenticated service API. Protect requests against tampering and replay, and keep financial/authorization decisions on the trusted server side.

## Public disclosure

Before the repository is made public, configure a private vulnerability-reporting channel (for example repository security advisories). Do not publish credentials, exploit details for an unfixed deployment, or production trust fingerprints in public issues.
