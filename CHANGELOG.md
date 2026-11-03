# Changelog

## Known issues
- After Bluetooth is switched off and on, the bridge doesn't reconnect to the remote by itself. Reboot the box, or
  restart the service: `am start-foreground-service -n net.sk4pro.siriremote/.BridgeService` (after `am force-stop`).

## 1.5 — 2026-11-03
- Restarts Google Assistant 30 s after Google Play Services or the Assistant updates in the background. Such an update
  kills the Assistant mid-session, and it could come back stuck "listening", pausing any video the moment it started
  (seen in Nuvio). Manual fix if it ever happens anyway: `am force-stop com.google.android.katniss`.

## 1.4 — 2026-11-02
- Voice search inside apps: hold Siri while an app's own voice search is listening (e.g. select Nuvio's mic button)
  and your voice goes into that app instead of opening the Assistant. Works with any app using Android's speech
  recognizer. With no app listening, Siri opens the Assistant as before.

## 1.3 — 2026-11-02
- Voice search now works on boxes where it heard nothing (Amlogic, e.g. Ugoos SK4).
  - Android's HID host is switched off for the remote. With nothing behind the remote's empty report map it stalled
    ~50 ms on every report, so the microphone arrived at 20 of its 50 frames per second: speech came through chopped
    and slowed down.
  - The decoded voice is fed straight into the Assistant's voice recording (Android audio-policy injection), for
    Assistant builds that ignore the audio stream handed to them and record the box's microphone instead, which on
    these boxes is an empty placeholder. Needs the new privileged permission `MODIFY_AUDIO_ROUTING`.
- Voice level lowered 12 dB: the remote's decoded speech was clipping.
- Downsampling to 16 kHz follows the decoder's reported output rate instead of assuming 48 kHz.

## 1.2 — 2026-11-01
- Settings screen ("Siri Remote" in the app list): pair, reconnect, disconnect, forget the remote, and see its status.

## 1.1 — 2026-10-30
- Click-pad OK stays pressed for as long as you hold it, so Android's long-press works like on a regular remote.
- Fixed key injection stalling: the root shell's output is now read instead of being left to fill up.
- Fixed a key sometimes ignoring its next press (no more releases sent for keys that aren't down).

## 1.0 — 2026-10-29
- First release: Apple Siri Remote (silver, 2nd/3rd gen) on rooted Android TV as a Magisk module.
- Buttons through a virtual "Siri Remote" input device; Siri tap = Menu, hold Siri = Google Assistant voice search.
