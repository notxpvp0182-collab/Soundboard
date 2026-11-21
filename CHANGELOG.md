# Changelog

## [1.0.0] - Initial Production Release
### Added
- **Dark Glassmorphism UI**: Translucent glass cards, specular borders, and zero-GPU-blur static atmospheric lighting optimized for 1–2 GB RAM devices.
- **Hybrid Low-Latency Audio Engine**: Instant `SoundPool` triggering (16-slot LRU cache) for short SFX clips and disk-streamed `MediaPlayer` playback for longer tracks.
- **Movable & Resizable Floating Overlay**: System overlay (`TYPE_APPLICATION_OVERLAY`) with drag header, touch corner resize handle, automatic grid column calculation, minimize mode, and opacity control.
- **Honest Microphone Local Monitor & Diagnostics**: Real-time local microphone + soundboard headphone monitoring with feedback protection and transparent Android audio routing capability diagnostics.
- **Storage Access Framework Importer**: Persistable URI permission handling, magic-extension/MIME validation, categories, favorites, recent filter, and per-sound volume control.
- **100% Offline Privacy**: Zero `INTERNET` permission requested in `AndroidManifest.xml`.
