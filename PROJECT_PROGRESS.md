# Galaxy Camera Study + Japanese Translation — Progress (2026-11-24)

## Goal
Build a Samsung Galaxy Android APK that retains the existing camera-based study/problem-solving tool and adds Japanese-to-Korean camera translation. Later, explore showing translated text on Even Realities G2 glasses.

## Existing asset
- `camera-study.html` is identified in the user's file library but has **not yet been added to this repository**.
- Earlier inspection indicates camera input and Gemini 2.5 Flash image-based study assistance.
- API credentials must not be committed to GitHub.

## Proposed implementation
- Android wrapper or native Kotlin/CameraX app.
- Japanese OCR: Google ML Kit Text Recognition v2.
- Japanese-to-Korean translation: ML Kit on-device Translation, subject to language model downloads and validation.
- Display translations on the Galaxy screen first.
- G2 integration is a separate future phase; SDK capability is not verified.

## Actual status (updated 2026-11-24)
- Android project added under `app/` (apktool format, WebView app):
  - Package `kr.bsm.evenglass`, version 0.1.0, minSdk 24 / targetSdk 34.
  - `MainActivity` requests camera permission and loads `assets/index.html`; `Chrome` grants WebView camera access.
  - **0.2.0: live Japanese→Korean translation, no capture button.** The camera frame is OCR'd every 0.8–3 s (Tesseract.js `jpn`/`jpn_vert`, chosen in settings) and translated with MyMemory (no API key). Translation is only requested when the recognized text changes (bigram similarity ≤ 0.8), with a cache, to save the free daily quota. Pause, camera flip, photo picker, and G2 preview remain.
  - Gemini removed (the `gemini-2.5-flash` model was retired and the user no longer wants Gemini here).
  - Study helper removed at the user's request; it lives in a separate app.
  - Presentation prompter: `assets/prompter.html`.
  - 0.1.1 camera fix: reload WebView after CAMERA permission result; `getUserMedia` retries with looser constraints; photo picker via `onShowFileChooser`.
  - PC test (2026-11-24, Chrome, canvas text): OCR + translation correct; ~2 s per changed frame, ~1.2 s per unchanged frame. Phone speed not measured.
- Fixed `MainActivity.onBackPressed` register count (`.registers 2` → `3`) so `p0` is not overwritten.
- GitHub Actions workflow `.github/workflows/build-apk.yml` builds the APK on every push (apktool 2.10.0 + uber-apk-signer 1.3.0 debug signing), uploads the `EvenGlass-apk` artifact, and publishes `EvenGlass.apk` to GitHub Releases on `main`.
- First Actions build succeeded (run 45317769726, artifact ~41 KB).
- **Not yet tested on a real Galaxy device.**

## How to install
1. Open https://github.com/bsmkorea1216sj/EvenGlass/releases/latest on the phone.
2. Download `EvenGlass.apk` and allow "install unknown apps" for the browser.
3. Debug-signed build: Play Protect may warn; choose install anyway.

## Next steps
1. Install 0.2.0 on Galaxy; check camera opens and live translation speed/accuracy.
2. If MyMemory's free quota is too small, consider an on-device translator.
3. Investigate G2 SDK only after the phone app is stable.
