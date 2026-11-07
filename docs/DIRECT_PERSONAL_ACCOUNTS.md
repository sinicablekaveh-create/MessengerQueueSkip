# THREE IB Personal v3.3.0 • Direct native account gateway

This is a **separate stacked follow-up** to PR #9. It does not rewrite PR #8 or PR #9.

## Architecture
- Android launcher is `DirectAccountActivity`, with phone + OTP + optional 2FA for Bale/Rubika/Eitaa. No Accessibility service, app launching, UI recording or screen control is registered.
- The GoOmni v2.4.1 pure-Go ARM64 gateway is compiled for Android and bundled as `jniLibs/arm64-v8a/libgoomni.so`. The `EmbeddedGatewayService` initializes private account storage and starts a local REST listener on **127.0.0.1:38741**, without any exposed public listener.
- The gateway is a third-party **unofficial** account client (https://github.com/mimalef70/goomni, upstream tag v2.4.1, commit a49bd70). Its compatibility can change or cause account restrictions; review its SECURITY.md before using valuable accounts.
- The Android app reads the generated local Basic credential from its own private app directory. No account token, password, OTP, app admin credential or encryption key is embedded in GitHub source/CI APK.
- Excel/CSV `phone,opt_in` contacts and optional messages use persistent QueueDb. Sending occurs in a separate foreground service, with one stable idempotency key per logical send and independent 1–1440 minute intervals. Unknown/interrupted outcomes become REVIEW, not blind retries.
- Only explicit opt_in recipients may receive messages. The checkbox confirms consent; it does not independently verify evidence.
- Optional JPEG/PNG image (up to 6 MB) sends via GoOmni's guarded multipart image endpoint.
- Normal Android battery/background constraints and provider restrictions apply. No claim is made that 24/7 sending is guaranteed.
- `submitted` means accepted by the gateway/provider, not proven delivery.

## Limitations and verification
- The GoOmni binary compiles and its localhost `/health` and provider discovery were tested in Termux. This is **not** an installed-APK runtime test.
- Actual OTP login, SMS delivery, sending and receipt with a real Bale/Rubika/Eitaa personal account **have not been tested**.
- A live physical-device install is needed to verify Android 16 execution of the embedded native binary; Github Actions APK compilation alone cannot guarantee this.
- Supports ARM64-v8a only, requires Android API 26+.
- Android debug signing identities are not stable between CI runs; if an earlier debug build uses a different key, it must be uninstalled first (which removes local app data).
- Restart after reboot and unrestricted background operation remain platform-dependent; foreground notification should remain visible.
- A non-official client can be blocked or modified by providers without advance notice.

## Data protection
- The process binds to loopback only and requires an app-private generated Basic credential.
- Android app data uses `allowBackup=false`; GoOmni encrypts session tokens and maintains SQLite. Be aware that message bodies stored in the gateway's database are not app-level encrypted.
- Do not export private app data, OTPs, `.env`, master keys or session files.
- Always review and revoke unsupported provider integrations if your account's security is paramount.
