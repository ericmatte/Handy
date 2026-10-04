# Handy for Android

A native Android companion to Handy, with two ways to dictate:

- **Voice keyboard (recommended):** you keep Gboard. Tap its globe key (or long-press the space bar) and Handy takes over the keyboard area: it starts listening right away, shows live voice-level bars so you can see it records cleanly, types the transcript at the cursor and switches back to Gboard. It works in every app, with no accessibility service.
- **Floating button (optional, off by default):** an accessibility service shows a small Handy button next to the focused text field. Tap it, speak, and the transcript is inserted.

It is a port of desktop Handy's transcription flow:

- **On-device models:** desktop's built-in models that fit on a phone (Parakeet V3/V2, Whisper Turbo/Small/Base, Moonshine, SenseVoice, Canary), run with sherpa-onnx.
- **Same cleanup:** custom words, filler-word removal and Silero VAD, with desktop's code and tests ported.
- **Same Claude post-processing:** your own API key, named prompts and a separate "with Claude" action.
- **Desktop extras:** history, start/stop sounds, trailing space, auto submit, and the model unload timeout.

See [Desktop parity](#desktop-parity) for the full list.

The desktop app (Tauri, `src/`, `src-tauri/`) is untouched; this folder is a standalone Gradle project.

## Install

### 1. Get the APK

- **From CI:** every push that touches `android/` runs the [Android workflow](../.github/workflows/android.yml). It publishes a pre-release named `Handy Android debug N` with `handy-android-debug-<sha>.apk` attached (open the repo's _Releases_ page from your phone). The same APK is also uploaded as a workflow artifact. Each CI build's version is the run number (`versionCode` N, `versionName` 0.3.N), so every APK installs over the previous one as an update.
- **Locally:** JDK 17+ and the Android SDK (platform 37):

  ```bash
  cd android
  ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
  ./gradlew testDebugUnitTest    # unit tests
  ```

### 2. Install it

```bash
adb install -r handy-android-debug-*.apk
```

or open the APK on the phone and allow "Install unknown apps" for your browser or file manager.

### 3. Enable the voice keyboard

In Handy, tap **Enable the Handy keyboard** (or **Settings › System › Keyboard › On-screen keyboard › Manage keyboards**) and turn on **Handy voice keyboard**. Android warns that a keyboard can collect what you type; Handy only receives what you dictate and inserts it, it never sees your Gboard typing. Keep Gboard as the default keyboard.

To dictate, open Gboard in any field and tap its **globe key** (shown once several keyboards are enabled) or **long-press the space bar** and pick _Handy voice keyboard_. _Choose keyboard_ in Handy opens the same picker.

### 4. Optional: the floating button

Under _How to dictate_ in Handy, pick **Floating button** (the keyboard's settings are then hidden, and vice versa), then enable the accessibility service: **Settings › Accessibility › Installed apps (or Downloaded apps) › Handy dictation button › On.** The _Open accessibility settings_ button in Handy goes straight there.

On Android 13+ a sideloaded app's accessibility switch is greyed out with a "Restricted setting" dialog until you allow it:

1. Try to enable the service once; the dialog appears. On Android 15+ this first attempt is required, otherwise the menu entry below doesn't show.
2. Open **Settings › Apps › Handy** (or the _Open app info_ button in Handy).
3. Tap the **⋮** menu (top right) › **Allow restricted settings**, then confirm with your PIN or fingerprint.

### 5. Allow the microphone

Open Handy and tap **Allow microphone**. Optionally allow notifications, so the "Handy is listening" notification is visible while recording.

### 6. Download a speech model

In Handy, open **Speech models** and download one. Parakeet V3 is recommended: about 490 MB, 25 European languages including French. Downloads come from the [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), Wi-Fi recommended. Keep the screen open; an interrupted download resumes where it stopped, and installing takes a few minutes. The first downloaded model is selected automatically.

### 7. Optional: Claude post-processing

In **Post-processing (Claude)**:

- Turn on _Clean up with Claude_ and paste your Anthropic API key.
- Keep or change the model: `claude-haiku-4-5` by default, and _Load models_ lists the ones your key can use.
- Pick or edit a prompt, or add your own. The default is desktop's "Improve Transcriptions", and `${output}` is replaced by the transcript.
- **Test** sends a sample sentence through the selected prompt.
- Under **Button tap**, choose whether a tap transcribes plainly or with Claude. The long-press menu always offers the other action, like desktop's two shortcuts.

## Using it

### Voice keyboard

- Switch to Handy from Gboard (globe key or long-press on space). It listens right away (turn off _Start listening right away_ to tap first); the start chime plays once the mic is live, if sounds are on.
- The bars scroll from right to left, one per 60 ms of audio: flat dim bars mean silence, pink bars that follow your voice mean Handy hears you cleanly.
- Recording stops after the silence timeout, or tap the hand. The status line shows _Transcribing…_, then _Cleaning up with Claude…_ when post-processing is on.
- The text is inserted at the cursor with desktop's spacing rules, then Handy switches back to Gboard (turn off _Return to the previous keyboard_ to stay).
- The **Claude** chip (shown when post-processing is configured) toggles Claude for this and later dictations; it is the same setting as the button's tap action.
- The keyboard icon cancels and goes back to Gboard; the gear opens Handy's settings. Leaving the field or closing the keyboard cancels a recording.
- A short vibration when the keyboard opens and when recording stops.
- On password, PIN and code fields Handy doesn't record and says so.

### Floating button

- Focus a text field: the button fades in at the field's bottom-right corner, outside the field and never over the keyboard.
- **Tap** to start recording (light haptic). The ring pulses with your voice. Recording stops after the configured silence, or when you tap again.
- A thin spinner shows while transcribing (and while Claude cleans the text, if enabled), then a brief check, and the text appears at the cursor. On failure the button shakes and a toast explains why.
- The first dictation after a while loads the model, which takes a few seconds; it starts loading as soon as you tap, while you speak. The model then stays in memory for the _Unload model_ delay (5 minutes by default, as on desktop).
- **Drag vertically** to move the button. The offset is remembered per app.
- **Long-press** for _Dictate with/without Claude_ (when post-processing is on), _Hide in this app_ and _Settings_.
- Every dictation goes to **History** (last 5 by default, as on desktop), where you can copy the final or original text.

The button is hidden on password fields and other sensitive fields, in excluded apps, on the lock screen, when the keyboard is closed, while scrolling and when the window changes.

## How it works

| Area                         | Where                                                                                          | Notes                                                                                                                                                                                                                                                                                                              |
| ---------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Focus tracking               | `service/HandyAccessibilityService.kt`                                                         | Base events: `typeViewFocused \| typeViewTextSelectionChanged \| typeWindowStateChanged`. While an eligible field is focused, `typeViewScrolled \| typeWindowsChanged` are added at runtime (to hide on scroll and track the keyboard), then removed. All events are debounced (80 ms) into one `evaluate()` pass. |
| Sensitive fields             | `core/SensitiveFieldDetector.kt`                                                               | `isPassword`, password `inputType`s (text, web, number, visible), and keywords in the hint, view id and content description (password, mot de passe, pin, nip, otp, code, cvv, carte…), matched on whole tokens.                                                                                                   |
| Exclusions                   | `core/ExclusionMatcher.kt`, `settings/InstalledApps.kt`                                        | Always: Handy itself, `com.android.systemui` (status bar and lock screen), all home apps; also skipped while the keyguard is locked. On first run, installed banks, password managers and authenticators are added to the editable blacklist.                                                                      |
| Placement                    | `core/ButtonPlacer.kt`                                                                         | Candidates: right of the field, below, above, left. The first one on screen, off the keyboard (`TYPE_INPUT_METHOD` window bounds) and off the field wins.                                                                                                                                                          |
| Overlay                      | `overlay/OverlayController.kt`, `overlay/HandyButtonView.kt`                                   | `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW`), `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL \| FLAG_LAYOUT_NO_LIMITS`. Plain `View`, no Compose in the overlay. Animators run only while recording or processing.                                                                                         |
| Audio                        | `audio/AudioRecorder.kt`, `audio/SileroVad.kt`, `core/SpeechSegments.kt`                       | `AudioRecord`, 16 kHz mono PCM16, `VOICE_RECOGNITION` source, released as soon as recording stops. Silero VAD (desktop's model) stops after the silence delay and keeps only the voiced parts (padded 200 ms). An energy VAD is the fallback.                                                                      |
| Mic access in the background | `service/MicrophoneForegroundService.kt`                                                       | See below.                                                                                                                                                                                                                                                                                                         |
| Insertion                    | `service/TextInserter.kt`, `core/TextMerger.kt`                                                | `ACTION_SET_TEXT` with the text merged at `textSelectionStart/End` (ignoring the placeholder when `isShowingHintText`), then `ACTION_SET_SELECTION` after the insertion. Fallback: clipboard + `ACTION_PASTE`, then restore the previous clip.                                                                     |
| Transcription                | `transcription/ModelCatalog.kt`, `SherpaTranscriber.kt`, `ModelManager.kt`                     | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (ONNX Runtime, CPU), see [Models](#models). Queued, resumable downloads; only the needed int8 files are extracted. Preloaded while you speak, released after the unload delay or on memory pressure.                                                   |
| Text cleanup                 | `core/TextCleanup.kt`                                                                          | Port of desktop's `audio_toolkit/text.rs` (and its tests): custom words (Levenshtein + Soundex, n-grams), filler words (universal + language-gated), stutter collapsing.                                                                                                                                           |
| Post-processing              | `transcription/ClaudePostProcessor.kt`, `core/PostProcessPrompt.kt`, `settings/SecretStore.kt` | Official Anthropic Java SDK, Messages API, `claude-haiku-4-5` by default. Same prompt convention as desktop (`${output}`). API key encrypted with an Android Keystore AES-GCM key. On any error the raw transcript is inserted and a toast says so.                                                                |

### Microphone from an accessibility service (Android 14+)

`RECORD_AUDIO` is a _while-in-use_ permission. An app with no visible activity is fed silence (Android 11+), and Android 14 requires a foreground service of type `microphone` to record from the background. Being an accessibility service doesn't exempt Handy from that. So each tap starts `MicrophoneForegroundService` (`foregroundServiceType="microphone"`, with a low-importance "Handy is listening" notification), records, and stops the service as soon as the mic is released. If the system refuses to start it (`ForegroundServiceStartNotAllowedException` or a `SecurityException`, logged under the `HandyMicFgs` tag), Handy records directly anyway. `AudioRecorder` then checks `AudioRecordingConfiguration.isClientSilenced()` and reports a blocked mic as an error instead of transcribing silence.

**Please confirm on your device** (manual test 7 below). This is the part most likely to differ between Android versions and OEMs.

### Battery

- No polling, sensors or wake locks; no `WorkManager`, alarms or broadcast receivers.
- Inference only runs after a dictation (a few seconds of CPU). The model is dropped from memory 5 minutes after the last dictation.
- While no editable field is focused the service only receives focus, cursor and window-state events, and does nothing with them beyond a single `findFocus` call after the debounce.
- The microphone and its foreground service only exist between a tap and the end of that dictation.

## Models

Every configuration below was run with sherpa-onnx 1.13.8 on the models' own sample recordings (French and English for the European models, plus zh/ja/ko/yue for SenseVoice) before shipping.

| Model             | Desktop equivalent | Download | Languages                                 | Translate to English | Notes                                                                                    |
| ----------------- | ------------------ | -------- | ----------------------------------------- | -------------------- | ---------------------------------------------------------------------------------------- |
| Parakeet V3       | Parakeet V3        | 487 MB   | 25 European (incl. French), auto-detected | –                    | Recommended. Fastest accurate multilingual model.                                        |
| Parakeet V2       | Parakeet V2        | 482 MB   | English                                   | –                    | Best for English only.                                                                   |
| Whisper Turbo     | Whisper Turbo      | 563 MB   | 99, auto-detected                         | No (as on desktop)   | Most accurate Whisper here; slower, ~1 GB on disk.                                       |
| Whisper Small     | Whisper Small      | 639 MB   | 99, auto-detected                         | Yes                  | Only the int8 weights are kept (~375 MB).                                                |
| Whisper Base      | (desktop catalog)  | 207 MB   | 99, auto-detected                         | Yes                  | For low-end phones.                                                                      |
| Moonshine Base    | Moonshine Base     | 111 MB   | English                                   | –                    | Very fast.                                                                               |
| SenseVoice        | SenseVoice         | 163 MB   | zh, en, ja, ko, yue, auto-detected        | –                    | The 2024-07-17 build: the 2025-09-09 one misdetected every clip as Cantonese in testing. |
| Canary 180M Flash | Canary 180M Flash  | 153 MB   | en, de, es, fr (must be set)              | Yes                  | No language detection: uses the selected language, else the phone's.                     |

Not ported: Whisper Medium and Large (1.9 GB+, too slow on a phone CPU), and desktop's newer GGUF catalog, which runs on `transcribe-cpp` and has no Android build.

## Desktop parity

| Desktop setting / behaviour                                                                   | Android                                                                                                                                 |
| --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `selected_model`, model download/delete                                                       | Speech models screen                                                                                                                    |
| `selected_language`, `translate_to_english`                                                   | Transcription › Language (only languages the model supports; same fallback rules)                                                       |
| `custom_words`, `word_correction_threshold`                                                   | Transcription › Custom words (same algorithm and default 0.18)                                                                          |
| `filler_word_removal_enabled`, `custom_filler_words`                                          | Transcription › Filler words (same lists and language gating)                                                                           |
| `model_unload_timeout`                                                                        | Transcription › Unload model (same choices, default 5 min)                                                                              |
| `vad_enabled` (Silero)                                                                        | Transcription › Voice activity detection (default on)                                                                                   |
| `post_process_enabled`, API key, model, prompts, selected prompt                              | Post-processing (Anthropic only; key encrypted with the Android Keystore)                                                               |
| `transcribe` / `transcribe_with_post_process` shortcuts                                       | Button tap action + long-press menu                                                                                                     |
| LLM response cleanup (`<think>` block, invisible characters)                                  | Same                                                                                                                                    |
| `audio_feedback`, `sound_theme`, `audio_feedback_volume`                                      | Button and behaviour › Sounds (marimba and pop, same files)                                                                             |
| `append_trailing_space`, `auto_submit`                                                        | Button and behaviour › Output (auto submit sends the field's IME action, Android 11+)                                                   |
| `history_limit`, history                                                                      | History screen (text only; recordings are not kept)                                                                                     |
| Global shortcuts, overlay, paste methods                                                      | Replaced by the voice keyboard (`InputConnection.commitText`) and the optional floating button (`ACTION_SET_TEXT` + clipboard fallback) |
| Other providers (OpenAI, Groq…), Chinese script conversion, custom sound files, debug options | Not ported                                                                                                                              |

## Settings

The home screen shows the service status (with the restricted-settings walkthrough on Android 13+), permissions and the selected model. It links to:

- **Speech models**
- **Transcription:** language, translate, custom words, filler words, unload delay, VAD.
- **Post-processing:** Claude key, model, prompts, test, tap action.
- **Button and behaviour:** opacity and size with a live preview, silence delay, sounds, trailing space, auto submit.
- **Excluded apps**
- **History**
- **Test:** a screen with Compose, View and password fields.

To re-skin the button, replace `app/src/main/res/drawable/ic_handy_button.xml` (any vector or bitmap drawable; it is drawn centered at ~60 % of the button). It is the hand from the desktop logo (`src/components/icons/HandyHand.tsx`, same artwork as `src-tauri/icons`). Colors are in `res/values/colors.xml`.

### Known sensitive apps

`ExclusionMatcher.KNOWN_SENSITIVE_PACKAGES` only contains package ids confirmed on Google Play (search results showing `play.google.com/store/apps/details?id=…`, October 2026), plus KeePassDX libre confirmed on F-Droid. Anything else is caught by keywords on the package id and app label (bank, banque, caisse, desjardins, td, rbc, bmo, scotia, cibc, bnc, tangerine, bitwarden, 1password, keepass, lastpass, dashlane, authenticator, wallet…).

| App                                       | Package                                                                                  |
| ----------------------------------------- | ---------------------------------------------------------------------------------------- |
| Desjardins                                | `com.desjardins.mobile`                                                                  |
| TD Canada / TD Authenticate               | `com.td` / `com.td.softtoken`                                                            |
| RBC Mobile                                | `com.rbc.mobile.android`                                                                 |
| BMO                                       | `com.bmo.mobile`                                                                         |
| Scotiabank                                | `com.scotiabank.banking`                                                                 |
| CIBC                                      | `com.cibc.android.mobi`                                                                  |
| Banque Nationale                          | `ca.bnc.android`                                                                         |
| Tangerine                                 | `ca.tangerine.clients.banking.app`                                                       |
| Simplii                                   | `com.pcfinancial.mobile`                                                                 |
| EQ Bank                                   | `com.eqbank.eqbank`                                                                      |
| Laurentian Bank                           | `ca.laurentianbank.mobileapp`                                                            |
| Wealthsimple                              | `com.wealthsimple.trade`                                                                 |
| KOHO                                      | `ca.koho`                                                                                |
| Bitwarden / Bitwarden Authenticator       | `com.x8bit.bitwarden` / `com.bitwarden.authenticator`                                    |
| 1Password                                 | `com.onepassword.android`                                                                |
| KeePassDX (Play / F-Droid)                | `com.kunzisoft.keepass.free` / `.libre`                                                  |
| Keepass2Android (online / offline)        | `keepass2android.keepass2android` / `_nonet`                                             |
| LastPass / LastPass Authenticator         | `com.lastpass.lpandroid` / `com.lastpass.authenticator`                                  |
| Dashlane                                  | `com.dashlane`                                                                           |
| Proton Pass                               | `proton.android.pass`                                                                    |
| Keeper                                    | `com.callpod.android_apps.keeper`                                                        |
| NordPass                                  | `com.nordpass.android.app.password.manager`                                              |
| Enpass                                    | `io.enpass.app`                                                                          |
| Google / Microsoft / Twilio Authenticator | `com.google.android.apps.authenticator2` / `com.azure.authenticator` / `com.authy.authy` |

## Manual test checklist

### Voice keyboard

1. Enable the Handy keyboard from Handy's home screen; the status turns to _Handy keyboard enabled_.
2. In Messages, Chrome (address bar and a web form) and the Claude app: open Gboard, switch to Handy with the globe key. It starts listening, the bars move with your voice and stay flat when you're silent. Stop talking: the text appears at the cursor and Gboard comes back.
3. Type "Bonjour" with Gboard, switch to Handy, say "comment ça va": the result is "Bonjour comment ça va" with a single space.
4. Tap the hand while listening: it stops and transcribes. Tap the keyboard icon while listening: nothing is inserted and Gboard comes back. The gear opens Handy.
5. With Claude configured, toggle the **Claude** chip and dictate: the status shows _Cleaning up with Claude…_ and the cleaned text is inserted.
6. On a password field, switch to Handy: it says voice input is off and doesn't record.
7. Turn off _Start listening right away_: Handy waits for a tap. Turn off _Return to the previous keyboard_: Handy stays after inserting.
8. Revoke the microphone permission or delete every model: the panel explains it and _Open Handy_ opens the settings.

### Floating button

Pick _Floating button_ under _How to dictate_ and run with Gboard as the keyboard. For each item, also check that the button never covers the keyboard or the typed text.

1. **Handy › Test** screen
   - [ ] The button appears on _Single line_, _Multi-line_ and _Classic EditText_; never on _Password_.
   - [ ] Dictate a sentence in French, then one in English: both are transcribed in their language, with punctuation.
   - [ ] Say "euh… je pense que c'est bon": "euh" is removed (filler words). Add "Handy" as a custom word, say "handee": it becomes "Handy".
   - [ ] Pause for a few seconds mid-sentence: the recording continues; stop talking: it stops after the silence delay.
   - [ ] On an empty field, the placeholder is replaced, not merged (only your sentence).
   - [ ] With text and the cursor in the middle, the text is inserted at the cursor with spaces on both sides, and the cursor ends up right after it.
   - [ ] With a word selected, the selection is replaced.
   - [ ] Tap → ring pulses with your voice → stops after the silence delay → spinner → check.
   - [ ] Close the keyboard (back gesture): the button fades out. Tap the field again: it comes back.
   - [ ] Scroll the screen: the button hides, then comes back once scrolling stops.
2. **Google Messages:** conversation input (docked on the keyboard) → the button sits above the input, right-aligned. Dictate, and the text lands in the input.
3. **Chrome:** the address bar and a web form field (e.g. a search box). The button appears and insertion works. On a login page, nothing appears on the password field.
4. **Gmail:** To, Subject and body. Insertion in the body at the cursor, and the cursor follows.
5. **Password field** in any app's login screen: no button.
6. **Banking app** (e.g. Desjardins, TD): listed under _Excluded apps_ after the first launch; no button anywhere in it. Remove it from the list → the button appears on non-password fields → re-add it.
7. **Mic in the background:** start a dictation from Messages with Handy's settings closed. The "Handy is listening" notification shows during recording and disappears right after; the audio is not silent (the ring reacts to your voice). If you get "microphone unavailable", note the Android version and model.
8. **Drag:** drag the button up in Messages, leave, come back: it's at the new height. Another app keeps its own offset.
9. **Long-press › Hide in this app:** the app appears in the excluded list. **Long-press › Settings** opens Handy.
10. **Lock screen / notification shade / launcher search:** no button.
11. **Settings:** opacity and size changes apply to the live button. The button dims to ~40 % after 3 s without interaction.
12. **Battery:** after a few minutes idle, _Settings › Battery › Handy_ shows near-zero usage.
13. **Models:** download a second model (e.g. Moonshine Base) while the first is installed: it is queued and selectable once ready. Switch models and dictate. Whisper Small with _Translate to English_ on: French speech comes out in English. Delete every model → tapping the button explains that one must be downloaded and opens the settings.
14. **Claude:** with post-processing on and the tap set to _Transcribe and clean up with Claude_, say "euh alors la réunion est à trois heures virgule pas quatre heures point" → you get something like "La réunion est à 3 h, pas 4 h." Long-press › _Dictate without Claude_ gives the plain transcript. Add a second prompt (e.g. "Translate to English: ${output}"), select it, dictate. With a wrong API key, the transcript is inserted and a toast says post-processing failed. In airplane mode, transcription still works (on-device).
15. **History, sounds, output:** the History screen lists the last dictations with the original and the Claude version. Sounds on: the start chime plays once the mic is ready, the stop chime at the end. Auto submit on in Messages: the message is sent after insertion.

## Troubleshooting

- **"App not installed as package conflicts with an existing package":** the installed APK was signed with a different key. Builds before 0.3.20 were each signed with a random CI key; since then every debug APK is signed with `app/debug.keystore` (a debug-only key, committed on purpose). Uninstall Handy once, then install the new APK; later APKs install as updates.

- **The globe key doesn't show in Gboard:** enable the Handy keyboard first; Gboard shows the globe key (or the long-press on space) only when more than one keyboard is enabled. Gboard settings › Preferences › _Show language switch key_ also controls it.
- **The voice keyboard records nothing:** run `adb logcat -s HandyIme HandyMicFgs`. Check that the bars move when you talk; if they stay flat, another app may hold the microphone.

- **The button doesn't appear in an app:** run `adb logcat -s HandyA11y`, then focus the field. The log says whether the field was skipped as sensitive (with its hint and id), skipped because the app is excluded, or not found under the focused view. Field detection uses input focus first, then the field the last focus, click or cursor event came from (Compose apps like Claude, Chrome), then a bounded search of the focused view.
- **After installing a new APK**, Android sometimes keeps the old accessibility service running: toggle _Handy dictation button_ off and on in the accessibility settings.

## What's next

- Instrumented tests for `TextInserter` against View, Compose and WebView fields.
- Download models in a foreground service so long downloads survive leaving the settings screen.
- Other post-processing providers (OpenAI-compatible endpoints), and Chinese script conversion.
- A release signing config and R8 rules (the debug APK is unminified).
- The clipboard fallback can't restore the previous clip on Android 10+ when Handy can't read it (only the focused app or the IME may); the transcript then stays on the clipboard.
