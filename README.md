# abhimanyu

Offline voice assistant for a Pixel 10a ("Hey Abhimanyu").
It runs fully on-device with no internet permission, gates every action by risk tier,
and blocks payments, banking, photo access and message reading by design.

- [Design document](docs/design.md): goals, action layers, security model, feature list, build phases and decisions.

## Status: Phase 1 + Gemma brain

Tap the mic and speak a Tier 0 command in English, Hindi or Hinglish. **Gemma 4 E2B** hears
the audio directly and returns a transcript, the language and one command from a fixed list.
The command runs through direct Android APIs, and the assistant replies in the language you spoke,
with tones, haptics and the edge glow. Vosk plus the rule parser is an optional backup, used only
if Gemma isn't loaded.

Works now:

- **Device:** flashlight, volume (media, ring, alarm), mute, silent/vibrate/ring, brightness.
- **Do Not Disturb:** on or off, including "for one hour".
- **Clock:** alarms and timers, set through the Clock app without opening it.
- **Media:** play, pause, next, previous.
- **Information:** time, date, battery.
- **Assistant:** help, stop, "phir se" (repeat), reply language and captions.
- **Indian times and durations:** "saade chhe baje", "paune aath", "dedh ghanta", "kal subah".

Gemma can only choose from that list. Its reply is forced into a JSON shape, then every field is
checked before anything runs. An unknown tool or an out-of-range value is rejected, never "fixed".

Not yet (later phases): the wake word, fingerprint and the risk gate above Tier 0, the lock screen,
and controlling other apps.

## Install on the phone

1. Open the latest **Build APK** run under the repo's Actions tab, download the `abhimanyu-apk`
   artifact, unzip it, and open `app-debug.apk` on the phone. Allow installing from that source when asked.
2. **Gemma 4 E2B:** in Chrome, download exactly `gemma-4-E2B-it.litertlm` (2.59 GB) from
   huggingface.co/litert-community/gemma-4-E2B-it-litert-lm (Files tab). The Pixel 10a's Tensor G4
   runs this general build on its GPU, and it is the file Edge Gallery uses for audio input.
   The `_Google_Tensor_G5`/`G6`, `_NPU_`, `Qualcomm`, `intel` and `-web` files are built for other
   chips or runtimes, and won't load here. The app refuses them at import. Then in Abhimanyu tap
   **Import** next to "Gemma 4 E2B" and pick the file. It is copied into the app's private
   storage, and you can delete the download afterwards.
   - The copy Edge Gallery downloaded can't be reused, because Android keeps each app's files
     private to that app. Edge Gallery is still the place to benchmark speed on your phone.
3. **Voices for replies:** tap **Open**, then go to Preferred engine → Speech Services by Google →
   Install voice data, and install **English (India)** and **Hindi (India)**.
4. Allow **Do Not Disturb access** (for silent mode and DND) and **Modify system settings** (for brightness).
5. Tap the mic and try "flashlight on", "torch band karo" or "दस मिनट का टाइमर लगाओ".

Optional backup: `vosk-model-small-en-in-0.4.zip` and `vosk-model-small-hi-0.22.zip` from the
Vosk models page, imported under "Backup speech". Skip these unless you want a fallback.

Builds are signed with the same key each time, so a new APK installs over the old one and keeps
the imported models.

## Develop

```bash
./gradlew -p core test         # parser, Gemma protocol, tiers, reply templates; no Android SDK needed
```

```bash
./gradlew :app:assembleDebug   # needs the Android SDK (CI does this)
```

The `core` module is plain Kotlin, so command understanding and replies are tested without a phone.
CI fails the build if the APK ever requests a network permission.
