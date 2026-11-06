# Codex Cloud / ChatGPT Work: THREE IB project instructions

This file is authoritative for future AI coding sessions in this repository. Read it together with `docs/WORK_HANDOFF_FA.md` before editing. Do not ask repetitive setup questions: inspect the code and make safe, incremental changes, build and verify each result.

## Source of truth
- Default branch: `main`. The proven Android SDK build is `.github/workflows/build-apk.yml`; the three-channel baseline is MessengerQueueSkip v2.7.0.
- The separately named `.github/workflows/three-ib-apk.yml` was fixed on 2026-11-06 and compiles the **same three-channel baseline**.
- Legacy branch `three-ib-apk-v1` contains an independent experimental `android-app/` implementation with SQLite and explicit row-level opt-in. You may borrow tested approaches, but it is not the current release.
- Do **not** describe existing Telegram/WhatsApp manifest package queries as actual integrations. `QueueDb.APPS` currently contains only Bale/Rubika/Eitaa.

## Goal: one honestly verified six-channel Android app
1. Bale, Rubika, Eitaa: user-triggered Android Accessibility integration, with a no-send dry run on the installed versions and verified recipient matching.
2. Telegram: **real TDLib / personal account** login with number, OTP and 2FA as required. Do not claim that API_ID/API_HASH alone logs in. Build official native/Java artifacts using reproducible pinned dependencies. Some imported numbers cannot be resolved to Telegram users.
3. SMS from user's installed SIM: Android SmsManager with chosen subscriptionId, SEND_SMS permission, multipart SMS, sent PendingIntent callbacks, independently rate-limited queue, fail-safe duplicate protection, explicit estimated carrier charges. Ordinary SMS is text-only.
4. WhatsApp: official **WhatsApp Business Platform Cloud API**, based on authorized business account and recipient opt-in; approved template when required. Store business access token on an authenticated server, NOT in an APK. Personal WhatsApp has no equivalent officially supported bulk-message API.
5. Excel/CSV: parse and normalize phone numbers carefully; reject duplicates and invalid rows. For bulk sending, require **per-row opt_in** and an explicit user confirmation. Do not assume all numbers in Excel consented.
6. Central persisted queue: independently enabled channels, delay 1..1440 minutes, per-channel text/media support and settings, START AUTOMATIC, pause/resume/stop/clear, logs, permission checks, sensible reboot/foreground behavior, and statuses PENDING/IN_FLIGHT/SUBMITTED/DELIVERED/FAILED/REVIEW. Uncertain attempts must not be blindly retried.

## Nonnegotiable release gates
- `START AUTOMATIC` must not silently broadcast to arbitrary imported contacts; require consent and supported channel readiness.
- Platform quirks, account anti-spam limits and Android background restrictions cannot be guaranteed away. A local UI send indicator is NOT proof of server delivery.
- Before claiming a channel works, test that channel on a real device/account with a consenting test recipient; report actual attempts, accepted/delivered receipts, failures and phone/OS/app versions. No invented 99% guarantees.
- Build an installable signed APK through GitHub Actions, verify `apksigner` and checksum, and publish the actual file plus clean source ZIP. Debug signatures are for testing only; use a persistent keystore as a secure CI secret for release signing.
- Use least-privilege permissions. Avoid unbounded wake locks or infinite loops. Respect WhatsApp template/opt-in rules and Telegram policies.

## Secrets & publication
- NEVER commit or upload Telegram API_HASH/API_ID, login codes, TDLib session database, phone lists, WhatsApp tokens, release signing keys, personal numbers or private Drive docs to public repositories/folders.
- Credentials previously pasted into a chat must NOT be copied from chat to source. Ask the platform for secure secret input when needed; otherwise mark the channel as configuration pending.
- The Google Drive handoff folder is `Theree iba`, ID `1uBZVIuMbOlKia7GHVxgoV8IXtSq4XvBx`. It is currently PRIVATE. Only public-safe build outputs and sanitized documents may be uploaded there.
- If anyone-with-link Editor permission cannot be technically granted, say so; do not claim it was granted. If it can, warn that editors can replace files. Prefer public Reader for distributables and limited Editor for source contributors.
- Complete source and APK should be linked in `docs/WORK_HANDOFF_FA.md` with dated verified statuses.

## Technical reference
- Official TDLib documentation: https://core.telegram.org/tdlib
- Android SmsManager: https://developer.android.com/reference/android/telephony/SmsManager
- WhatsApp Business Platform: https://developers.facebook.com/documentation/business-messaging/whatsapp/about-the-platform

## Execution priority
1. Audit current working baseline and experimental branch; establish reproducible CI and tests.
2. Implement and validate real SMS SIM integration.
3. Implement TDLib login/send/receipt handling with runtime secrets and secure storage.
4. Implement WhatsApp Business Cloud API via secure backend only when authorized configuration exists.
5. Refactor three UI-driven channel adapters, preserve their reliability gates; add robust consent and delivery reporting.
6. Produce a single APK and source ZIP, with an honest channel-by-channel verification matrix.

The project is finished ONLY when all six channels have verifiable tests on real authorized accounts and the user can install the produced APK; otherwise clearly label the remaining missing dependencies.
