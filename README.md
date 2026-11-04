# Messenger Queue Skip v1.1.0

A consent-based Android queue controller for **Bale, Rubika and Eitaa**. It uses Android Accessibility to inspect the foreground messenger interface and attempt interactions. This is NOT an official integration.

## What changed in 1.1
- Three-app focus: Bale, Rubika and Eitaa
- Phone-number-first search and optional unique `MQS_<phone>` contact aliases
- Explicit Android Contacts permission for the optional contact feature (off by default)
- Search and new-chat detection, a bounded 60-second attempt and fallback messenger
- Safe chat selection: only results with the target phone suffix or its created unique alias
- Send button must be near the message input, and message text must match before sending
- Pause/resume, skip, 1-1440-minute delay, per-contact log

## Important limitations
- A signed, installable APK exists, but **sending has not been tested on the user's phone**.
- Messaging app UIs are subject to change; contact sync may take time or fail.
- A SUBMITTED status means the send UI action was attempted; it does not confirm server delivery.
- The screen should remain unlocked and the messenger account should be logged in.
- Contacts added by the optional setting remain in the phone address book unless removed by the user.
- Do not import or send to people who have not agreed to receive the messages.

## Installation
Download [MessengerQueueSkip-v1.1.0.apk](releases/MessengerQueueSkip-v1.1.0.apk), install, enable the Accessibility service, import .xlsx or .csv with numbers in column A, select a messenger and message, optionally enable tagged contacts, confirm recipients' consent and select START AUTOMATIC.

## Source build
Gradle sources remain available under `app/` and `.github/workflows/`. Release APK here was independently compiled using Android SDK Build Tools 35 and javac 17 and verified with apksigner.