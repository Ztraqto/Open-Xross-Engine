# OpenXrossEngine 1.4.1

Security and public-release hardening patch for the 1.4 line.

## Security

- Bound Remote XrossDB HTTP responses to 2 MiB and stream them instead of buffering an unbounded body.
- Require HTTPS for non-loopback Remote XrossDB clients; direct non-loopback XrossDB HTTP server binding is denied by default.
- Add `*_FILE` secret sources for Discord token, XrossDB token, PostgreSQL passwords and Editor signing secret; secret CLI options remain compatibility-only and emit a deprecation warning.
- Centralize Jackson `StreamReadConstraints` and strict duplicate-key detection for untrusted/configuration JSON.
- Replace plugin approval SHA-prefix routing with opaque random approval request IDs; the full SHA-256 remains the trust decision.
- Disable bulk plugin approval in Discord UI and the public manager method.
- Add response-size bounds to Discord Get Gateway Bot calls.
- Validate encrypted Editor PNG dimensions before `ImageIO` decoding.
- Sanitize remote XrossDB error messages before surfacing them.

## Removed

- Remove DBP (Discord Bot Protocol) completely: `DBPService`, DBP packet/security/replay classes, DBP configuration, runtime dispatch and DBP tests are deleted.

## Compatibility

- Maven coordinate remains `com.ztraqto.openxross:openxross-engine:1.4.1`.
- Xross Orchestrator/XrossDB storage contracts remain compatible with the 1.4 line.
- Existing applications that imported DBP APIs must migrate before updating.

## Release note

Approved plugins still execute inside the Bot JVM and are not OS/JVM sandboxed. Treat plugin approval as executing trusted third-party Java code.
