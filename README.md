# Siri Remote bridge for Android TV (Magisk module)

Use Apple's **Siri Remote (silver, 2nd/3rd generation)** with a rooted Android TV box, including Google Assistant voice search.

Normal Bluetooth pairing doesn't work: the remote hides its buttons behind Apple's own protocol. This module installs a
small privileged app that talks that protocol and turns the remote into an ordinary Android remote.

## Requirements
- Android TV, Android 13 or newer, rooted with Magisk
- For voice: Google Assistant for Android TV (`com.google.android.katniss`)
- Built and tested on a Ugoos SK4 Pro (Amlogic, Android 14). Other boxes should work but are untested.

## Install
1. Magisk app → Modules → Install from storage → `SiriRemoteBridge-v1.1-magisk.zip`, then reboot.
2. Within 5 minutes after boot, hold **Back (<) + Volume Up** on the remote for ~5 s, right next to the box.
   It pairs and connects by itself. Later boots reconnect automatically on the first button press.

To pair again (new remote or after unpairing):
`adb shell am start-foreground-service -n net.sk4pro.siriremote/.BridgeService --ez pair true`, then hold Back + Volume Up.

## Buttons
| Remote | Android |
|---|---|
| Click ring up / down / left / right | D-pad (hold to repeat) |
| Click pad centre | OK (hold for long-press, like a regular remote) |
| Back (<) | Back |
| TV | Home |
| Play/Pause | Play/Pause |
| Volume + / − | Volume (hold to repeat) |
| Mute | Mute |
| Power | Power |
| Siri, tap | Menu |
| Siri, hold and speak | Google Assistant voice search |

Not supported: touch-surface swipes.

## How it works
- The app connects to the remote over Bluetooth LE, unlocks its input reports, and subscribes to buttons and microphone only.
- Keys come from a virtual input device named "Siri Remote", registered through Android's `uinput` tool in a root shell.
- Voice: the remote's Opus microphone audio is decoded, converted to 16 kHz, and handed to Google Assistant through the same
  audio-stream interface Google's own TV remote service uses.
- It only acts on devices that expose Apple's remote service, so it can't take over another Bluetooth device by mistake.
- Privileged permissions used: `BLUETOOTH_PRIVILEGED` (Apple's HID data), `START_ACTIVITIES_FROM_BACKGROUND` (opening the Assistant).

## Build
Needs JDK 17 and the Android SDK (platform 34).
```
./build.sh        # -> dist/SiriRemoteBridge-v<version>-magisk.zip
```
Source: `app/` (bridge app), `module/` (Magisk module template; the APK is copied in by the build).

## Troubleshooting
- Log: `adb shell logcat -s SiriBridge`
- Remote doesn't pair: start the pairing command above, keep the remote within a metre, and unplug any Apple TV it was paired with.

## Uninstall
Remove the module in the Magisk app and reboot, then unpair the remote in Settings → Bluetooth if it's still listed.

## Credits
Button protocol from [Yanndroid/SiriRemote-Linux](https://github.com/Yanndroid/SiriRemote-Linux) (gen-3 branch). The handles
and microphone handling were adapted for the silver remote on Android.
