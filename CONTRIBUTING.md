# Contributing to Stillroom

Thank you for wanting to help. Stillroom is a companion to an existing [Grocy](https://grocy.info/) server.

## Boundaries

Read [AGENTS.md](AGENTS.md) first. In short:

- Grocy remains the system of record. Do not fork Grocy or replace its web app.
- Do not copy GPL code from grocy-android or grocy-kmp.
- Do not add Home Assistant, Hermes, or a second backend.
- Never commit API keys, household barcodes, live pantry screenshots, or signing keys.

## Development

```sh
mise trust
mise install
mise run unit-test
mise exec -- ./gradlew --no-daemon assembleDebug
```

Java 21 and a project-local Android SDK 35 are installed by mise. What has been validated lives in [docs/STATUS.md](docs/STATUS.md).

## Pull requests

- Keep the change scoped to the problem.
- Match the surrounding Kotlin / Compose style.
- Add or extend tests when the behavior is checkable on the JVM.
- Record actual test commands you ran; do not claim phone or emulator results that did not run.
