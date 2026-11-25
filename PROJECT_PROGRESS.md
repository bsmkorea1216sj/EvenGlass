# EvenGlass — Live Japanese→Korean Camera Translation + Reply Assistant — Progress (2026-11-25)

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

## Current version: 0.5.0 (대화 답변 추천 프로토타입, phone only)
- versionCode 6; versionName 0.5.0. Architecture: `docs/ARCHITECTURE_reply_assist.md` (from the 2026-11-25 mail, with an implementation table).
- Main screen: new **[대화 도우미]** button → `ReplyAssistActivity`. No G2 connection yet; everything runs on the phone.
- Screen: top card = other person's words (live partial text while listening), detected language, Korean translation (ML Kit on-device translation shown first, replaced by Gemini's `translation_ko`). Bottom = G2-preview panel (black, green text) with 2 suggested replies; English → text / Korean meaning (2 lines), other languages → text / meaning / Hangul pronunciation (3 lines). Generation latency (ms, Gemini request start → display) in the panel's top-right corner; the status line also shows detect/search/generate/total ms.
- Input: Android `SpeechRecognizer`, continuous (restarted after every result/error), partial results on. When partial text stops changing for **1.2 s** the utterance is finalized and the recognizer restarted. Recognition language in ⚙ 설정: en-US / ja-JP / zh-CN / ko-KR. Test text box for typed input. Mic permission requested on first [듣기 시작]; recognizer errors are shown in Korean on the status line and it auto-restarts where sensible. `<queries>` for `RecognitionService` added (Android 11+ visibility).
- Language ID: ML Kit `language-id:17.0.6`; if it returns `und`, fall back to script detection (Hangul/kana/Han/Latin), then the recognition language.
- Personal data (all on device): `assets/persona/pairs.jsonl` (30 **sample** pairs in a professor / lecturer / AI-education voice — to be replaced with real conversation data) and `assets/persona/style.md` (tone profile). On start the jsonl is imported into SQLite `persona.db` (re-imported automatically when the file's SHA-256 changes).
- **Retrieval — fallback used: character n-gram search instead of MediaPipe embeddings.** Top 5 by cosine similarity of character 2/3-gram TF-IDF vectors over the `q` column; when the speaker isn't Korean, the ML Kit on-device ko translation is searched too (best score per pair), so English/Japanese/Chinese speech still matches Korean examples. Why not MediaPipe Text Embedder: its published models (Universal Sentence Encoder, MobileBERT/BERT embedders) are English-only, there is no official small multilingual model, and a converted multilingual model (e.g. multilingual-e5-small → TFLite with MediaPipe metadata) could not be produced or verified in this environment. The `emb BLOB` column is reserved so a vector search can be added without a schema change (the offline prep and the phone must then use the same model).
- Generation: same Gemini key/model as the main settings (no key in code or repo). REST `generateContent`, `responseMimeType: application/json`. Prompt = style.md + 5 retrieved examples + other's words + detected language + "당신은 SJK입니다. 위 예시의 말투로, 상대가 쓴 언어로 짧은 답변(1~2문장) 2개를 서로 다른 방향으로 만드세요." Output `{"lang","translation_ko","answers":[{"text","ko_meaning","ko_pron"}]}`; `ko_pron` forced empty for English. For `flash` models thinking is turned off (`thinkingBudget: 0`) for latency; if the model rejects it (HTTP 400) the call is retried once without it.
- Recycling log: `files/logs/history.jsonl` (app-private), one line per utterance: `ts, other, lang, translation_ko, suggestions[2], latency_ms, example_ids, my_reply, matched_suggestion (0/1/-1), match_score, error`. **[내 답 기록]** switches the recognizer to the other person's language and records the next utterance (or typed text) as SJK's actual reply; it's compared to the suggestions (n-gram similarity ≥ 0.6 = matched). A record is written when the reply is recorded, when the next utterance starts, on export or when the screen is closed. ⚙ 설정 → **[기록 내보내기]** shares the jsonl via FileProvider + share sheet.
- Privacy: recognized speech of the other person is sent to Gemini and kept in the local log — tell the other person / get consent (see architecture §7).

## Previous version: 0.4.0 (Gemini-assisted translation)
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
- 0.5.0 build succeeded on the first try (Actions run 45660726560 → release v0.5.0-build7, EvenGlass.apk ≈ 55 MB). Not yet tested on a real Galaxy device (speech recognition, latency, suggestion quality).
- 0.4.0 source implementation; build and device verification pending.
- 0.3.0 build succeeded (v0.3.0-build5). **Not yet tested on a real Galaxy device** (camera, OCR accuracy, translation quality, speed).

## Next steps
0. Reply assistant: test on Galaxy (recognizer endpointing with 1.2 s, latency per stage), replace `pairs.jsonl`/`style.md` with real data, then add a multilingual on-device embedder for hybrid (keyword + vector) search, pre-search on partial results, streaming display, answer cache, and G2 SDK output.
1. Test 0.4.0 on Galaxy: Gemini connection, live accuracy/latency, precision capture, gallery, and offline fallback.
2. Optionally draw translations over the text on the camera image (Lens-style overlay).
3. Investigate G2 SDK only after the phone app is stable.
