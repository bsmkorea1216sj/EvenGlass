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

## Camera app: EvenGlass 0.5.1 (`app/` module)
- versionCode 7; versionName 0.5.1. Same as 0.4.0 functionally; the [대화 도우미] screen added in 0.5.0 was moved out to the separate app below. GeminiClient keeps the 0.5.0 refactor (shared `generateJson`).

## Separate app: EvenGlass 자동번역 0.5.0 (`autotranslate/` module)
- **0.5.0 (versionCode 5): answers grounded in my own data (personal data import).**
  - ⚙ 설정 → **[개인 데이터 가져오기]** reads a `persona_bundle.json` chosen on the phone and stores it only in app-private storage (`files/persona_user/`): `style.md`, `facts.jsonl` (fact cards "주제 | 내용"), `interview_pairs.jsonl`, optional `pairs.jsonl` (conversation). Imported files replace the bundled samples; **[개인 데이터 삭제]** goes back to samples. The settings show how many cards/answers are loaded.
  - Bundle format: `{"version":1, "style":"...", "interview_context":"...", "facts":[{"topic","text"}], "interview_pairs":[{"q","a","lang"}], "conversation_pairs":[...]}`. `interview_context` fills the 면접 정보 field.
  - **Personal data is never committed** (this repository is public). The owner's bundle was built from their faculty application PDF outside the repo, without phone/address/email/birth date/certificate number, and delivered to them directly.
  - Grounding: in 면접 mode all fact cards are sent (up to 80; character n-gram ranking missed paraphrased questions such as "연구 계획" in a test), in 회화 mode the top 3 matches. The prompt numbers cards F1..Fn and tells the model to use only card facts (Korean cards, answer in the interview language), and to return `evidence` card numbers. The panel shows **📎 근거: 카드 주제** under the answers, or "근거 카드 없음 · 일반적인 답". 🔮 previews also use the cards (questions an interviewer would ask from the application).
- **0.4.0 (versionCode 4, minSdk 26): top tabs 💬 회화 / 🎤 면접; 면접 uses Claude or ChatGPT and previews what comes next.**
  - Tabs switch the whole screen; 회화 works as before (Gemini). 면접 shows a bar with my role (**내가 답변자** / **내가 면접관**), a **면접 정보** field (e.g. "미국 대학 교수 임용 면접, AI 교육 분야") and **[🔮 예상 질문]**.
  - 면접 AI is chosen in ⚙ 설정: **Claude** (default, `claude-opus-5-5`, official Anthropic Java SDK `com.anthropic:anthropic-java:3.14.0`, effort low for speed, server-side refusal fallback `"default"` on models that support it), **ChatGPT** (OpenAI Chat Completions REST, default `gpt-5-mini`, `response_format: json_object`), or Gemini. Keys/models editable, stored in private prefs, connection test checks Gemini + the interview AI. No keys in code or repo.
  - [🔮 예상 질문]: from the interview info, retrieved interview examples and the conversation so far, shows 3 likely next turns. 답변자: next interviewer questions (text / 뜻 / 발음) + 💬 a short prepared answer (+ 뜻). 면접관: questions I could ask + the candidate's likely answer.
  - Each heard interviewer question (답변자) → 💡 question intent (Korean, one line), ① short conclusion-first answer, ② answer with grounds/experience (no invented facts), then 🔮 the 2 likely follow-up questions with prepared answers. As 면접관: candidate's answer → 💡 gist, Q① deeper follow-up, Q② verification question, 🔮 likely next candidate replies.
  - The last 8 interview lines (면접관/지원자/SJK) are sent as context so previews don't repeat questions. Reading a prepared answer aloud counts as my words (same containment rule).
  - Retrieval uses a separate interview store: `assets/persona/interview_pairs.jsonl` (15 **sample** Q&A pairs, ko/en/ja) → `persona_interview.db`.
  - history.jsonl records now carry `mode` (conversation / interview_answer / interview_ask), `tip_ko` and `next_preview`.
