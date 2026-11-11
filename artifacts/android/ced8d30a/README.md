# CED8D30A development candidate

These are the five frozen, development-signed APKs built on 2026-11-10 18:53 UTC. All five v3 signatures and SHA-256 hashes were rechecked for publication. **Not installed or runtime-tested as this candidate; not a production release.** F134 emulator passes do not qualify these packages. Real microphone capture remains gated.

- `sidecar-dev.apk`: development application.
- `sidecar-tests.apk`: storage/native instrumentation tests, not an ordinary app.
- `sidecar-fixture.apk`: separate synthetic input fixture, not real recording.
- `sidecar-fixture-tests.apk`: fixture/UI/control instrumentation.
- `crash-tests.apk`: controlled process-termination tests.

Do not use real private material. The fixture and crash packages are for isolated synthetic test environments. Installation or running tests was not performed for this upload. The signing private key is not published; a local rebuild generates its own development identity and is not expected to be byte-identical or update-compatible.

`SOURCE_INPUTS.json` binds 76 source inputs relative to the repository `android/` directory. Every published input matches that manifest. `BUILD_STATUS.json` is a sanitized summary, not the raw receipt. `SHA256SUMS` identifies exact package bytes.

Certificate SHA-256: `a00db9f6e4cc85b406bd39d349f3e4be80b1bf97a08075f5d0ab33142e18343f` (CN=Sidecar Local Development). No signing private key is included.
