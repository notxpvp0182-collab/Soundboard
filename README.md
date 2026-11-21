# Aura Soundboard — Ultra-Light Dark Glass Android Soundboard

A production-grade native Kotlin Android Soundboard & Floating Overlay application engineered specifically for **low-end Android devices (1–2 GB RAM)**.

## Key Features
- **Dark Glassmorphism UI**: Translucent glass surfaces, specular borders, and static radial atmospheric lighting (zero real-time GPU blur overhead).
- **Hybrid Audio Engine**:
  - Short SFX (`<= 5.5s`): Native `SoundPool` with a 16-slot LRU RAM cache for `<15ms` playback latency.
  - Long Audio Tracks (`> 5.5s`): Disk-streamed `MediaPlayer` instances to prevent `OutOfMemoryError` on 1 GB RAM phones.
- **Movable & Resizable Floating Overlay**: Works over other apps (`TYPE_APPLICATION_OVERLAY`), featuring touch drag, corner resize handle, auto-adapting grid columns, opacity control, and compact minimize mode.
- **Honest Microphone Local Monitor & Diagnostics**: Real-time local microphone + soundboard headphone monitoring (`AudioRecord` -> `AudioTrack`) with speaker feedback protection and transparent Android audio routing diagnostics.
- **100% Offline Privacy**: Does **not** request `android.permission.INTERNET`.

## Cloud Build via GitHub Actions
Every push to `main` or `master` automatically triggers `.github/workflows/android-ci.yml` to run:
1. Unit Tests & Android Lint (`gradle testDebugUnitTest lintDebug`)
2. Debug APK, Release APK, and Play Store AAB (`gradle assembleDebug assembleRelease bundleRelease`)
3. Artifact upload (`AuraSoundboard-Debug-APK`, `AuraSoundboard-Release-APK`, `AuraSoundboard-Release-AAB`)

### Optional Production Release Signing (GitHub Secrets)
To sign Release builds with your own production keystore, add these repository secrets in GitHub (`Settings -> Secrets and variables -> Actions`):
- `KEYSTORE_BASE64`: Base64-encoded string of your `.jks` or `.keystore` file (`base64 -w 0 release.jks`)
- `KEYSTORE_PASSWORD`: Your keystore password
- `KEY_ALIAS`: Your key alias
- `KEY_PASSWORD`: Your key password

*(If secrets are not configured, CI falls back to standard debug signing so your APK/AAB builds and installs immediately out-of-the-box.)*

### Creating an Official GitHub Release
Push a version tag (e.g., `v1.0.0`) or manually trigger the **Automated GitHub Release** workflow in the GitHub Actions tab to publish a release with the APK, AAB, and `CHANGELOG.md` notes attached.
