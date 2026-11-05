# THREE IB Telegram v3.1.0

Personal Android Telegram queue using TDLib, plus inherited Bale, Rubika and Eitaa UI queues.

### v3.1.0 fixes
- Restores each Telegram queue delay from persistent storage after app recreation.
- Handles Telegram send-success and send-failure notifications arriving before the SendMessage callback.
- Stops with a visible error if Android refuses the foreground queue service.
- Shows the saved Telegram delay instead of resetting the input to 5 minutes.

### Rebuilding
Use the app's private `telegram-config.json` containing your personal api_id and api_hash (only in a local working copy), plus Android SDK 35 and the documented TDLib AAR. The provided source deliberately replaces secrets with placeholders.
The release APK is already signed with the private key retained by the owner. The signing keystore is intentionally excluded from this repository. A locally rebuilt APK signed with a different key cannot be installed as an update.

### Status
Java compilation, DEX, ZIP integrity, Android package inspection, APK v2/v3 signature verified. Real account login and end-to-end message delivery on Android devices still require testing. Android Accessibility integration for other messengers remains sensitive to UI updates. Use only with opt-in recipients. The queue stops rather than guessing about uncertain sends and does not circumvent rate limits.

### Android CPU architectures
arm64-v8a and armeabi-v7a. Android 8+.
