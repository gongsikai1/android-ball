# AI Screenshot Floating Assistant

[English](README.en.md) | [简体中文](README.md)

An always-on-top Android floating ball: **tap to save the screenshot, double-tap to ask a typed question with the screenshot, triple-tap to translate the screenshot**. The ball is only 48dp, never takes input focus, and every touch outside its own window goes straight to the app underneath — video, chat and browsing keep working normally.

- Package: `com.aivision.floatassist`
- Version: 1.7
- Minimum: Android 8.0 (API 26), tuned for Android 14
- Stack: Java + Android Framework + MediaProjection + ML Kit OCR + OkHttp

## Features

| Gesture | Action |
| --- | --- |
| Single tap | Capture the screen, save it to the built-in "AI screenshot library", OCR runs in background. Only a lightweight toast appears. |
| Double tap | Capture, then open a panel with a text field; ask the AI anything about the screenshot. The field sits at the top of the panel so the keyboard never covers it. |
| Triple tap | Capture and translate: Chinese → English, any other language → Simplified Chinese. Only the translation is shown. |
| Quadruple tap | Start recording screen audio ("listen"); tap four times again to stop, transcribe and summarize what was heard. |
| Quintuple tap | Start "see + listen": a screenshot every 15s plus continuous screen audio; tap five times again to stop and summarize both on a timeline. |
| Long-press 2s | Toggle live bilingual subtitles fixed at the bottom: Chinese on top, English below. Long-press 2s again to close. |

Drag the ball to move it; it snaps to the nearest edge on release. Long-press for 0.5s opens a quick menu (open settings / stop assistant). Opacity is adjustable from 25% to 100%.

## First run

1. Install the APK (allow "install unknown apps").
2. Open the app, enter your DeepSeek API key (`sk-...`), tap "Save".
3. Tap "Test connection" to verify the key.
4. Tap "Grant overlay permission" and enable "Display over other apps" in system settings.
5. Tap "Start assistant" and accept the one-time "Screen capture" consent dialog.
6. You can leave the app; the floating ball stays on screen.

## Live bilingual subtitles

- Long-press the ball for 2 seconds to start, again to stop; the ball shows "译" while active.
- The subtitle window sits at the bottom with text centered horizontally, ignores touch, and never blocks the app underneath. **Chinese is always the first line, English the second.**
- By default it uses Model Studio **streaming ASR** `qwen3-asr-flash-realtime` over WebSocket: text appears as you speak, a sentence is finalized after 0.4s of silence, and draft latency is about 0.5–1s.
- Chinese, English, Japanese, other languages and dialects are all supported, and **every sentence is shown as Chinese + English**:
  - Chinese/dialect speech: Chinese transcript on top, English translation below;
  - English speech: English transcript below, Chinese translation on top;
  - Japanese and other languages: one bilingual call returns both Chinese and English, using the previous sentence as context.
- Until the translation arrives the source line shows "翻译中…", then the pair is completed.
- If the key, model or network is unavailable it automatically falls back to 1-second chunks with `qwen3-asr-flash`; subtitles keep running and a notice is shown.
- Streaming requires the Model Studio (Qwen) endpoint and key. A DeepSeek key can only use the fallback path, and DeepSeek has no ASR capability.
- Requires the Model Studio (Bailian/Qwen) endpoint for both ASR and translation; unavailable while the projection token is held by another recorder.

## Screen capture vs. the system recorder (Xiaomi / HyperOS)

The built-in recorder and third-party recorders also consume the `MediaProjection` capture channel. While one is running, this app gets no frames or a fully black image — shown as "capture failed" and black frames in the five-tap mode. It is a platform-level exclusivity, not a permission issue.

v1.3 handles it by:

- Trying `MediaProjection` first; if it returns nothing or an all-black frame, automatically retrying through accessibility screenshot (`AccessibilityService.takeScreenshot`), which uses an independent path.
- Switching to the fallback when the projection is revoked, with a clear in-app/notification message.
- Degrading five-tap "see + listen" to screen-only when the capture token is taken, so it still summarizes visuals.

Enable the fallback: tap "Enable accessibility screenshot (recorder fallback)" in the app, then turn on "AI screenshot fallback channel" in Accessibility settings. The service only captures when you tap the ball and never reads window content.

## Listen / See + Listen

- Audio comes from **AudioPlaybackCapture**: it records media played by the phone (video, podcasts, meetings) without the microphone permission, and requires Android 10+.
- Speech-to-text uses Qwen `qwen3-asr-flash` (OpenAI-compatible `input_audio`, base64 WAV), so listen/see-listen need the Model Studio (Bailian) endpoint and a Qwen key. DeepSeek has no ASR endpoint.
- The see-listen mode samples on a **15-second window**: one screenshot plus the transcript of the same window, up to 8 windows (2 minutes; hard cap 150s).
- On stop the audio is split into 15-second chunks, transcribed in parallel, and assembled into a timeline (`mm:ss image N, audio...`) that is sent together with the screenshots, so visuals and audio are aligned by position.
- A 2-minute video ≈ 8 screenshots + 8 transcript chunks (not every frame; fast scene changes can be missed).

