# TS18 CarDiag

Diagnostics app for a TOPWAY TS18 Android head unit (Android 10) in a Renault Megane II.
Built by GitHub Actions (see `.github/workflows/build-apk.yml`); download the `TS18-CarDiag-debug-apk` artifact.

## What it does
1. **System info** – device/firmware properties, car-related packages (canbox etc.), and system settings keys that might carry vehicle state.
2. **Change watcher** – snapshots Settings (System/Secure/Global) and system properties every ~1.5 s and prints what changes (open/close a door and watch).
3. **ELM327 CAN test** – connects to a *classic Bluetooth* ELM327, checks for CAN traffic on the OBD port (4 protocols), then runs a guided door test (driver, passenger, rear L/R, trunk) and reports which CAN ID / bit changes.

Use **Copy report** / **Share report** to export the output.

## Limits
- ELM327 adapters are slow and sample only part of the CAN traffic; use the ID filter for focused sniffing.
- Read-only: the app never writes to the car.
- Not device-tested.
