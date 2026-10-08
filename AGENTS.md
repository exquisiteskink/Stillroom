# Stillroom

- App name: Stillroom. Android package/application ID: `app.stillroom`.
- Use mise for tool installation, environment, and tasks. Java 21 is pinned in `mise.toml`.
- Implement only the stage authorized in `docs/STATUS.md`. Stop when its gate passes.
- The gate is `mise run stage:N`, where N is the authorized stage.
- Grocy stays authoritative. Use its official API; phone writes must appear in the official Grocy web app, and web writes must appear on the phone after sync. Do not fork Grocy or replace its web app.
- Child means a separate Grocy user and API key, never just a local profile.
- No Home Assistant or Hermes integration.
- No GPL copies. Do not copy code from `patzly/grocy-android` or `grocy-kmp`; their docs may inform workflows only. This project is MIT.
- Do not scaffold Laravel Homestead or copy grocy-android.
- Kotlin, Jetpack Compose, Material 3, minSdk 26. UI → ViewModel → use case → repository. Ktor and Room come later.
- Store future API keys using Keystore-backed storage. Separate future databases by account.
- Never commit real household data, API keys, credentials, or signing keys.
- Do not claim phone, emulator, integration, or CI tests that did not run. Record actual validation in `docs/STATUS.md`.
