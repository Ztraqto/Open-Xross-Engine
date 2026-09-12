# OpenXrossEngine 1.4.1 public-source security audit

Audit date: 2026-09-11. Baseline: supplied OpenXrossEngine 1.4.0 source plus the 1.4.1 hardening patch set.

## Release assessment

No unauthenticated, direct Critical-class RCE path was identified in the reviewed Engine source. The main 1.4.0 release blockers found during the review were addressed in 1.4.1: bounded Remote XrossDB responses, secure-by-default external XrossDB transport, secret-file loading, opaque plugin approval request IDs, bulk-approval removal, JSON resource limits, image/HTTP response bounds, and removal of DBP.

## Trust boundaries that remain

- Application plugins and System Plugins run in the Bot JVM. OpenXross permissions are API controls, not a JVM/OS sandbox. A malicious approved plugin can potentially access process environment, files and network with the Bot OS user's rights.
- The transitive audio/native dependency graph still contains old JNA 4.4.0 through upstream dependencies. No confirmed 2026 blocker was established for the Engine's use, but upgrading requires audio/native compatibility testing and remains a maintenance item.
- The complete Git history of the eventual GitHub repository is outside this ZIP and must be secret-scanned before first public push.
- GitHub repository settings, Actions secrets, branch/tag protection and Maven signing keys are deployment/repository controls and are not validated by this source archive.

## Dependency posture

The locked graph keeps security-fixed versions identified during review, including pgJDBC 42.7.13, Jackson 2.22.2, Logback 1.6.3, Rhino 1.7.14.1, jsoup 1.23.1 and protobuf-java 4.28.2. Dependency locks and SHA-256 verification metadata are retained. Re-run advisory scanning at publication time.

## Public release decision

The 1.4.1 source is suitable as a GitHub/Maven Central release candidate after a clean networked build (`./gradlew clean test build javadoc sourcesJar`), full repository-history secret scan, and final dependency advisory scan. This environment could not download the Gradle distribution, so the final 1.4.1 Gradle test suite was not independently re-run here.
