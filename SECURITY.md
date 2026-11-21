# Security Architecture

- **No Arbitrary Execution**: Imported files are strictly validated via `AudioFileValidator` (MIME type, extension, size bounds, and `MediaMetadataRetriever` header verification) and are never executed.
- **Zero Hardcoded Secrets**: Keystores, passwords, and tokens are excluded via `.gitignore` and injected exclusively via GitHub Actions Secrets during CI/CD builds.
- **Minimal Attack Surface**: No WebViews, no third-party network SDKs, no reflection-heavy DI containers, and no background polling loops.
