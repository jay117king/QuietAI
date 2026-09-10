# Quiet AI

Offline-first, on-device AI assistant for Android.

Two Gradle product flavors:
- **offline** — zero network permissions
- **cloud** — free keyless cloud fallback (Pollinations)

## Key features
- On-device inference via MediaPipe GenAI (Gemma-compatible `.task` models)
- Optional free cloud engine
- “Keep warm” foreground service so the model stays loaded across Activity recreation
- Material Design chat bubbles
- Context window limited to last 10 messages

> **Note (2026):** MediaPipe LLM Inference API is in maintenance-only mode.  
> Google recommends migrating new projects to [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM).  
> The dependency used here still works for supported models.

## Requirements
- Android Studio Ladybug / Koala or newer
- JDK 17
- Real high-end device recommended (Pixel 8 / S23 class or better). Emulators usually fail for on-device LLM.
- A compatible MediaPipe `.task` model (e.g. Gemma-3 1B 4-bit)

## Build

```bash
# Offline flavor (no INTERNET permission)
./gradlew assembleOfflineDebug

# Cloud flavor
./gradlew assembleCloudDebug
```

APKs appear under `app/build/outputs/apk/`.

## First run
1. Install the APK.
2. Choose **On-device**.
3. Pick your `.task` model file (copy it to the phone first via USB / Files app).
4. Wait for “On-device ready”.
5. Optionally enable “Keep warm in background”.

## Project structure
```
app/
  src/
    main/          # shared code + offline manifest
    cloud/         # INTERNET permission only
  build.gradle.kts
```

## License
MIT (or whatever you prefer — update this file).
