# THREE IB | Six standalone Android APKs

Version: **5.0.0** | versionCode: **28** | Android: **ARM64 / minSdk 26**

Download the six APKs, six source ZIP archives, SHA256SUMS, and channel-specific build notes from [GitHub Releases](https://github.com/sinicablekaveh-create/MessengerQueueSkip/releases).

## Independently installable programs

| Program | Android package ID | Account/transport |
| --- | --- | --- |
| Bale | com.sinicable.threeib.bale | In-app personal phone, OTP, optional 2FA via unofficial bundled gateway |
| Rubika | com.sinicable.threeib.rubika | In-app personal phone, OTP, optional 2FA via unofficial bundled gateway |
| Eitaa | com.sinicable.threeib.eitaa | In-app personal phone, OTP, optional 2FA via unofficial bundled gateway |
| Telegram | com.sinicable.threeib.telegram | TDLib with interactive API ID/hash, phone, OTP and 2FA |
| SMS SIM | com.sinicable.threeib.sms | Selected physical SIM; Android SmsManager; text only |
| WhatsApp Business | com.sinicable.threeib.whatsapp | Authorized WhatsApp Business Cloud API backend; not personal WhatsApp |

Every APK has an independent Android package, launcher and sandbox. The six source ZIPs contain the shared source code and an included TARGET_CHANNEL.txt flavor selector; they are not six separate codebases.

## Intended user flow

Import an XLSX/CSV recipient list. Name/phone two-column files and files with explicit opt_in fields are supported. You must have recipient consent and confirm it before starting a queue. Manage unsent contacts, set a per-channel delay from 1 to 1440 minutes where supported, type a message, and use START AUTOMATIC. Uncertain attempts are moved to REVIEW rather than automatically sent again.

Bale, Rubika and Eitaa perform attempted login inside their own APKs, not by opening installed messenger apps. Their embedded gateway is an **unofficial implementation** and may not be accepted by the provider. It uses distinct loopback ports so all three APKs can be installed together. Telegram cannot necessarily resolve every imported phone number. SMS can incur carrier charges and needs Android permission. WhatsApp Business requires an authorized HTTPS Cloud API backend, valid permissions and applicable recipient/template rules. In v5 its user-started queue runs in a foreground Service after the screen closes, but Android process termination stops it safely; users must restart with a token. The token stays only in RAM, not local preferences.

## Verified versus not verified

**Verified by GitHub Actions:** Compilation of all six flavor APKs; APK signature and package ID checks; launchable activity and permission boundaries; unit parser check; packaging of six source ZIPs and SHA-256 checksums.

**Not verified:** Installation on the user's particular phone, actual OTP / account login on live Bale/Rubika/Eitaa/Telegram, successful message delivery, reliable long-term unattended background sending, or the real WhatsApp Business backend credentials. Foreground service implementation is not proof of long-term Android survival. Build success is not evidence of actual delivery.

**Important:** Published APKs are DEBUG-SIGNED previews, not production-signed releases. CI signing keys may differ between builds, meaning upgrading can require uninstall and will lose app data. A persistent secure release signing certificate is required for durable upgrades.

Review [the six-app technical specification](docs/SIX_SEPARATE_APPS.md), [CI workflow](.github/workflows/build-apk.yml), and [Releases](https://github.com/sinicablekaveh-create/MessengerQueueSkip/releases). Never commit API secrets, session files, login codes or contact spreadsheets.
