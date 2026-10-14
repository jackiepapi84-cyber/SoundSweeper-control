# SoundSweeper Control

Experimental, independent Android companion for Volkano SoundSweeper headphones. Not affiliated with Volkano or OPPO.

## Version 1.5 — Device diagnostics

The interface keeps a device-first overview inspired by the organization of HeyMelody, but uses original artwork and styling.

### Working features
- Bluetooth A2DP/headset connection status and best-effort headphone battery reporting.
- Streaming shortcuts for Spotify, Apple Music and YouTube.
- Factory button reference, plus **locally stored draft preferences** (these do **not** remap headset hardware).
- ANC instructions for the physical switch. No app-controlled ANC toggle.
- Hardware diagnostics:
  - Detects connected SoundSweeper Bluetooth type (Classic, LE or dual-mode) and audio profiles.
  - Displays **cached classic Bluetooth service UUIDs**. A user-initiated "Read supported services" button invokes Android's read-only Bluetooth SDP discovery using `fetchUuidsWithSdp()`.
  - Optionally displays media/volume **key events delivered to this foreground Activity**. Android may route these to the OS or another app instead, so no event isn't proof of incompatibility.
  - Copies a text report **only when requested**; excludes hardware/MAC addresses, and does not upload device data.
- No EQ feature.

### Important limitations
Bluetooth service UUIDs are not evidence of writable ANC controls or firmware button-remapping support. Some headphones have separate BLE control services that aren't discoverable with classic SDP. This app **does not scan BLE, open GATT connections, send proprietary commands or write to firmware**. Without a documented control API or verified real-device protocol, those controls cannot be offered honestly.

### Testing v1.5
1. Connect SoundSweeper in Android Bluetooth settings.
2. Open SoundSweeper Control → **Hardware diagnostics**.
3. Tap **Read supported services**, wait for service discovery.
4. Keep the diagnostic screen open and press a headset media button once. No event is a normal possible result.
5. Tap **Copy diagnostic report**, then paste the report into the chat for interpretation. The report does not include your Bluetooth MAC address.

### Build and install
In GitHub Actions, open the latest successful **Build Android APK** workflow run, download the `soundsweeper-debug-apk` artifact, extract its ZIP, and install the APK. Debug builds are not Play Store production builds.
