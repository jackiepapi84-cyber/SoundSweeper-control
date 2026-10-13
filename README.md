# SoundSweeper Control

An independent Android companion prototype for the Volkano SoundSweeper headphones.

## Version 1.1

- Native dark-themed connection screen with manual refresh and shortcut to Bluetooth settings.
- Detects **actively connected** Bluetooth audio devices via Android A2DP / Headset profiles.
- Identifies SoundSweeper by its Bluetooth name and updates on Bluetooth connection changes.
- Requests the Nearby devices / Bluetooth Connect permission on Android 12+.
- Does **not** pair headphones itself or issue proprietary firmware commands.

**Limitations:** Internal ANC adjustment, on-headphone EQ, and battery reporting have not been implemented or validated. The headphones must first be connected in Android Bluetooth settings. Name detection expects a device name resembling "SoundSweeper"; models with different advertising names may not be recognized.

## Build / install

Visit **Actions > Build Android APK** and download the `soundsweeper-debug-apk` artifact from the latest successful run. Extract the ZIP and install the APK on your Android phone. No local Android Studio setup is required.
