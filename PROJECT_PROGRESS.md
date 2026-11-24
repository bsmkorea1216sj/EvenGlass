# EvenGlass — Live Japanese→Korean Camera Translation — Progress (2026-11-24)

## Goal
A Samsung Galaxy app that translates Japanese seen through the camera into Korean in real time, plus a presentation prompter. Later, show the translation on Even Realities G2 glasses.

## Current version: 0.3.0 (native Kotlin + Google ML Kit)
- Package `kr.bsm.evenglass`, versionCode 4, minSdk 24 / targetSdk 34, ABIs arm64-v8a + armeabi-v7a.
- `MainActivity.kt`: CameraX preview + ImageAnalysis. Every 600 ms the latest frame goes to:
  - **ML Kit Japanese text recognition** (on-device, model bundled in the APK; handles vertical text).
  - **ML Kit on-device ja→ko translation** (no API key, no daily quota; ~30 MB model downloaded on first run).
  - Translation runs per text block, only when the recognized text changes (bigram similarity ≤ 0.85), with an LRU cache.
- Buttons: pause/resume, camera flip, photo (translate a gallery image), G2 preview (green-on-black panel), prompter.
- `PrompterActivity.kt`: the existing `assets/prompter.html` in a WebView; its "← 메인" button returns to the camera screen.
- APK is ~53 MB because the OCR model and native libraries are bundled.

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
- 0.3.0 build succeeded (v0.3.0-build5). **Not yet tested on a real Galaxy device** (camera, OCR accuracy, translation quality, speed).

## Next steps
1. Test 0.3.0 on Galaxy: first-run model download, live translation accuracy and speed.
2. Optionally draw translations over the text on the camera image (Lens-style overlay).
3. Investigate G2 SDK only after the phone app is stable.
