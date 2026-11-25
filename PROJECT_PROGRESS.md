# EvenGlass — Live Japanese→Korean Camera Translation — Progress (2026-11-25)

## Goal
A Samsung Galaxy app that translates Japanese seen through the camera into Korean in real time, plus a presentation prompter. Later, show the translation on Even Realities G2 glasses.

## Previous version: 0.3.0 (native Kotlin + Google ML Kit)
- Package `kr.bsm.evenglass`, versionCode 4, minSdk 24 / targetSdk 34, ABIs arm64-v8a + armeabi-v7a.
- `MainActivity.kt`: CameraX preview + ImageAnalysis. Every 600 ms the latest frame goes to:
  - **ML Kit Japanese text recognition** (on-device, model bundled in the APK; handles vertical text).
  - **ML Kit on-device ja→ko translation** (no API key, no daily quota; ~30 MB model downloaded on first run).
  - Translation runs per text block, only when the recognized text changes (bigram similarity ≤ 0.85), with an LRU cache.
- Buttons: pause/resume, camera flip, photo (translate a gallery image), G2 preview (green-on-black panel), prompter.
- `PrompterActivity.kt`: the existing `assets/prompter.html` in a WebView; its "← 메인" button returns to the camera screen.
- APK is ~53 MB because the OCR model and native libraries are bundled.

## Current version: 0.4.0 (Gemini-assisted translation)
- versionCode 5; versionName 0.4.0.
- Settings dialog: password input, private SharedPreferences (backup disabled), editable model default gemini-flash-latest and connection test. No hard-coded key, URL query key or request logging.
- CameraX + ML Kit Japanese OCR every 600 ms, central 80% width / 70% height by block center; merge wrapped lines by paragraph.
- Gemini REST generateContent with x-goog-api-key, JSON original/korean, corrected original shown, 20-second total timeout. Live calls only for changed text, at least 1.5 seconds apart.
- No key or API/network failure: fall back to ML Kit Japanese-to-Korean; Korean status shows offline mode. First offline use requires model download.
- Precision capture and gallery: EXIF-corrected image, longest edge up to 2048px, JPEG quality 85, Gemini vision OCR + translation in reading order (including vertical Japanese). Pauses live analysis; offline photo fallback uses ML Kit.
- Real-time OCR text and selected/captured photos are sent to Gemini when configured; API usage may incur charges. No key is provided in repository or build.
- Build/device status: pending GitHub Actions and physical Galaxy testing for 0.4.0.

## History
- 0.1.0–0.1.1: apktool/smali WebView app with web camera; fixed a register bug and camera-permission flow.
- 0.2.0: live web OCR (Tesseract.js) + MyMemory translation; removed Gemini (model retired) and the study helper (separate app). Accuracy on real camera frames was poor.
- 0.3.0: rebuilt natively with ML Kit for accuracy (this was the original plan; the earlier environment blocked Android SDK/Maven, but GitHub Actions has them).

## Build and release
- `.github/workflows/build-apk.yml`: on push, Gradle 8.9 / AGP 8.5.2 / Kotlin 1.9.24 builds `assembleRelease`, then uber-apk-signer 1.3.0 debug-signs it. This is the same debug certificate as every earlier build (SHA-256 1E:08:A9:…:59:53), so new builds install over the old app.
- On `main`, `EvenGlass.apk` is published to a GitHub Release tagged `v<versionName>-build<run>`.
- If the Gradle step fails, the key error lines are posted as a job annotation (readable via the public check-runs API).

## How to install
- Fixed link to the latest build: https://github.com/bsmkorea1216sj/EvenGlass/releases/latest/download/EvenGlass.apk
- Allow "install unknown apps"; Play Protect may warn about a debug-signed app — choose install anyway.

## Status
- 0.4.0 source implementation; build and device verification pending.
- 0.3.0 build succeeded (v0.3.0-build5). **Not yet tested on a real Galaxy device** (camera, OCR accuracy, translation quality, speed).

## Next steps
1. Test 0.4.0 on Galaxy: Gemini connection, live accuracy/latency, precision capture, gallery, and offline fallback.
2. Optionally draw translations over the text on the camera image (Lens-style overlay).
3. Investigate G2 SDK only after the phone app is stable.
