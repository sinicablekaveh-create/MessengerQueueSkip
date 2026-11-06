# THREE IB — Messenger Queue Skip v2.8.0

Android queue controller for user-approved recipients in Bale, Rubika and Eitaa. It drives the visible UI of the installed messenger apps through an Accessibility service; it does not use unofficial messaging APIs and it does not claim server delivery.

## Current behavior

- Imports Iranian mobile numbers from column A of `.xlsx` files or the first CSV column.
- Maintains independent, persistent Bale, Rubika and Eitaa queue states with 1–1440 minute minimum delays.
- Requires a user confirmation that recipients agreed to receive the message.
- Runs a no-send reliability check before releasing a full queue.
- Verifies the visible recipient, exact text composer and Send control before acting.
- Marks ambiguous or interrupted attempts for review instead of retrying blindly.
- Supports an optional image through Android's standard share flow.
- Keeps a user-started foreground watchdog with pause and stop controls.
- Optionally creates uniquely tagged phone contacts when the user enables that setting and grants Contacts access.

## v2.8 privacy and build hardening

- Accessibility events are limited to the three supported messenger packages.
- Full recipient phone numbers are removed from the ongoing notification and redacted from stored/copyable logs.
- Contact alias creation is off for new installations and remains optional.
- Denied permissions no longer cause an automatic prompt loop.
- The screen-awake preference now controls the Activity window flag.
- Pull requests compile, package and verify the APK; the obsolete v1 `android-app` workflow was removed.
- CI reads the version from `app/build.gradle.kts`, so manifest and artifact versions cannot silently drift.

The supplied SmsForwarder archive was reviewed as an architecture reference. No SmsForwarder code, SMS receiver or SMS permission is included in this release. Adding SMS ingestion would require a separate opt-in privacy design because SMS content can contain authentication codes and private messages.

## Installation and use

1. Download the APK from the latest successful GitHub Actions artifact.
2. Install and sign in to Bale, Rubika and Eitaa directly.
3. Import a consented `.xlsx` or CSV recipient list.
4. Enter the exact message and enable only the desired queues.
5. Enable the app's Accessibility service after reviewing Android's warning.
6. Use **TEST ONE QUEUE TASK** before **START AUTOMATIC**.
7. Keep the phone unlocked while UI automation is active and inspect every `REVIEW` result before retrying.

Android may show **Restricted settings** for Accessibility on sideloaded APKs. Battery restrictions, Force stop, messenger UI changes and device lock state can pause or prevent the queue.

## Privacy and reliability limits

- Recipient data and queue state stay in this app's private local storage.
- Optional contact aliases modify the device address book only after explicit permission.
- Accessibility can read the visible screens of the three supported messenger packages while enabled.
- `SUBMITTED` means local UI evidence was observed. It is not a server receipt, delivery receipt or read receipt.
- A GitHub Actions artifact is signed with a temporary debug key unless a stable release-signing workflow is configured. A differently signed build cannot upgrade an installed APK without migration planning; uninstalling would erase local app data.
- The app has not been validated against every current Bale, Rubika and Eitaa UI version or on every Android/OEM battery policy.

Package ID remains `com.sinicable.messengerqueue` so existing local data and upgrade identity are not intentionally replaced.
