# THREE IB v4.0.1 — Personal-login → Excel → Queue → START acceptance gates

Baseline: `e1745095425ce3f35e6442abc3852e82e90f246c`. These are acceptance criteria, not claims of live-tested behavior.

## Per-provider acceptance
1. **Bale, Rubika, Eitaa** (unofficial GoOmni): User types personal-account phone inside the APK; requests OTP, enters OTP and optional two-step password inside that same APK; account status must report both `authenticated` and `connected` before START is enabled. Verify with operator-owned test accounts. Do not reuse Telegram credentials or publish OTP, session keys, API_HASH, or phone numbers.
2. **Telegram** (TDLib): Configure user-supplied API ID/hash securely; phone, code, email challenge when required, and 2FA; only `AuthorizationStateReady` allows START. An unresolvable contact is skipped with persisted progress; a send with uncertain outcome still requires REVIEW, never automatic retry.
3. **Excel**: Import .xlsx or .csv with headers `phone,opt_in`; SMS also requires `send_sms`. Deduplicate and preserve per-row consent; block rows with missing or false opt-in.
4. **START**: Require actual logged-in account, consent and message/image, at least one eligible recipient; queue attempts must respect user interval 1–1440 min and pause/clear actions.
5. **Reporting**: Distinguish `submitted` (server accepted), `failed`, and `review` (uncertain). Never say 'delivered' based only on HTTP acceptance. Avoid duplicate sends on restart.
6. **On-device test**: For each provider independently, verify OTP delivery and successful login, app relaunch/session recovery, one authorized contact receiving text and image, 2-row queue progression, pause/resume, and network interruption behavior.

## Outstanding blockers
- Real successful personal login and test-message acceptance for Bale/Rubika/Eitaa are **not verified on-device**. GoOmni is unofficial; capability differs across provider adapters.
- Telegram TDLib tests require a real authorized account and recipients. Not every phone resolves to a Telegram user.
- Android 12+ foreground-service start restrictions and device battery policy can interrupt background automation. No guarantee of infinite service lifetime.
- This work does not change WhatsApp Cloud-only architecture; WhatsApp is not a personal WhatsApp login.
- This checklist is not a successful end-to-end test report.

## External references
- https://core.telegram.org/tdlib/getting-started
- https://github.com/mimalef70/goomni
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
