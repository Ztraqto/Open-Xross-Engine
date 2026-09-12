# Ztraqto namespace migration

OpenXrossEngine 1.2.2 is the first public-release candidate after the Nestware -> **Ztraqto** rename.

## Maven coordinate

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.ztraqto.openxross:openxross-engine:1.2.2")
}
```

## Java imports

Replace the pre-release namespace:

```text
org.nestware.xross.*
```

with:

```text
com.ztraqto.openxross.*
```

This affects Xecute, Coolol and any private OpenXross Plugin/System Plugin compiled against earlier snapshots.

## Why this is breaking now

Maven Central namespaces can be backed by a domain in reverse-DNS form. The project owner controls `ztraqto.com`, therefore `com.ztraqto` is the stable namespace chosen before the first public Maven release. Changing Java packages after public adoption would be substantially more disruptive.

## Persisted data

The brand/package rename does not intentionally rename Xross database collections or Xross Orchestrator persisted keys. Existing 1.2.x cluster state should be migrated by application-level testing rather than by rewriting persistence identifiers solely for branding.
