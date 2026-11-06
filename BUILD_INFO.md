# THREE IB — Build snapshot

- Snapshot date: 2026-11-06
- Version: 2.9.1
- Version code: 17
- Package: `com.sinicable.messengerqueue`
- Implemented paths in this snapshot: Bale, Rubika, Eitaa and SMS SIM.
- Telegram TDLib and WhatsApp Business Cloud API are not implemented in this snapshot.
- The build workflow compiles and packages the APK, verifies its debug signature, and records the package metadata.
- No automated tests, manual tests, device tests, messenger-send tests, SIM tests or carrier-delivery tests are run for this snapshot, per user instruction.
- The build uses a temporary debug signing key per run. It is not a stable release-signing identity.
