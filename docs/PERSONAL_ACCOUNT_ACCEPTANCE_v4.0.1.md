# THREE IB personal-account acceptance protocol (v4.0.1+)

Scope: **four separate APKs**, Bale, Rubika, Eitaa and Telegram. No bot accounts, no Accessibility screen playback, no handoff to the installed messaging application.

## Actual user journey

1. Install the corresponding signed APK and open it.
2. Enter the operator's personal account phone number **inside the APK**.
3. Request a login code, supply the code in-app and complete 2FA/email confirmation when the provider requests it.
4. Confirm authenticated/connected state before enabling START. A screen existing is not proof that provider login succeeded.
5. Import `.xlsx` or `.csv` containing `phone,opt_in` headers; `opt_in=true` is required for each authorized recipient. The parser must reject missing headers and ignore duplicates and invalid numbers.
6. Enter text and/or choose a supported image. Set the per-app interval from 1 to 1440 minutes.
7. Press START AUTOMATIC. Observe foreground notification and the queue states. Test PAUSE, app background, screen-off, network loss and process restart.
8. Record one genuine server-accepted result from a recipient controlled by the operator. Confirm ambiguous/failed submissions are **not** resent without review.

## Evidence matrix

| Channel | In-app phone/OTP/2FA code | Build/signature | Live in-app OTP verified | Actual personal-account send verified |
| --- | --- | --- | --- | --- |
| Bale (unofficial GoOmni) | Implemented | v4.0.1 signed | Not verified | Not verified |
| Rubika (unofficial GoOmni) | Implemented | v4.0.1 signed | Not verified | Not verified |
| Eitaa (unofficial GoOmni) | Implemented | v4.0.1 signed | Not verified | Not verified |
| Telegram (official TDLib) | Implemented | v4.0.1 signed | Not verified | Not verified |

### Queue failure behavior

- Never retry an unknown in-flight request without checking delivery outcome.
- A Telegram imported phone unresolved by `ImportContacts` must not be treated as a delivered message. The queue now records progress past the unsendable row and waits the configured interval before the next eligible recipient.
- Provider errors, authorization expiry, background execution restrictions and offline conditions can still stop sending. This project must not promise an immortal queue or universal delivery rates.
- Phone numbers, OTP, password, API_HASH, session credentials and message content must not be pasted into GitHub issues or CI logs.

Sources: https://core.telegram.org/tdlib/getting-started ; https://github.com/mimalef70/goomni ; https://developer.android.com/training/monitoring-device-state/doze-standby

Tracked by #26. This checklist is **not** evidence of passing real-world tests.
