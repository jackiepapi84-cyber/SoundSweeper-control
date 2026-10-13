# SoundSweeper Control

Independent experimental Android companion for SoundSweeper Bluetooth headphones. **Not affiliated with Volkano.**

## Version 1.3 — Streaming edition

- Replaces the local music player with shortcuts to Spotify, Apple Music (Android), and YouTube.
- Keeps Bluetooth audio connection detection and best-effort device battery reporting.
- Refines navy / ice-blue UI with clear sections and larger touch targets.
- Adds a shortcut to Android's **Sound settings**, plus a media-volume control and guidance for Spotify's equalizer.

### Important EQ limitations

This app **does not** equalize music played by Spotify, Apple Music or YouTube. Android music apps use their own audio sessions; a third-party app does not have a supported universal hook into all of them. Android's audio-effect control panel intent generally stores changes for the **calling application's own session**, so exposing that as a system-wide EQ would be misleading.

Use Spotify's in-app Equalizer option when supported, or an actual phone manufacturer system-wide EQ feature if your model supplies one. Sound settings may not contain an equalizer at all.

The SoundSweeper firmware's proprietary EQ/ANC control commands have **not** been identified or verified. Bluetooth A2DP support alone does not establish their existence.

### Testing

1. Install the latest debug APK from GitHub Actions.
2. Connect headphones and verify the connection and battery fields.
3. Open the three streaming services with their shortcuts.
4. Tap 'Open phone sound settings' and check whether your phone exposes an equalizer.
5. Tap 'Show media volume' to display Android's media volume panel.

### Build

See **Actions → Build Android APK** and download the `soundsweeper-debug-apk` artifact from the latest successful run. Extract the ZIP and install the APK. Debug builds are test packages, not signed production Play Store releases.
