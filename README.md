# SoundSweeper Control

Independent Android prototype for Volkano SoundSweeper headphones. The app does not represent or originate from Volkano.

## Version 1.2

- Redesigned native Android interface with a consistent sans-serif font, dark navy surfaces, ice-blue and coral accents.
- Displays Android Bluetooth AUDIO profile connection status (A2DP/Headset). Requires Nearby devices permission on Android 12+.
- Displays connected SoundSweeper headphone battery **only if Android receives a valid battery level from that device**. Otherwise displays "Unavailable" instead of guessing. Includes a refresh button and Bluetooth settings shortcut.
- Lets you select a local audio file through Android's system document picker (no broad storage permission). Plays it using a MediaPlayer.
- Balanced, Bass Boost, and Clear Voice presets; bass and treble sliders. When supported, the audio Equalizer applies solely to the **player's own audio session**. Other apps (YouTube, Spotify), firmware ANC, and headphones' hardware EQ are **not** controlled by this app.
- Audio playback is paused when you leave the app.

## Testing

Connect SoundSweeper in Android Bluetooth settings before opening the app. Test battery reporting with headphones both connected and disconnected; "Unavailable" means the Bluetooth stack did not expose a usable battery percentage. Then choose a local song and compare presets on an audio device that supports Android's Equalizer audio effect.

## Build

Open Actions > Build Android APK, select the latest successful run, download the `soundsweeper-debug-apk` artifact, extract it and install the APK on an Android device.

This is a test/debug APK, not a Play Store production build.
