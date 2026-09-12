# System Plugins

Place privileged OpenXross system-plugin JARs in this directory.

System plugins use `system-plugin.json` and extend `XrossSystemPlugin`. They are
loaded before internal services and Discord, are not hot-reloaded, and require
a process restart after replacement.

For production, enable `systemPlugins.requireTrustedFingerprints` and add the
SHA-256 of every trusted JAR to `trusted.sha256` (one fingerprint per line).

The OpenXross Cloudflare D1 Layer is distributed separately and belongs here
as a System Plugin rather than being embedded into the OpenXrossEngine core.
