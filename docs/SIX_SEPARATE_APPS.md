# THREE IB 4.0.3 | Six independent APK flavors

Six separate Android applications are compiled from one source code tree. Each has its own app ID, launcher, Android-private storage, and recipient/queue interface. The six named source ZIPs each include the shared sources and a TARGET_CHANNEL.txt build selector.

| App | Package ID | Launcher |
| --- | --- | --- |
| Bale | com.sinicable.threeib.bale | In-app personal account login |
| Rubika | com.sinicable.threeib.rubika | In-app personal account login |
| Eitaa | com.sinicable.threeib.eitaa | In-app personal account login |
| Telegram | com.sinicable.threeib.telegram | TDLib personal login + queue |
| SMS | com.sinicable.threeib.sms | SIM selection + SMS queue |
| WhatsApp Business | com.sinicable.threeib.whatsapp | Authorized Cloud API backend queue |

## Isolation and safety

Bale, Rubika and Eitaa use bundled **unofficial** GoOmni inside their APKs without opening installed messenger applications. Their localhost gateway ports are Bale 38741, Rubika 38742, Eitaa 38743 to avoid conflicts. Live account authentication and provider delivery are unverified.

Recipient imports accept XLSX/CSV using name+phone or phone+opt_in; an imported phone number does not prove recipient consent. START requests explicit confirmation. The recipient editor allows manual updates to unsent rows without erasing the audit states of submitted or uncertain sends. Uncertain attempts require REVIEW to prevent blind retry. Telegram has a dedicated pending recipients editor.

SMS uses text-only messaging and can incur carrier charges. Only SMS flavor requests SEND_SMS and READ_PHONE_STATE; only Bale/Rubika/Eitaa allow local cleartext HTTP. WhatsApp Business is not a personal WhatsApp integration, requires an authorized Meta Cloud API HTTPS backend, and currently stops its activity-scoped queue when the screen closes.

## CI / deliverables

On 2026-11-07, run 42384192641 for SHA 01f3082b33c583091078948ff7d0ccf62cf134f2 completed successfully and published six independently installable preview APKs, six source ZIPs, channel build-info text files, and SHA256SUMS to the PR #32 GitHub release.

After main integration, the same workflow is configured to build on main pushes and publish a separate main-branch GitHub Release.

**Release status: preview, debug-signed, ARM64, compilation verified only.** No live device account OTP, recipient delivery, background survivability or WhatsApp backend authorization was proven. These must not be represented as tested or production-ready features. CI build tests do not send real messages.
