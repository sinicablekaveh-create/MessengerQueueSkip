# THREE IB 5.0.0 (six independent APKs)

Release implementation date: 2026-11-07
Android package version: 5.0.0 (versionCode 28)

## Six apps
Bale, Rubika, Eitaa, Telegram, SIM SMS and WhatsApp Business remain separate Android package IDs, each with a different installable APK and a source ZIP containing the shared repository plus a channel selector.

## Concrete v5 improvement
WhatsApp Business queue is no longer Activity-scoped. After an explicit START with HTTPS backend, runtime service credential, message, 1-1440 minute delay and verified recipient-consent attestation, a visible foreground service handles the queue and exposes PAUSE in its Android notification. Merely closing its screen does not cancel queued work.

The backend token is never written to local preferences or a source file. If Android kills the process, the service does **not** restart without a token. In-flight sends become REVIEW at the next app open. The queue never silently replays uncertain requests. A late HTTP response cannot overwrite a review/cleared attempt: a compare-and-set only completes an IN_FLIGHT attempt. This does not replace server-side idempotency and delivery receipts.

The auxiliary source handoff CI job now verifies its ZIP rather than uploading to quota-exhausted GitHub Actions artifact storage. The main build workflow already publishes six actual APK and six source ZIP release assets.

## Release gates and limitations
- GitHub Actions must finish the Android assemble, package-ID checks and debug-signature validation on all six flavors, and publish APKs, source ZIPs and SHA256 hashes. This is a **preview/debug** build, not release-key production signing.
- No physical-device live login (Bale, Rubika, Eitaa or Telegram), SMS receipt, or WhatsApp backend delivery has been verified in this release by CI.
- Bale, Rubika and Eitaa still use an unofficial embedded provider implementation and can be rejected by the service.
- Telegram relies on a working TDLib integration, user's own secure API configuration, account sign-in and recipient discoverability.
- SMS may incur charges and requires permissions/active SIM.
- WhatsApp Business requires an authorized API backend, opted-in recipients and Meta messaging policy compliance. Approved outbound templates are **not** implemented. WhatsApp personal-account automated messaging is not supported.
- No 99%/100% delivery or survival guarantee is justified without live test receipts.
