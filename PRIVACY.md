# Privacy Policy — Aura Soundboard

Aura Soundboard is engineered as a **100% offline-first utility**.

1. **Zero Network Access**: The application does **not** request the `android.permission.INTERNET` permission. No audio files, voice data, telemetry, or analytics can ever be transmitted off your device.
2. **Imported Audio**: Audio files selected via Android's system file picker remain on your device storage using persistable URI permissions or app-private local storage.
3. **Microphone Usage**: `RECORD_AUDIO` is optional and requested only if you explicitly toggle on **Live Microphone Monitoring**. Microphone PCM frames are routed strictly to your local headphone output buffer in RAM and are never saved to disk.
4. **Overlay Permission**: `SYSTEM_ALERT_WINDOW` is used solely to render the movable floating Soundboard panel when enabled by the user.
