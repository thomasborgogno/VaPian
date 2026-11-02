# VaPian!

A native Android app (Kotlin + Jetpack Compose) that warns you about **speed cameras** in real time, using
OpenStreetMap data and community reports. One APK, two roles:

| | **Phone** | **Head unit / in-car tablet (Dashboard)** |
|---|---|---|
| Network | online (OSM/Overpass, Firebase, Google Maps) | **always offline** |
| What it does | map, edit and report cameras, settings, per-region data download, media player | radar, speed and limit, alerts, "Report" button, media player, volume |
| Data | downloads from OSM and syncs with Firestore | receives it from the phone over **Bluetooth Classic (RFCOMM)** |

The role is picked automatically: a wide landscape screen (≥ 840 dp) or car mode → Dashboard, otherwise Phone.
The head unit has no internet connection: a banner reminds you every 14 days to refresh the data from the phone,
which is the only node that is online. Reports made on the head unit go through the phone before reaching Firestore.

Requirements: Android 8.0+ (`minSdk 26`) and Bluetooth Classic for the phone ↔ head unit link.

## Features

- Real-time speed camera alerts with distance, speed and limit
- Phone ↔ head unit link over Bluetooth Classic, with per-region datasets
- Media player on the head unit (cover art, progress with seek) controlling the phone's playback
- Map with camera markers, filters, and interactive editing and reporting
- Dashboard with a Canvas radar view, volume control and a one-tap "Report" button
- Read-only vehicle signals from the head unit (doors, gear, speed) when available
- Built-in diagnostics screen to inspect GPS and vehicle signals

## Getting started

1. Install the **same APK** on the phone and on the head unit (enable "unknown sources"; on the head unit you can
   also use `adb install`).
2. **Pair the phone with the head unit** in the head unit's Bluetooth settings.
3. On the **phone**: grant location, notification and Bluetooth permissions; in *Settings → Speed camera data*
   download the regions you need; in *Head unit* keep "Always ready in background" on and, for the media player,
   grant notification access.
4. On the **head unit**: swipe the dashboard from right to left → *Settings → Choose phone*. When the link is up,
   the "phone disconnected" icon disappears and data and player start flowing.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37). Two local files are needed and are **never committed**:

- `local.properties`: `sdk.dir=...` and `MAPS_API_KEY=<your Google Maps API key>`
- `app/google-services.json`: your own Firebase project configuration

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug build includes a `DebugFixReceiver` to simulate GPS fixes via `adb`; it is not present in release builds.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — modules and design decisions
- [PROTOCOL.md](PROTOCOL.md) — Bluetooth link protocol

## Status

**Implemented:** guidance and alerts, Bluetooth phone ↔ head unit link with per-region datasets, head unit media
player, map with markers, filters and editing, settings, Dashboard with radar.

**Planned:** Google sign-in and guest linking, navigation mode on the phone, OSM OAuth/changesets, Android Auto,
region selection from the head unit.
