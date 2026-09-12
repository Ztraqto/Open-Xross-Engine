# Publishing OpenXrossEngine to Maven Central

Target coordinate:

```text
com.ztraqto.openxross:openxross-engine:1.4.1
```

Ztraqto controls `ztraqto.com`, so request the Maven Central namespace `com.ztraqto` and verify ownership using the TXT record provided by Central Publisher Portal.

## Before publishing

1. Create/verify the `com.ztraqto` namespace in Central Publisher Portal.
2. Confirm the final Git repository URL and update the `scm` block in `build.gradle` if it differs from the placeholder `github.com/ztraqto/OpenXrossEngine`.
3. Run `release.bat` on a networked Java 17+ Windows machine.
4. Confirm unit tests, Javadoc and sources JARs pass.
5. Scan the full Git history for credentials.
6. Generate/review the exact dependency license report.
7. Configure a GPG/PGP signing key and Central Portal publishing workflow.

Maven Central expects the primary JAR plus sources and Javadoc artifacts, required POM metadata and signatures. Do not reuse a released version: Central artifacts are immutable after publication.

## Consumer usage

```gradle
repositories { mavenCentral() }

dependencies {
    implementation("com.ztraqto.openxross:openxross-engine:1.4.1")
}
```
