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
  - Japanese→Korean camera translation: Gemini vision if a key is set, otherwise Tesseract OCR + MyMemory. Includes G2 screen preview.
  - Study helper: solves a photographed problem with Gemini (newly written; original `camera-study.html` was not available).
  - Presentation prompter: `assets/prompter.html`.
  - API keys are entered in the app's settings screen and stored only in on-device localStorage. No keys in source.
- Fixed `MainActivity.onBackPressed` register count (`.registers 2` → `3`) so `p0` is not overwritten.
- GitHub Actions workflow `.github/workflows/build-apk.yml` builds the APK on every push (apktool 2.10.0 + uber-apk-signer 1.3.0 debug signing), uploads the `EvenGlass-apk` artifact, and publishes `EvenGlass.apk` to GitHub Releases on `main`.
- First Actions build succeeded (run 45317769726, artifact ~41 KB).
- **Not yet tested on a real Galaxy device.**

## How to install
1. Open https://github.com/bsmkorea1216sj/EvenGlass/releases/latest on the phone.
2. Download `EvenGlass.apk` and allow "install unknown apps" for the browser.
3. Debug-signed build: Play Protect may warn; choose install anyway.

## Next steps
1. Install on Galaxy; check camera permission, translation, and study helper.
2. If the original `camera-study.html` (made in Gemini/Codex/ChatGPT) is found, add it and replace the study helper with it.
3. Investigate G2 SDK only after the phone app is stable.
