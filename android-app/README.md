# THREE IB Android v1.0

Native Android queue app for user-approved contacts in Bale, Eitaa and Rubika.

## Core behavior
- Imports `.xlsx` rows with `phone`, `opt_in`, `name`, `message`, `send_bale`, `send_eitaa`, `send_rubika`.
- Only queues rows with explicit truthy `opt_in`.
- Stores queue state in SQLite with `PENDING`, `SENDING`, `SENT`, `FAILED`, `UNKNOWN`.
- Uses a user-started foreground `specialUse` service, `START_STICKY`, a watchdog alarm and `BOOT_COMPLETED` recovery.
- Uses Android Accessibility, enabled explicitly by the user, to control the installed messenger apps.
- Saves stale in-flight sends as `UNKNOWN` after a process restart instead of blindly resending and risking duplicates.
- Optional one-time UI Record/Test captures view selectors for messenger UI versions whose control IDs differ.

## Target packages
- Bale: `ir.nasim`
- Eitaa: `ir.eitaa.messenger`
- Rubika: `app.rbmain.a`

## Reliability limits
Android can stop all app background work after an explicit Force stop, and OEM Restricted battery mode can suppress alarms/jobs/boot delivery. The app includes a Reliability Settings button to request exact-alarm access and battery-optimization exemption, but those settings must be granted by the device owner.
