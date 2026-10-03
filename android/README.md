# Handy for Android: dictation button

A native Android companion to Handy. An accessibility service watches which text field has focus and shows a small floating Handy button next to it. Tap the button, speak, and the transcript is inserted at the cursor. You keep your usual keyboard (Gboard etc.): this is **not** a custom keyboard (IME).

> **Status:** transcription is simulated (`FakeTranscriber` returns a fixed sentence). The real on-device model and Claude post-processing come next (see [What's next](#whats-next)).

The desktop app (Tauri, `src/`, `src-tauri/`) is untouched; this folder is a standalone Gradle project.

## Install

### 1. Get the APK

- **From CI:** every push that touches `android/` runs the [Android workflow](../.github/workflows/android.yml). It publishes a pre-release named `Handy Android debug N` with `handy-android-debug-<sha>.apk` attached (open the repo's _Releases_ page from your phone). The same APK is also uploaded as a workflow artifact.
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

### 3. Allow restricted settings (Android 13+, sideloaded apps)

Android blocks accessibility services for apps that don't come from an app store. The switch in step 4 is greyed out with a "Restricted setting" dialog until you allow it:

1. Try to enable the service once (step 4); the dialog appears. On Android 15+ this first attempt is required, otherwise the menu entry below doesn't show.
2. Open **Settings › Apps › Handy** (or the _Open app info_ button in Handy).
3. Tap the **⋮** menu (top right) › **Allow restricted settings**, then confirm with your PIN or fingerprint.

### 4. Enable the service

**Settings › Accessibility › Installed apps (or Downloaded apps) › Handy dictation button › On.** The _Open accessibility settings_ button in Handy goes straight there.

### 5. Allow the microphone

Open Handy and tap **Allow microphone**. Optionally allow notifications, so the "Handy is listening" notification is visible while recording.

## Using it

- Focus a text field: the button fades in at the field's bottom-right corner, outside the field and never over the keyboard.
- **Tap** to start recording (light haptic). The ring pulses with your voice. Recording stops after the configured silence, or when you tap again.
- A thin spinner shows while transcribing, then a brief check, and the text appears at the cursor. On failure the button shakes and a toast explains why.
- **Drag vertically** to move the button. The offset is remembered per app.
- **Long-press** for _Hide in this app_ and _Settings_.

The button is hidden on password fields and other sensitive fields, in excluded apps, on the lock screen, when the keyboard is closed, while scrolling and when the window changes.

## How it works

| Area                         | Where                                                        | Notes                                                                                                                                                                                                                                                                                                              |
| ---------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Focus tracking               | `service/HandyAccessibilityService.kt`                       | Base events: `typeViewFocused \| typeViewTextSelectionChanged \| typeWindowStateChanged`. While an eligible field is focused, `typeViewScrolled \| typeWindowsChanged` are added at runtime (to hide on scroll and track the keyboard), then removed. All events are debounced (80 ms) into one `evaluate()` pass. |
| Sensitive fields             | `core/SensitiveFieldDetector.kt`                             | `isPassword`, password `inputType`s (text, web, number, visible), and keywords in the hint, view id and content description (password, mot de passe, pin, nip, otp, code, cvv, carte…), matched on whole tokens.                                                                                                   |
| Exclusions                   | `core/ExclusionMatcher.kt`, `settings/InstalledApps.kt`      | Always: Handy itself, `com.android.systemui` (status bar and lock screen), all home apps; also skipped while the keyguard is locked. On first run, installed banks, password managers and authenticators are added to the editable blacklist.                                                                      |
| Placement                    | `core/ButtonPlacer.kt`                                       | Candidates: right of the field, below, above, left. The first one on screen, off the keyboard (`TYPE_INPUT_METHOD` window bounds) and off the field wins.                                                                                                                                                          |
| Overlay                      | `overlay/OverlayController.kt`, `overlay/HandyButtonView.kt` | `TYPE_ACCESSIBILITY_OVERLAY` (no `SYSTEM_ALERT_WINDOW`), `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCH_MODAL \| FLAG_LAYOUT_NO_LIMITS`. Plain `View`, no Compose in the overlay. Animators run only while recording or processing.                                                                                         |
| Audio                        | `audio/AudioRecorder.kt`, `core/EnergyVad.kt`                | `AudioRecord`, 16 kHz mono PCM16, `VOICE_RECOGNITION` source, released as soon as recording stops. Energy VAD with an adaptive noise floor.                                                                                                                                                                        |
| Mic access in the background | `service/MicrophoneForegroundService.kt`                     | See below.                                                                                                                                                                                                                                                                                                         |
| Insertion                    | `service/TextInserter.kt`, `core/TextMerger.kt`              | `ACTION_SET_TEXT` with the text merged at `textSelectionStart/End` (ignoring the placeholder when `isShowingHintText`), then `ACTION_SET_SELECTION` after the insertion. Fallback: clipboard + `ACTION_PASTE`, then restore the previous clip.                                                                     |
| Transcription                | `transcription/`                                             | `Transcriber` + `PostProcessor` interfaces, `DictationPipeline` glue. Wired in `HandyAccessibilityService.onServiceConnected`.                                                                                                                                                                                     |

### Microphone from an accessibility service (Android 14+)

`RECORD_AUDIO` is a _while-in-use_ permission. An app with no visible activity is fed silence (Android 11+), and Android 14 requires a foreground service of type `microphone` to record from the background. Being an accessibility service doesn't exempt Handy from that. So each tap starts `MicrophoneForegroundService` (`foregroundServiceType="microphone"`, with a low-importance "Handy is listening" notification), records, and stops the service as soon as the mic is released. If the system refuses to start it (`ForegroundServiceStartNotAllowedException` or a `SecurityException`, logged under the `HandyMicFgs` tag), Handy records directly anyway. `AudioRecorder` then checks `AudioRecordingConfiguration.isClientSilenced()` and reports a blocked mic as an error instead of transcribing silence.

**Please confirm on your device** (manual test 7 below). This is the part most likely to differ between Android versions and OEMs.

### Battery

- No polling, sensors or wake locks; no `WorkManager`, alarms or broadcast receivers.
- While no editable field is focused the service only receives focus, cursor and window-state events, and does nothing with them beyond a single `findFocus` call after the debounce.
- The microphone and its foreground service only exist between a tap and the end of that dictation.

## Settings

Service status and shortcut, the restricted-settings walkthrough (Android 13+), microphone and notification permissions, button opacity (30–100 %) and size (32–52 dp) with a live preview (tap it to cycle the states), the silence delay (0.5–5 s), the excluded apps (picker with search, _Detect again_), and a **Test** screen with Compose, View and password fields.

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

Run with Gboard as the keyboard. For each item, also check that the button never covers the keyboard or the typed text.

1. **Handy › Test** screen
   - [ ] The button appears on _Single line_, _Multi-line_ and _Classic EditText_; never on _Password_.
   - [ ] On an empty field, the placeholder is replaced, not merged ("Bonjour depuis Handy…" only).
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

## What's next

To plug in real transcription (Whisper / Parakeet, like the desktop app):

1. **Pick the engine.** Either [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), which ships an Android AAR and runs Parakeet TDT, Whisper and Moonshine ONNX exports and is the closest match to `transcribe-rs` on desktop, or whisper.cpp through a small JNI wrapper (what `transcribe-cpp` wraps on desktop; GGML/GGUF models, Vulkan on recent devices).
2. **Model manager:** download, store and select models like `src-tauri/src/managers/model.rs` does (same URLs on `blob.handy.computer`), with progress UI in the settings and a check for free space.
3. **Implement `Transcriber`** (e.g. `SherpaTranscriber`), keep the loaded model in memory while the service runs (unload on `onTrimMemory`), and run inference on `Dispatchers.Default`.
4. **VAD:** replace `EnergyVad` with Silero (the same `silero_vad_v4.onnx` as desktop) for auto-stop and to trim silence before inference.
5. **Swap the pipeline** in `HandyAccessibilityService.onServiceConnected`.

To plug in Claude post-processing (your API key + custom prompt):

1. **`ClaudePostProcessor : PostProcessor`:** `POST https://api.anthropic.com/v1/messages` with `x-api-key`, `anthropic-version: 2023-06-01`, a Haiku model id, your custom prompt as `system` and the transcript as the user message. Return the text block, or the raw transcript on error or timeout (~5 s) so a dictation never gets lost.
2. **Settings:** API key (stored encrypted with an Android Keystore key, never logged, excluded from backups; `allowBackup` is already `false`), model, custom prompt with the desktop default, and an on/off toggle. Possibly a second gesture for "with post-processing", like desktop's `--toggle-post-process`.
3. **Manifest:** add the `INTERNET` permission (deliberately absent today: the app currently makes no network calls).
4. **Tests:** request building and response parsing with a fake HTTP layer.

Other follow-ups:

- Instrumented tests for `TextInserter` against View, Compose and WebView fields.
- A release signing config and R8 rules once real engines are added.
- The clipboard fallback can't restore the previous clip on Android 10+ when Handy can't read it (only the focused app or the IME may); the transcript then stays on the clipboard.
