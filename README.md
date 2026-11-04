# abhimanyu

Offline voice assistant for a Pixel 10a ("Hey Abhimanyu").
It runs fully on-device with no internet permission, gates every action by risk tier,
and blocks payments, banking, photo access and message reading by design.

- [Design document](docs/design.md): goals, action layers, security model, feature list, build phases and decisions.

## Status: Phase 1 (push-to-talk basics)

Tap the mic, speak a Tier 0 command in English, Hindi or Hinglish, and it runs through
direct Android APIs. It replies in the language you spoke, with tones, haptics and the edge glow.

Works now:

- **Device:** flashlight, volume (media, ring, alarm), mute, silent/vibrate/ring, brightness.
- **Do Not Disturb:** on or off, including "for one hour".
- **Clock:** alarms and timers, set through the Clock app without opening it.
- **Media:** play, pause, next, previous.
- **Information:** time, date, battery.
- **Assistant:** help, stop, "phir se" (repeat), reply language and captions.
- **Indian times and durations:** "saade chhe baje", "paune aath", "dedh ghanta", "kal subah".

Not yet (later phases): the wake word, the AI model (Gemma), fingerprint and the risk gate above Tier 0,
lock screen, and controlling other apps.

## Install on the phone

1. Open the latest **Build APK** run under the repo's Actions tab, download the `abhimanyu-apk`
   artifact, unzip it, and open `app-debug.apk` on the phone. Allow installing from that source when asked.
2. Speech models: the app has no internet, so download these in Chrome first:
   - Indian English: `vosk-model-small-en-in-0.4.zip`
   - Hindi: `vosk-model-small-hi-0.22.zip`

   Both are on the Vosk models page (alphacephei.com/vosk/models). Then in the app tap **Import** next
   to each model and pick the zip. The app shows the file's SHA-256 so you can compare it with the download page.
3. **Voices for replies:** tap **Open**, then go to Preferred engine → Speech Services by Google →
   Install voice data, and install **English (India)** and **Hindi (India)**.
4. Allow **Do Not Disturb access** (for silent mode and DND) and **Modify system settings** (for brightness).
5. Tap the mic and try "flashlight on", "torch band karo" or "दस मिनट का टाइमर लगाओ".

Builds are signed with the same key each time, so a new APK installs over the old one and keeps the imported models.

## Develop

```bash
./gradlew -p core test         # parser, tiers and reply templates; no Android SDK needed
```

```bash
./gradlew :app:assembleDebug   # needs the Android SDK (CI does this)
```

The `core` module is plain Kotlin, so command understanding and replies are tested without a phone.
CI fails the build if the APK ever requests a network permission.
