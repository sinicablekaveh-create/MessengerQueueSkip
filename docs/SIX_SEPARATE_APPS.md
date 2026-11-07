# THREE IB v4.0.1 — Six separate Android applications

This branch builds six independently installable APKs from one source tree.

| Flavor | Package | Dedicated entry point |
|---|---|---|
| Bale | com.sinicable.threeib.bale | In-app personal-account phone/OTP/2FA |
| Rubika | com.sinicable.threeib.rubika | In-app personal-account phone/OTP/2FA |
| Eitaa | com.sinicable.threeib.eitaa | In-app personal-account phone/OTP/2FA |
| Telegram | com.sinicable.threeib.telegram | TDLib login and queue |
| SMS | com.sinicable.threeib.sms | SIM selection and SmsManager queue |
| WhatsApp Business | com.sinicable.threeib.whatsapp | Official Cloud API backend queue |

All builds require row-level recipient consent. SMS also requires send_sms=true.
The three Iranian personal-account builds use the bundled unofficial local GoOmni gateway and do not open installed messenger applications or use Accessibility.

The CI workflow creates six APKs, six separately named source ZIPs, six build-info files and SHA256SUMS.txt.

## v4.0.1 stability and permission boundaries

- Fixed the Rubika/Eitaa dedicated login screen initialization: the selected account, saved phone and per-channel delay now match the APK flavor on first launch.
- UI version labels read BuildConfig.VERSION_NAME to avoid stale release text.
- SEND_SMS and READ_PHONE_STATE are requested by the SMS flavor only; the other five APKs do not declare them.
- Cleartext HTTP is enabled only for Bale, Rubika and Eitaa, for their embedded loopback gateway. Telegram, SMS and WhatsApp flavors do not enable cleartext.
- CI validates each APK's package, launcher, version, signature and SMS permission boundary.
- These are debug-signed arm64-v8a preview builds. APK compilation/signature validation does not establish device installation, OTP login or end-to-end delivery. WhatsApp requires an authorized Business Cloud API backend.