## AI and DeepSeek

The official DeepSeek API (`deepseek-chat` / `deepseek-reasoner`) **accepts text only, not images**. The default pipeline is therefore:

```
screen capture → on-device ML Kit OCR (offline, Chinese + Latin) → text + question → DeepSeek → answer / translation
```

This keeps the official API key usable out of the box. If you want the model to actually *look* at the screenshot (pure images, layouts, product shots), put an **OpenAI-compatible vision model** name into the optional "Vision model" field and point the base URL to that service. The image is sent as base64 only when that field is filled.

Translation details:

- The source language is detected locally by counting CJK vs Latin characters.
- The prompt states the target language explicitly and forbids echoing the source or adding a side-by-side original.
- If the reply is identical to the source, or lacks the target language, the request is retried once.
- If it still fails, the UI says no translation was returned instead of showing the source as a result.

- Default base URL `https://api.deepseek.com`; proxies/relays are supported and `/chat/completions` is appended automatically.
- API key and base URL live only in local SharedPreferences; nothing is uploaded.
- Screenshots are stored in the app's private `files/shots` folder, capped at 40; view, copy text or long-press to delete in the "AI screenshot library".

## Why the floating ball does not get in the way

- The window is only 48×48dp with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`: it never takes input focus, and touches outside the window are forwarded to the app below.
- After dragging it snaps to the left or right screen edge.
- Translucent, with adjustable opacity.
- A panel appears only after an intentional double/triple tap; "Collapse / Close" restores the original screen immediately. The subtitle window ignores touch entirely.
- A low-importance notification stays in the status bar, required by Android for the foreground media-projection service. It never pops a banner.

## Known limitations

- Screen capture must be re-authorized every time the assistant is restarted (Android 14 requirement).
- Banking, payment and DRM-protected windows cannot be captured; you will get a black or empty image.
- The capture resolution is fixed when the assistant starts; rotating the screen or entering split-screen afterwards may crop the result — restart the assistant.
- A pure image without text, and without a vision model configured, can only be saved; the AI has no information to analyze.
- Some apps forbid capture (certain DRM videos, calls); the recording will be silent. Headphones do not prevent playback capture.
- The recorder/screenshot conflict is a platform limit. Accessibility capture bypasses it on most devices, but a few HyperOS builds and DRM-protected content may still produce black frames.
- See-listen samples every 15s with at most 8 screenshots, so fast scene changes can be missed; chunked transcription may cut sentences at 15-second boundaries.
- ML Kit OCR struggles with tiny fonts, stylized text and vertical writing. Rephrase the question or take a clearer screenshot.

## Build

Requirements: JDK 17, Android SDK 34, Gradle 8.7.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Dependencies:

- `com.google.mlkit:text-recognition-chinese:16.0.0` (offline OCR)
- `com.squareup.okhttp3:okhttp:4.12.0` (HTTP)

## Project layout

```
app/src/main/java/com/aivision/floatassist/
├── MainActivity.java      settings, permissions, launch entry
├── FloatingService.java   floating ball, gestures, result panel, quick menu (foreground service)
├── CaptureManager.java    MediaProjection + VirtualDisplay + ImageReader capture
├── OcrHelper.java         ML Kit on-device OCR
├── AiClient.java          DeepSeek / OpenAI-compatible client, ask + translate
├── ShotStore.java         screenshot library storage
├── GalleryActivity.java   screenshot library UI
├── Prefs.java             local settings
└── Ui.java                dp / screen helpers
```

## Changelog

- **1.7**: Fixed Chinese-top/English-bottom layout for any source language (Chinese, English, Japanese, other languages and dialects); non-Chinese/English speech is translated to both in one call.
- **1.6**: Live subtitles upgraded to Model Studio `qwen3-asr-flash-realtime` WebSocket streaming — text as you speak (about 0.5–1s drafts), sentence-level translation with context; automatic fallback to chunked mode.
- **1.5**: Faster subtitles — 1-second chunks, three parallel transcriptions, separate translation pool; bottom bar text now centered. Latency down from roughly 4–8s to 2–4s.
- **1.4**: Added long-press (2s) live bilingual subtitles with Chinese on top and English below, fixed at the bottom; 2.6-second chunks are transcribed and translated with roughly 4–8s latency.
- **1.3**: Fixed capture failures and black five-tap frames while the system recorder runs: added an accessibility screenshot fallback with automatic switching on empty/black frames, graceful degradation when the projection is revoked, and screen-only see-listen mode without a capture token.
- **1.2**: Added quadruple-tap listen and quintuple-tap see+listen; AudioPlaybackCapture for screen audio, `qwen3-asr-flash` chunked transcription, and a 15-second timeline that aligns screenshots with audio before summarizing. The ball shows a "听 / 看" state.
- **1.1**: Fixed English screenshots being returned untranslated (explicit target language + echo detection and one retry); panel title now shows the target language; clearer message when there is no text and no vision model.
- **1.0**: First release — tap to save, double-tap to ask, triple-tap to translate, draggable edge-snapping ball.
