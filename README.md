# THREE IB — Android Queue Controller

**Version:** 2.9.1  
**Version code:** 17  
**Snapshot date:** 2026-11-06  
**Application ID:** `com.sinicable.messengerqueue`

This build contains four implemented delivery paths. It is a consent-gated Android queue controller, not a six-messenger release.

## Implemented in this build

- **Bale, Rubika and Eitaa:** separate persistent queues driven through each installed messenger's visible UI with Android Accessibility.
- **SMS SIM:** a separate text-only queue using Android `SmsManager` and an explicitly selected active SIM subscription.
- **Recipient import:** `.xlsx` and CSV files with required `phone` and `opt_in` headers. Optional columns are `opt_in_source`, `send_sms` and `message`.
- **Consent enforcement:** each row is blocked unless `opt_in=true`. SMS additionally requires `send_sms=true`, an enabled SMS queue, an explicitly selected SIM, and Android permissions.
- **Scheduling:** independent channel delays from 1 to 1440 minutes.
- **Queue controls:** START AUTOMATIC, Pause/Resume, Stop and Clear.
- **Message content:** text and optional image for the three messenger UI paths; SMS is text-only.
- **Recovery behavior:** interrupted or uncertain sends are moved to REVIEW rather than blindly retried.

## Not included in this build

- **Telegram TDLib:** not integrated. Telegram package visibility in the manifest is not an implementation.
- **WhatsApp Business Cloud API:** not integrated. The official Cloud API is a business-platform integration that needs Meta business assets and credentials; it is not equivalent to sending from a personal WhatsApp app.
- Therefore this artifact must not be described as a completed six-channel THREE IB release.

## Important operational limits

- The three messenger queues depend on the installed apps' current UI, sign-in state, Accessibility permission, network, and Android power-management behavior.
- A local `SUBMITTED` state is evidence that the app observed the messenger's send UI action. It is not a server delivery or read receipt.
- SMS delivery callbacks depend on Android, the SIM, carrier and destination network. Carrier charges may apply.
- Android/device policy may restrict direct SMS permission for sideloaded applications. Permission prompts alone do not guarantee carrier delivery.
- The app cannot guarantee uninterrupted background execution on every Android/OEM build.

## Build and verification record

- The release workflow compiles the Java sources, packages the APK, verifies the APK signature, and reads package metadata.
- **No automated tests, manual tests, device tests, messenger-send tests, SIM tests or carrier-delivery tests are run for this snapshot**, as requested.
- A successful compile/package/signature check is not proof of runtime behavior or message delivery.

## Signing and installation

The current workflow generates a temporary debug signing key for each build. The APK is installable as a sideloaded package, but a build signed with a different key cannot update an existing installation of the same package. Do not uninstall an existing installation unless its local queue data has been backed up and you accept the data-loss risk.

The package ID remains `com.sinicable.messengerqueue`. Minimum Android SDK is 26; target SDK is 35.

## Dated artifacts

The build workflow creates a dated GitHub pre-release with three separate downloadable assets:

- `THREE-IB-LATEST-YYYY-MM-DD-v2.9.1.apk`
- `THREE-IB-SOURCE-LATEST-YYYY-MM-DD-v2.9.1.zip`
- `SHA256SUMS.txt` for both files.

The release path is used instead of GitHub Actions artifact storage.
