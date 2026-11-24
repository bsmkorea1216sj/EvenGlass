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

## Actual status
- Requirements gathered; existing HTML source located.
- No completed Android integration, APK build, or Galaxy installation test.
- No APK email has been sent.

## Next steps
1. Retrieve and inspect the full original `camera-study.html`.
2. Separate any API key from source and document safe configuration.
3. Create Android project and integrate the camera study mode.
4. Add OCR and translation mode.
5. Build and test an APK on a real Galaxy device.
6. Investigate G2 SDK only after phone app works.

This is a progress handoff, **not a completed application**.
