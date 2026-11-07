# THREE IB v4.0.0 — Six separate Android applications

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
