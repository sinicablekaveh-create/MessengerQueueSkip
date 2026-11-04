# Messenger Queue Skip v1.2.0

A consent-based Android queue controller for Bale, Rubika and Eitaa. Uses device-installed apps and the Android Accessibility UI, not unsupported direct messaging APIs.

## Improvements
- Search by mobile number or an optional, uniquely tagged MQS phone contact after explicit Contacts permission.
- Accept chat candidates only when the phone suffix or known tagged alias is visible.
- A new **TEST ONE CONTACT** action allows a single recipient trial before starting the whole queue.
- Queue scheduling now guards against repeated UI events and preserves delay across Pause/Resume.
- After tapping Send, waits to see the composer cleared AND a new matching outgoing message node. Status "UI confirmed" means this local UI condition was observed; **it is not delivery confirmation**.
- Uncertain sends are marked REVIEW and stop the queue, preventing automatic duplicate retries or fallback to another messenger.
- Copyable diagnostic logs, 1–1440-minute delays, optional fallback and Skip, imported contact deduplication.

## Installation and testing
Download the APK from GitHub Actions > latest successful run > Artifacts. For an APK installed outside the app store, Android may initially block Accessibility with Restricted settings. Only enable this sensitive permission if you trust the app and its source.
Import an .xlsx file with consented mobile numbers in column A or a CSV first column. Enable Accessibility, select Bale/Rubika/Eitaa and your message. Use TEST ONE CONTACT before START AUTOMATIC. Keep the phone unlocked and installed messenger logged in.

## Limits
- This release **has been compiled and signed locally** using Android SDK Tools 35, but has not been tested on the user's Android phone or with logged-in messenger accounts.
- Search and send UI can change without warning. There is no official verified phone-number deep link for these three messenger apps.
- UI-confirmed is not a server read/delivery receipt; only the messenger itself can report those.
- Contact alias creation is off by default and creates entries in your phone's address book. Use only with recipients who consented.
- The GitHub Actions debug signer may differ from previous versions. Android may require uninstalling an older debug APK to install this one; uninstalling loses local queue data.
