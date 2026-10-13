# SoundSweeper Control

An independent, experimental Android companion for Volkano SoundSweeper headphones. Not made by, endorsed by, or affiliated with Volkano or OPPO.

## Version 1.4 — Companion redesign

The interface takes **general design inspiration** from OPPO's HeyMelody layout: device-first overview, battery panel, concise settings rows, separate controls and ANC pages. All artwork and layout are original, with a navy/ice-blue/coral headphone logo. Not an OPPO interface clone.

### Features
- Connection status using Android Bluetooth audio profile access (A2DP / headset).
- Best-effort headphone battery display from Bluetooth-stack battery broadcasts on compatible Android phones.
- Quick-launch shortcuts for Spotify, Apple Music for Android, and YouTube.
- A **Button preferences** page with factory-control reference and editable, locally saved draft mappings. Drafts are **NOT sent to the headphones**, DO NOT remap physical keys, and are not applied to streaming apps. This is a planning screen only until a verified proprietary control protocol is available.
- **ANC guide** page with physical switch instructions. No working ANC on/off toggle or ANC state detector is claimed.

### Factory button controls
According to available SoundSweeper manuals for the VK-2027 series: power short press for Play/Pause or answer/end call; Volume + short press increases volume, long press next track; Volume − short press decreases volume, long press previous track; ANC uses a separate physical on/off switch. Verify these instructions against your particular device's manual.

### Limitations
No EQ, since Android doesn't provide this app with universal streaming-audio EQ access. We have not confirmed an exposed Bluetooth service that accepts button-mapping or ANC controls. Adding such functionality requires documented or reverse-engineered commands verified against the actual device. No commands are sent to firmware.

### Build
GitHub Actions → Build Android APK → latest successful run → download the `soundsweeper-debug-apk` artifact, extract the ZIP, and install the test APK on Android. This isn't a Play Store release.
