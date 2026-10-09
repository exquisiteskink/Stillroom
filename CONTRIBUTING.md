# Contributing to Stillroom

Thank you for wanting to help. Stillroom is a companion to an existing [Grocy](https://grocy.info/) server.

## Boundaries

Read [AGENTS.md](AGENTS.md) first. In short:

- Grocy remains the system of record. Do not fork Grocy or replace its web app.
- Do not copy GPL code from grocy-android or grocy-kmp.
- Do not add Home Assistant, Hermes, or a second backend.
- Never commit API keys, household barcodes, live pantry screenshots, or signing keys.

## Development

Requires [mise](https://mise.jdx.dev/), curl, unzip, and Linux x86_64:

```sh
mise trust
mise install
mise run unit-test
mise exec -- ./gradlew --no-daemon assembleDebug
```

mise pins Java 21 and downloads a project-local Android SDK 35 into ignored `.android-sdk/`. Android Studio is optional.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Keep signing keys out of the repository. What has been validated lives in [docs/STATUS.md](docs/STATUS.md). Stage gates (`mise run stage:0` … `stage:11`) and disposable Grocy fixtures are documented there. Architecture is UI → ViewModel → use case → repository.

## Pull requests

- Keep the change scoped to the problem.
- Match the surrounding Kotlin / Compose style.
- Add or extend tests when the behavior is checkable on the JVM.
- Record actual test commands you ran; do not claim phone or emulator results that did not run.
