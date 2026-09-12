# Open-source readiness

Current release candidate: **OpenXrossEngine 1.4.1**. Historical versioned audit
documents describe earlier snapshots; use `OPEN_SOURCE_AUDIT_1.4.1.md` for current status.

## Implemented locally

- Apache-2.0 LICENSE, project NOTICE and a resolved dependency license inventory.
- Credential exclusions, source-release allowlist and rejected external/symbolic release inputs.
- Hash-verified plugin snapshots with no external manifest/index dependencies.
- Updated jsoup dependency, locked versions and artifact/metadata checksum verification.
- Security hardening for bounded remote responses, secure XrossDB transport defaults, JSON parser limits and per-artifact plugin approval.
- DBP has been removed from the current Engine API and runtime.

## Before public publication

1. Scan every branch/tag and the complete history of the actual repository for secrets.
   Rotate any genuine exposed credentials; merely deleting a file is insufficient.
2. Verify repository ownership/URLs and enable a private vulnerability-reporting channel.
3. Recheck dependency advisories at publication time and review native/binary redistribution terms.
4. Run `release.bat`, inspect `build/release`, and scan the generated source archive.
5. For Maven Central, complete namespace ownership and artifact-signing setup described
   in `PUBLISHING_MAVEN_CENTRAL.md`.

The supplied local source folder has no readable Git history. Live deployments,
external Editors/providers, downstream plugins and native-library internals are
outside the completed local source audit. No public publication is performed by the build.
