# Decisions

## User-installed CA trust

The 2026-10-09 request authorizes HTTPS to Grocy servers whose certificates chain to a CA the user installed in Android. The app's network security configuration trusts the system CA store and every user-installed CA, including release builds. That trust is not limited to one Grocy host. Certificate chain, validity, and hostname checks stay enabled. There is no trust-all manager, permissive hostname verifier, or ignore-TLS option. Cleartext remains available only through the existing explicit HTTP opt-in; a TLS failure is not retried as HTTP. Redirects stay disabled, so an API key is not forwarded to another origin. This change does not add a certificate importer, certificate pinning, client-certificate authentication, or a way to permanently accept one server certificate.

## Post-Stage-11 maintenance

The 2026-10-07 review request authorizes defects and cleanup only, superseding historical stop statements for that scope. Existing passing behavior remains the baseline. Grocy shopping amounts are stock-unit values regardless of their display `qu_id`; recipe shortage calculations must not convert those stored values again. Unchanged fraction editors retain original decimals. Changing only the shopping display unit scales that original stock amount by the new factor over the original factor. A negative journal quantity is displayed with its sign; the fraction parser still accepts only non-negative input. An outbox result is recorded for the same operation while it is in-flight or needs-review, and a request that never returns is not sent again. Reactivation keeps the last verified grants when this login cannot read permissions and no administrator is available. A first connection stores no grants unless a permission read succeeds. Account changes recreate the shell's remembered feature content. Known authorization denial suppresses private offline cache fallback until account re-verification; live successful reads remain usable. No new backend or feature stage is authorized.

## Stage 0 scope

Stage 0 establishes a buildable native Android project and a JVM test gate. The launcher displays only the app name in a Compose Material 3 shell. No feature screens, networking, authentication, database, or synchronization are implemented.

## Authority and interoperability

Grocy is the system of record; Stillroom accompanies the existing official web app. Future repositories will use the official Grocy API against the user's existing server. Phone mutations must persist on that server and become visible in the web app. Synchronization must refresh server changes made in the web app. Room will be an account-scoped cache, never an independent authority. Bidirectional interoperability needs integration tests in a later authorized stage; Stage 0 does not prove it.

A child account is a separate Grocy user with its own API key. Future account identity must include server and user, with separate Room databases per account. API keys belong in Keystore-backed storage, outside Room, logs, source control, and backups. Account switching must not expose another account's cached data or credentials.

## Architecture and tools

Kotlin, Jetpack Compose, Material 3, minSdk 26, compile/target SDK 35. The shell demonstrates UI → ViewModel → use case → repository using local application identity only. Domain types have no Android dependency. Ktor and Room are deferred.

mise pins Temurin Java 21.0.12+101.0.LTS and sets project-local ANDROID_HOME. The SDK installer pins command-line tools 19.0 (archive 13114758), platform 35, and build tools 35.0.0, verifies Google's published archive checksum, and accepts the required SDK licenses. The Linux x86_64 bootstrap supports Omarchy and CI. Gradle 8.11.1, AGP 8.9.2, Kotlin/Compose compiler 2.1.20, and dependency versions are fixed. Android's [AGP compatibility table](https://developer.android.com/build/releases/agp-8-9-0-release-notes) documents the selected Gradle and SDK versions. Platform revisions and platform-tools are managed by sdkmanager; platform-tools has no historical version selector.

## Compatibility fixtures

Test-only Compose services pin Grocy 4.7.1 and the previous minor 4.6.0 by both version tag and multi-architecture image digest. Versions were checked against [official Grocy releases](https://github.com/grocy/grocy/releases) and [LinuxServer image tags](https://hub.docker.com/r/linuxserver/grocy/tags) on 2026-10-06. Each fixture uses an isolated Docker volume and loopback-only port. These are disposable fixtures, not household servers. No server data or API keys are seeded into this repo.

## Stage 3 connection boundary

Connection uses native `HttpURLConnection` with ordinary system TLS trust, no redirects, and explicit HTTP opt-in. API keys are checked using both system info and the current-user endpoint; errors do not expose credentials or response bodies. The cache identity hashes the canonical API base and verified Grocy user ID. Each account has a separate SQLite database and an AES-GCM credential file whose encryption key lives in Android Keystore. Credential files are excluded from backups. Ktor and Room remain deferred.

Cache access is bound to an active account lease; switching and logout close that lease and cancel its coroutine scope. Logout also cancels pending verification and removes only the selected account's database and credentials. Restored accounts must reverify before opening a cache.

Permission discovery lists only explicit server grants. Because the server requires ADMIN to read another user's grants, using a saved same-server administrator is an explicit connection-form choice. Missing verification yields an unavailable list, not inferred authority. Child UI restrictions do not replace the proxy required by Stage 1. Stage 3 contains no chores implementation or feature mutations.

## Licensing and exclusions

Original Stillroom source is MIT. No code was read or copied from patzly/grocy-android or grocy-kmp. No Grocy fork, replacement web app, Laravel Homestead, Home Assistant, or Hermes. Gradle's upstream wrapper retains its Apache-2.0 notices; dependency licenses remain their own.