- **0.3.0 (versionCode 3): my quiet Korean → the other person's language with Hangul pronunciation.**
  - **[🇰🇷 한국어로 말하기]**: the recognizer switches to ko-KR for one utterance (starts listening if needed); the sentence is interpreted by Gemini (JSON `{text, ko_pron}`, polite/concise per style.md) into the 상대 언어 and shown at the top of the G2 panel as: target-language text / Hangul pronunciation / (my Korean). Shown for every target language, English included. Suggestions for the other person stay below in smaller text. No key or Gemini failure → ML Kit ko→target on-device translation (no pronunciation).
  - Korean typed in the input box (or heard while the 상대 언어 isn't Korean) is treated the same way.
  - When I then say the interpreted sentence aloud, it's recognized as my words (same ≥70% containment rule) and logged as my_reply.
  - Settings now say **상대 언어** (labels in Korean): 영어 (en-US, default) / 일본어 / 중국어 / **러시아어 (ru-RU, new)** / 한국어. It sets both the recognition language for the other person and the interpretation target. Language ID/script fallback and ML Kit translation cover Russian.
  - history.jsonl: `interpretations: [{ts, ko, text, ko_pron, lang}]` on the newest record.
- **0.2.0 (versionCode 2): tells my words apart from the other person's.** Only the other person's words get translation + suggestions; mine are logged as `my_reply` with no Gemini call. Decision per utterance:
  1. **[🙋 다음은 내 말]** pressed (manual, also switches the recognizer to the other person's language) → me.
  2. **Auto:** ≥ 70% of the utterance's character n-grams appear in a suggestion on screen (containment, i.e. I read all or part of ①/②) and the utterance has ≥ 6 characters → me. Tested on samples: reading a suggestion fully/partly → 0.77–1.0; the other person's follow-ups reusing some words → 0.21–0.46. Very short replies ("Yes") are never auto-assigned.
  3. Otherwise → the other person.
  - **[↺ 바꾸기]** flips the latest guess: other→me cancels the generation, restores the previous screen and records the text as my reply; me→other undoes the my_reply and generates suggestions.
  - Partial recognition text now goes to the status line instead of overwriting the other person's text.
  - History keeps the last two records in memory so late replies/corrections still apply; several my-reply chunks are joined; `my_reply_source` = manual / auto-match / corrected.
  - Not done (possible next steps): voice-based speaker identification (Android's SpeechRecognizer doesn't expose audio, would need own AudioRecord + a speaker-embedding model) and loudness-based detection (phone near my mouth → louder; needs calibration).
- 0.1.0: first separate release.
- Conversation auto-translation + reply suggestions is a **separate app** from the camera translator: module `:autotranslate`, applicationId `kr.bsm.evenglass.autotranslate`, label "EvenGlass 자동번역", versionCode 1 / 0.1.0, its own launcher icon (green speech bubble "EN"). Installs side by side with EvenGlass. It first shipped inside EvenGlass 0.5.0 as a [대화 도우미] screen; moved out in camera app 0.5.1 because the purposes differ.
- Default recognition language **English (en-US)**; language ID can still detect ja/zh/ko. Input is the **phone's microphone** via Android speech recognition (no G2).
- Its own ⚙ 설정: Gemini API key (password field, private prefs, FLAG_SECURE) + model + connection test, recognition language, [기록 내보내기]. The key must be entered separately from the camera app (separate app sandbox).
- Architecture: `docs/ARCHITECTURE_reply_assist.md` (from the 2026-11-25 mail, with an implementation table).
- Launch screen = the assistant itself (`MainActivity` in the module). No G2 connection yet; everything runs on the phone.
- Screen: top card = other person's words (live partial text while listening), detected language, Korean translation (ML Kit on-device translation shown first, replaced by Gemini's `translation_ko`). Bottom = G2-preview panel (black, green text) with 2 suggested replies; English → text / Korean meaning (2 lines), other languages → text / meaning / Hangul pronunciation (3 lines). Generation latency (ms, Gemini request start → display) in the panel's top-right corner; the status line also shows detect/search/generate/total ms.
- Input: Android `SpeechRecognizer`, continuous (restarted after every result/error), partial results on. When partial text stops changing for **1.2 s** the utterance is finalized and the recognizer restarted. Recognition language in ⚙ 설정: en-US (default) / ja-JP / zh-CN / ko-KR. Test text box for typed input. Mic permission requested on first [듣기 시작]; recognizer errors are shown in Korean on the status line and it auto-restarts where sensible. `<queries>` for `RecognitionService` added (Android 11+ visibility).
- Language ID: ML Kit `language-id:17.0.6`; if it returns `und`, fall back to script detection (Hangul/kana/Han/Latin), then the recognition language.
- Personal data (all on device): `assets/persona/pairs.jsonl` (30 **sample** pairs in a professor / lecturer / AI-education voice — to be replaced with real conversation data) and `assets/persona/style.md` (tone profile). On start the jsonl is imported into SQLite `persona.db` (re-imported automatically when the file's SHA-256 changes).
- **Retrieval — fallback used: character n-gram search instead of MediaPipe embeddings.** Top 5 by cosine similarity of character 2/3-gram TF-IDF vectors over the `q` column; when the speaker isn't Korean, the ML Kit on-device ko translation is searched too (best score per pair), so English/Japanese/Chinese speech still matches Korean examples. Why not MediaPipe Text Embedder: its published models (Universal Sentence Encoder, MobileBERT/BERT embedders) are English-only, there is no official small multilingual model, and a converted multilingual model (e.g. multilingual-e5-small → TFLite with MediaPipe metadata) could not be produced or verified in this environment. The `emb BLOB` column is reserved so a vector search can be added without a schema change (the offline prep and the phone must then use the same model).
- Generation: Gemini key/model from the app's ⚙ 설정 (no key in code or repo). REST `generateContent`, `responseMimeType: application/json`. Prompt = style.md + 5 retrieved examples + other's words + detected language + "당신은 SJK입니다. 위 예시의 말투로, 상대가 쓴 언어로 짧은 답변(1~2문장) 2개를 서로 다른 방향으로 만드세요." Output `{"lang","translation_ko","answers":[{"text","ko_meaning","ko_pron"}]}`; `ko_pron` forced empty for English. For `flash` models thinking is turned off (`thinkingBudget: 0`) for latency; if the model rejects it (HTTP 400) the call is retried once without it.
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
- Both modules are built and debug-signed; on `main`, `EvenGlass.apk` and `EvenGlass-AutoTranslate.apk` are published to a GitHub Release tagged `v<versionName>-build<run>`.
- If the Gradle step fails, the key error lines are posted as a job annotation (readable via the public check-runs API).

## How to install
- Fixed link to the latest camera app: https://github.com/bsmkorea1216sj/EvenGlass/releases/latest/download/EvenGlass.apk
- Fixed link to the latest EvenGlass 자동번역: https://github.com/bsmkorea1216sj/EvenGlass/releases/latest/download/EvenGlass-AutoTranslate.apk
- Allow "install unknown apps"; Play Protect may warn about a debug-signed app — choose install anyway.

## Status
- 자동번역 0.5.0 build: result recorded after push.
- 자동번역 0.4.0 build succeeded on the first try (Actions run 45667713990, release v0.5.1-build12; APK ≈ 45 MB with the Anthropic SDK). Not yet tested on a device.
- 자동번역 0.3.0 build succeeded (Actions run 45665096057, release v0.5.1-build11). Not yet tested on a device.
- 자동번역 0.2.0 build succeeded (Actions run 45663809086). Not yet tested on a device.
- Two-app build succeeded (Actions run 45662252734 → release v0.5.1-build9: EvenGlass.apk ≈ 53 MB, EvenGlass-AutoTranslate.apk ≈ 34 MB). Not yet tested on a device.
- 0.5.0 build succeeded on the first try (Actions run 45660726560 → release v0.5.0-build7, EvenGlass.apk ≈ 55 MB). Not yet tested on a real Galaxy device (speech recognition, latency, suggestion quality).
- 0.4.0 source implementation; build and device verification pending.
- 0.3.0 build succeeded (v0.3.0-build5). **Not yet tested on a real Galaxy device** (camera, OCR accuracy, translation quality, speed).

## Next steps
0. Reply assistant: test on Galaxy (recognizer endpointing with 1.2 s, latency per stage), replace `pairs.jsonl`/`style.md` with real data, then add a multilingual on-device embedder for hybrid (keyword + vector) search, pre-search on partial results, streaming display, answer cache, and G2 SDK output.
1. Test 0.4.0 on Galaxy: Gemini connection, live accuracy/latency, precision capture, gallery, and offline fallback.
2. Optionally draw translations over the text on the camera image (Lens-style overlay).
3. Investigate G2 SDK only after the phone app is stable.
