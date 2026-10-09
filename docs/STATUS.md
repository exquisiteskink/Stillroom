# Status

Authorized stage: **11 only**, per the user request on 2026-10-07. Stages 0 through 11 are complete.

Current scope: Stage 11 complete. The user authorized the existing-screen Android design/scanning redesign on 2026-10-07; no new Grocy capabilities are included. The user subsequently authorized defect review and cleanup on 2026-10-07, limited to already-started behavior. No further feature stage is authorized. See [defect review](REVIEW.md) for fixes, regression names, and remaining risks.

Current gate: `mise run stage:11` (existing local Grocy battery-cycle/unit-conversion parity, regressions, lint, and build).

## GitHub release 0.0.1 — 2026-10-07

Published signed `Stillroom-0.0.1.apk` as GitHub Release tag `v0.0.1` (`app.stillroom`, versionCode 1). `assembleRelease` produced the unsigned APK; it was zipaligned and signed privately with `CN=Stillroom` (certificate SHA-256 `35b617c8fd0a8bfa1aad1d63abd4f361629d031007c85a58a3b2df4f28f60d3b`). Signing keys stay outside the repository. Physical [PHONE_TEST.md](PHONE_TEST.md) rows remain **NOT RUN**.

## Android design and scanner redesign — 2026-10-07

The user explicitly authorized both the reviewed engineering specification and implementation of the Android redesign. Existing Grocy operations and repository safety boundaries remain the scope; no feature stage or backend was added.

Deliverables: [reconciled design system](design-review/DESIGN-SYSTEM.md), [inventory](design-review/INVENTORY.md), six linked specialist audits/copy deck, and [independent design critique with revision ledger](design-review/design-critic.md).

Implemented shared neutral light/dark tokens, one contrast-checked primary accent, explicit typography, consistent gutters and buttons, labeled form inputs and searchable selectors. Restaged Today, Pantry, Shop, Meals, Household, Records, Accounts, Settings, Pending changes and widget resources. Scanner now selects Add/Use before capture, resolves unambiguous codes/products automatically, opens a focused stock review, preserves camera session settings, and requires deliberate repeat. Exact-operation confirmation and unresolved creation/stock recovery remain explicit. App-bar and system Back share the local task contract; failed reads are distinct from successful empty states; reduced-motion loading is static.

Regression coverage includes scanner ambiguity/repeat/creation locks, operation-specific receipts, required scanner purchase dates, consume-only fields, uncertainty blocking, utility navigation history, named inputs/toggles, searchable ID-based selectors and contrast/dynamic-color isolation. Host form tests use stateless callbacks rather than depending on an unavailable Robolectric Android Keystore.

Validation in this pass:

- `mise run stage:11`: passed against the existing synthetic Grocy 4.7.1 fixture. The fixture containers were initially stopped and were started with the existing `grocy:up` task. Debug assembly, lint and Android test compilation passed. **122 app cases: 117 executed, 5 skipped, 0 failures/errors; 11 fraction tests passed.** Native battery/history/next-date, conversion, equipment custom-field and undo parity passed. Catalog line coverage **193/201 (96.02%)**. The five skips lacked their separate earlier-stage live fixture inputs.
- The first emulator pass reached 33 phone cases and found two stale shared-state test expectations. These were corrected from Try again/You're offline to Reload/Offline. A subsequent run stopped before assertions with `Process crashed`; the test runner now captures redacted crash-buffer output before cleanup. These failed attempts are not counted as passing device checks.
- Physical-phone results remain **NOT RUN**. Source/host tests do not establish CameraX hardware behavior, TalkBack conformance, launcher rendering, or reminder delivery. Final build/device results are recorded below after completion.
- Final `assembleDebug`, `assembleRelease`, `lintDebug`, `lintRelease` and `scannerCoverageVerification`: **passed**, 6m57s. Scanner scoped domain/repository line coverage **112/138 (81.16%)**; this does not measure camera or full-UI coverage. Debug lint has **0 errors, 34 warnings, 1 informational finding**; no lint baseline or suppression was added. The full host suite passed again with **122 cases, 116 executed, 6 skipped** because no live repository fixture inputs were supplied in that invocation. Release APK is unsigned; nothing was published.
- Isolated `run-android-tests.sh 3`: **passed, 59 API 35 instrumentation tests**, 0 failures. Phone 412×915dp/font 1.0: 33; tablet 840×1200dp/font 1.0: 13; phone/font 2.0: 13. Shared navigation/state/settings checks run in light and dark themes. Account/device security and UI scenarios used separate synthetic parent/child users on **Grocy 4.7.1 and 4.6.0**. Temporary keys/users and the owned emulator were removed. Reports: `app/build/reports/stage3/{phone,tablet,phone-large-text}.txt`. These tests cover shell/shared/account behavior, not every connected form or physical camera.
- `run-scan-instrumentation.sh`: **passed, 1 API 35 test** of the bundled ML Kit detector using the synthetic multi-barcode image, target choices and duplicate suppression. Wi-Fi/mobile data were disabled; no Grocy credentials were supplied. The owned emulator was removed. Reports: `app/build/reports/stage9/instrumentation.txt` and `fixture-scan.json`. This is fixture-image decoding, not a real camera/package scan.

Review and implementation are complete. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`; unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`. No deployment, Git commit or physical-device conformance claim was made.

Subsequent user-authorized phone installation: the redesigned debug APK installed successfully with `adb install -r` on the connected Samsung SM-F956U1, preserving app data. `am start -W` opened `app.stillroom/.MainActivity` successfully (cold launch, Status: ok). This confirms installation and launch only; the physical camera, TalkBack, widget and reminder procedures remain NOT RUN.

### Visual correction after phone feedback

The user rejected the flat neutral result and removal of Today photography. Their correction supersedes the audit's recommendations to remove the existing household imagery, clay surfaces and status color. Restored time-of-day Today photography and greeting, existing empty-state illustrations, warm cream/clay light surfaces and warm brown dark surfaces, amber/sage named status colors, brown section/action text, 24dp rounded cards/buttons and softer modal shapes. Shared widget colors follow the warm palette. Scanner/task/recovery behavior remains the already-reviewed implementation. The normative design specification was updated; specialist reports preserve the historical rejected recommendations.

Validation: debug assembly and lint passed in 1m48s; all **4 existing theme/contrast regressions passed** with 0 failures/errors/skips. The corrected debug APK installed with `adb install -r` on the connected SM-F956U1 and launched successfully (`Status: ok`, cold launch). Existing app data was preserved. No physical barcode, TalkBack or widget test is implied by installation/launch.

Visually inspected a screenshot of Today on that phone: restored dinner photography, warm cream/clay surfaces, brown section titles and rounded cards/navigation rendered. Screenshot stays in the ignored build directory; this narrow rendering check is not a camera or accessibility certification.

## Stage 0 — completed baseline

Scope: MIT native Android scaffold, build tooling, layered app-name shell, JVM unit tests, test-only Grocy fixtures, and CI. No feature screens or server communication.

Stage 0 gate: `mise run stage:0` (`assembleDebug` and `testDebugUnitTest`).

Stage 0: **complete**. Stopped at this stage.

Validation on 2026-10-06 (Omarchy, Linux x86_64):

- `mise install`: passed; Java 21 selected by mise.
- `mise run stage:0`: passed; `assembleDebug` and `testDebugUnitTest`, 41 tasks executed.
- JVM tests: 2 run, 0 failures, 0 errors, 0 skipped.
- `mise run android:install`: passed again after making platform-tools explicit.
- `docker compose config --quiet`: passed. Containers were not started.
- Gradle wrapper JAR SHA-256 matched the upstream published checksum.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Test report: `app/build/reports/tests/testDebugUnitTest/index.html`.

No phone, emulator, Grocy integration, or GitHub Actions run claimed. Bidirectional Grocy interoperability remains unimplemented and untested in Stage 0.

## Stage 1 — live API and access-control discovery

Stage 1: **complete**. Historical discovery; Stage 4 is now authorized.

**Child mode needs a proxy: YES, on Grocy 4.7.1 and 4.6.0.** Both accepted a separate child user/key with exactly `CHORES` and `CHORE_TRACK_EXECUTION` explicit grants, but child stock, shopping, recipes, master-data and other generic reads returned 200 with synthetic data. Most unrelated writes returned 403. `CHORES` also enabled chore undo (204), `done_by` could attribute execution to the parent (200), and new file upload/deletion succeeded (204). UI filtering is not access control. A later proxy must enforce the boundary and prevent direct upstream-key bypass. No proxy is implemented.

Deliverables: [feature matrix](FEATURE_MATRIX.md), [compatibility report](COMPATIBILITY.md), and [live-response manifest](evidence/stage1/manifest.json). Both docs cite the running servers' authenticated OpenAPI, permission hierarchy, feature flags, and request/response evidence. Neither endpoint existence nor this discovery implements an Android feature.

Validation on 2026-10-06 America/New_York (2026-10-07 UTC timestamps):

- Read `AGENTS.md` and this status; user authorized Stage 1 only.
- Initial `mise run grocy:up` was blocked by Docker socket access. After the user's access grant, the same task pulled and started both pinned fixtures successfully.
- Live `/system/info` verified Grocy 4.7.1 (DB 257) and 4.6.0 (DB 255); `/openapi/specification` and `/objects/permission_hierarchy` returned authenticated 200 JSON on both.
- Created separate synthetic parent-admin (user 2) and child (user 3) keys on both; verified key owners using `/user`. Final grants: parent `ADMIN`; child IDs 10 and 24. Bootstrap/superseded keys revoked; no credentials in repo.
- Exercised all exposed `/objects/*` listings, unrelated reads and denied writes, seven chore recurrence modes, attribution/date-only/skip/undo/assignment behavior, shopping row shape and fractional amounts, stock booking/transaction undo, and barcode lookup/actions.
- 4.6.0 shopping `add-product` truncated 2.5 to 2; 4.7.1 preserved 2.5. Generic row creation preserved 2.5 on both. `uihelper_shopping_list` is exposed only on 4.7.1.
- Stage 1 gate: `mise run stage:1` (documentation presence and archived evidence SHA-256 integrity; not HTTP replay). Gate result: **passed**.

No app screens or Android production code changed. No phone/emulator, Android sync, GitHub Actions, or household-server test ran. Stage 0 assembly/unit-test results above remain historical; they were not rerun for these documentation changes. Fixture containers remain running with synthetic data and keys in isolated Docker volumes.

## Stage 2 — navigable disconnected shell

Stage 2: **complete**. Gate: **passed**. Historical validation; Stage 4 is now authorized.

Implemented Today, Pantry, Shop, Meals, and Household; scanner/search entries; persisted visible-section settings; system/light/dark appearance and optional dynamic color; reduced motion; and a child landing preference, default off, that opens Household. Every destination's empty state says the account is not connected. Phone navigation uses a bottom bar, large text uses scrolling section chips, and tablets use a sidebar. Navigation has content descriptions and 48dp minimum targets. Shared loading, empty, error, offline, and permission-denied components are available. No login, camera capture, live data, or fake dashboard.

Validation on 2026-10-06 America/New_York (2026-10-07 UTC), Linux x86_64 with KVM:

- `mise run stage:2`: **passed**, exit 0. Debug app and instrumentation APK built; six JVM tests passed with zero failures/errors/skips.
- Real API 35 Compose instrumentation: **39 tests passed**, zero failures. Each configuration ran 12 shell scenarios covering fixed light and dark themes, plus one production Activity test:
  - Phone: 412 × 915dp, font scale 1.0 — 13 passed.
  - Tablet: 840 × 1200dp, font scale 1.0 — 13 passed.
  - Large text phone: 412 × 915dp, font scale 2.0 — 13 passed.
- Tests assert actual width class/font scale, destination navigation and content descriptions, minimum navigation targets, disconnected messages, search/scanner/back, visibility and child landing settings, theme colors, dynamic-color/reduced-motion preferences, shared states, and error retry. The production Activity test verifies recreation and saved child/theme/motion settings on a fresh Activity launch.
- The gate owned an isolated emulator and removed its temporary AVD on exit. No physical phone, live Grocy integration, or GitHub Actions run is claimed.

Gate recovery: the initial emulator attempt exceeded the RAM-backed `/tmp` capacity; temporary AVD files now live under the ignored build directory. Direct Activity recomposer installation stalled Compose tests; reduced-motion configuration now uses an isolated window recomposer factory and standard Activity `setContent`. The factory explicitly opts into a version-sensitive Compose API against the pinned BOM. An incorrect test assumption that recreation resets the current page was corrected; fresh launch separately verifies persisted preferences.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `app/build/reports/stage2/{phone,tablet,phone-large-text}.txt` and `app/build/reports/tests/testDebugUnitTest/index.html`.

## Stage 3 — connection and account isolation

Stage 3: **complete**. Gate: **passed**. Stopped at Stage 3.

Implemented base-URL/API-key connection through `/system/info` and `/user`, HTTPS by default, explicit insecure HTTP opt-in, and compatibility warnings for 4.6.0 and unknown versions. Each verified server/user identity has its own Keystore-backed AES-GCM credential record and physical SQLite cache. Switching closes the previous cache lease and cancels its work; logout also deletes that account's cache, encrypted record, and Keystore key. Pending verification cannot restore a logged-out account.

Child accounts open a restricted Household placeholder until chores exist. Only explicit server grants are listed. Grocy requires ADMIN to read `/users/{id}/permissions`, so the connection form offers explicit verification using a saved administrator on the same server. Without that verification, the placeholder explains that the permission list is unavailable; it does not infer grants from successful reads. This is not server access control: the Stage 1 proxy requirement remains unresolved. No proxy, chores, feature synchronization, or feature mutations were implemented.

Validation on 2026-10-06 America/New_York (2026-10-07 UTC), Linux x86_64 with KVM:

- `mise run grocy:up`: passed using the Docker helper's system privilege mechanism when the session could not access the socket. Both pinned fixtures responded.
- `mise run stage:3`: **passed**, exit 0; debug app and instrumentation APK built, **11 JVM tests passed**.
- Real API 35 instrumentation: **59 tests passed**, zero failures: phone 412 × 915dp / font 1.0 (33), tablet 840 × 1200dp / font 1.0 (13), and phone / font 2.0 (13). Shell checks cover both themes; account UI flows run in both themes on the phone.
- Live integration verified separate parent and child users/keys against **Grocy 4.7.1 and 4.6.0**. Both child `/users` calls returned **403**, `Permission missing: USERS_READ`, matching Stage 1. Child grant lists contained only `CHORES` and `CHORE_TRACK_EXECUTION`.
- Tests exercised encrypted credential reload and tamper rejection, separate databases and row isolation, closed cache leases, account-specific logout, cancellation of pending connection and late work, rejected keys, explicit insecure opt-in, redirect refusal, and socket cancellation. Unknown-version warning coverage substitutes only the version response; it does not claim a third server was tested.
- The gate revoked temporary fixture keys, removed temporary users, deleted credential inputs, and removed its isolated emulator. Existing Stage 1 fixtures remain. No physical phone, household server, or GitHub Actions run is claimed.

The initial gate failed four UI assertions that incorrectly required whole-string matches for partial messages. Corrected assertions and reran the complete gate successfully.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `app/build/reports/stage3/` (phone, tablet, large text, redacted fixture checks, and cleanup) and `app/build/reports/tests/testDebugUnitTest/index.html`.

## Stage 4 — pure Kotlin quantity fractions

Stage 4: **complete**. Gate: **passed**. Stopped at Stage 4.

Implemented the independent Kotlin/JVM `:fractions` module using immutable `BigDecimal` values. It parses slash/mixed/vulgar fractions and locale decimals, formats `0.5` as `½` and `1.25` as `1¼`, supports configurable maximum denominators, marks approximate values with `≈`, and falls back to unrounded decimals when a fraction exceeds conservative error bounds. Repeating input fractions carry approximation metadata, including on decimal fallback. Display conversion never replaces the stored number. Prices, barcodes, dates, and IDs are explicitly excluded from fraction parsing/display.

The only app consumer is a debug-only Compose design preview, with no production route or feature screen. Rules, API, precision limits, and storage boundaries are documented in [FRACTIONS.md](FRACTIONS.md).

Validation on 2026-10-07 America/New_York, Linux x86_64:

- Followed the everything-claude-code TDD/review/verification guidance. Initial API stubs failed all eight original tests; a later approximation-provenance test also failed before its implementation.
- `mise run stage:4`: **passed**, exit 0; debug APK and preview built, **11 fraction JVM tests and 11 existing app JVM tests passed**, zero failures/errors/skips. Gate completed in 162.91 seconds.
- Seeded properties exercised 6,000 exact round trips across US/German/Arabic locales, 2,000 arbitrary decimal/error-bound cases, zero/negative rejection, repeating denominators, and 100 large 256-bit whole values. Additional examples cover denominator limits, invalid inputs, excluded fields, and unchanged stored values.
- JaCoCo: **100% line coverage** (91/91), **98.6% instruction coverage** (1013/1027), and **98% branch coverage** (98/100). The 80% coverage gate passed.

No emulator, phone, live Grocy, household server, or GitHub Actions test ran in Stage 4. No production fractions, feature synchronization, or server mutations were added.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `fractions/build/reports/tests/test/index.html`, `fractions/build/reports/jacoco/test/html/index.html`, and `app/build/reports/tests/testDebugUnitTest/index.html`.


## Stage 5 — Room caches and durable outbox

Stage 5: **complete**. Gate: **passed**. Stopped at Stage 5.

Replaced each account's native SQLite cache with Room, preserving its physical database identity and migrating existing cached rows. Added durable UUID operation records with pending, in-flight, failed, needs-review, and confirmed states. Reads preserve the last valid JSON response with an explicit stale flag on failure. Mutations commit their in-flight claim before dispatch and disable transport retries/redirects. Unknown outcomes, malformed acknowledgements, cancellation, and 5xx require review; none are replayed. Reopening recovers interrupted in-flight rows into needs-review. Pending work resumes only in its owning account session; switching cancels that session and closes its lease.

Settings → Pending changes in the debug app shows the active account's operation IDs and states, with refresh and read-only reconciliation. Fresh server observations may confirm an explicitly supplied complete expected representation; mismatches, unavailable reads, and operations without a safe expected representation remain needs-review. No production feature screens or mutation replay controls were added. Behavior and limits are documented in [SYNC.md](SYNC.md).

Validation on 2026-10-07 America/New_York, Linux x86_64:

- Read AGENTS.md and STATUS.md and followed everything-claude-code testing, security, and verification guidance. Initial tests failed compilation against the absent Stage 5 API before implementation.
- `mise run stage:5`: **passed**, exit 0. Debug APK assembled; **24 JVM tests passed**, zero failures/errors/skips, including **13 Stage 5 MockWebServer/Room tests** and 11 existing regressions.
- Tests cover timeout after a possible apply, 500 before apply, 401, malformed JSON, last-good cache fallback, file-backed database reopen, interrupted-request recovery, cancellation, account-separated queues/closed leases, concurrent drain claims, read-only reconciliation, request validation, and migration from the Stage 3 schema. Assertions establish one mutation dispatch/apply and no mutation replay after ambiguous outcomes.
- JaCoCo for the new cache/outbox repository and transport implementations: **96.52% line coverage** (111/115), **85.84% instruction coverage** (1164/1356), **73.28% branch coverage** (85/116). The 80% line coverage gate passed. This is targeted synchronization implementation coverage, not whole-app or generated Room/UI coverage.
- Android lint: **0 errors**, 18 warnings (dependency versions, KAPT preference, existing app icon/backup-rule suggestions, and KTX suggestions).
- The first timeout reconciliation run exposed localhost IPv6 fallback against an IPv4-only MockWebServer; fixtures now bind/use explicit IPv4 loopback. Coverage initially skipped due to the wrong execution-data path; corrected it to Android's output location and required the XML report. The final gate completed in 53.41 seconds, reusing the passing JVM/build outputs from the preceding 106.41-second run and executing coverage verification and lint.

No phone, emulator, live Grocy, household server, Android process-kill, debug-screen UI automation, or GitHub Actions validation is claimed. Durability was verified by closing/reopening actual file-backed Room databases, with cancellation representing interrupted transport work. Production feature interoperability remains future work.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/jacoco/syncCoverageReport/html/index.html`, and `app/build/reports/lint-results-debug.html`.


## Stage 6 — Grocy stock

Stage 6: **complete**. Gate: **passed**. Stopped at Stage 6.

Implemented Pantry stock overview, product/name and exact Grocy-barcode search, in-stock/opened/due/overdue/expired/missing/location filters, product detail, stock entries and locations, journal, and price history. Reads use the account-separated Room cache and explicit cached/offline state. Refresh observes server changes. Purchase, consume, open, transfer, inventory, spoilage, and supported booking/transaction undo use the durable outbox. Pending and confirmed operation records remain separate from confirmed server balances; ambiguous writes are never replayed. Transaction undo handles multi-entry transfers together.

Quantity input/display uses the fractions module; conversion and numeric JSON use decimal values and Grocy's resolved quantity-unit factors. Prices are explicitly entered per stock unit using decimals. Tare and disable-open settings are respected. Barcode matching uses only Grocy's persisted barcode records, with manual entry and no camera or external lookup. Stock requires verified explicit ADMIN/STOCK access; denied or unverified child access never loads or displays stock. Fresh 401/403 responses deny access instead of returning last-good cache contents. Stage 1's server/proxy authorization limitation remains unresolved; no proxy or other feature stage was implemented. Details: [stock behavior](STOCK.md).

Validation on 2026-10-07 America/New_York, Linux x86_64:

- Read AGENTS.md and STATUS.md; user authorized Stage 6 only. Applied everything-claude-code planning, TDD, security review, code review, documentation, and verification guidance.
- Initial stock tests failed compilation against the missing API before implementation. Transaction-undo tests also failed against the absent method before its implementation.
- `mise run stage:6`: **passed**, exit 0, final gate completed in **133.73 seconds**. Debug APK assembled, Android lint completed, all app/fraction JVM tests ran, targeted stock coverage verification passed, and both live container tests ran without skips.
- **30 app JVM tests and 11 fraction JVM tests passed**, zero failures/errors/skips. Stage 6 adds three stock policy/payload tests and three Room/MockWebServer/live-container integration tests. Existing account, shell, and outbox regressions passed; cache-denial expectations were updated for the intentional 401/403 behavior.
- Production HTTP/cache/outbox repositories with actual Room databases tested against **Grocy 4.7.1 and 4.6.0**. Each test created a synthetic product, purchase-unit conversion and barcode, purchased **1.25 packs × 4 = 5 stock units**, consumed **1.5 units**, and confirmed **3.5 units** plus the matching purchase/consume journal through independent official Grocy API reads. Open, transfer, inventory, spoilage, booking undo, and transaction undo were also confirmed. Transfer transaction undo restored the source stock and removed destination stock. Denied child grants exposed no stock.
- Room/MockWebServer tests verify cached reads, fresh denial over last-good data, no denied-child reads or mutations, all stock payloads and routes, pending/confirmed states, undo paths, invalid transaction IDs, numeric quantity conversions, and refusal of external barcode lookup routes. Existing Stage 5 tests retain unknown-outcome/no-replay and account-isolation coverage.
- Targeted stock domain/use-case/repository JaCoCo coverage: **98.28% line** (57/58), **92.04% instruction** (798/867), **85.71% branch** (96/112). The 80% line gate passed. This is not whole-app or Compose/ViewModel coverage.
- Android lint: **0 errors**, **18 warnings** (existing dependency/tooling/icon/backup/KTX suggestions).
- Live testing exposed Grocy's rejection of `application/json; charset=utf-8`. Mutation transport now sends UTF-8 bytes with exactly `application/json`; request-header assertions cover this fix. Fixture setup also accounts for Grocy's automatically created purchase-unit conversion. Temporary synthetic objects, API keys, users, private fixture files, and test databases were cleaned up.

No physical phone, emulator, browser UI automation, household-server, or GitHub Actions test ran in Stage 6. Container checks compare the authoritative API data used by the official web app; they do not claim visual/browser or Android UI parity tests.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `app/build/reports/stage6/container.json`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/jacoco/stockCoverageReport/html/index.html`, and `app/build/reports/lint-results-debug.html`.


## Stage 7 — Grocy shopping lists

Stage 7: **complete within the participating-client concurrency boundary below**. Gate: **passed**. Stopped at Stage 7.

Implemented Shop with multiple lists and list creation, product/note-only rows, quantity-unit-aware fractional input/display, notes, completion, edit/delete, store/category grouping, estimated totals from known last purchase prices, explicit unknown-price counts, queued-edit previews, and in-store item controls at least 64dp high. Purchase-from-list opens a review screen for actual quantity, stock unit, price, due date, location, store and note. It uses the Stage 6 purchase repository and marks the shopping row completed only after purchase confirmation. Server data remains authoritative and visible to the official Grocy web app.

Room version 3 migrates prior caches/outbox records, retains HTTP acknowledgement payloads, and adds a durable shopping intent outbox. Fresh row snapshots guard replay; changed/deleted rows open conflict review without applying local edits. Conflict review preserves both versions and supports keeping server state or explicitly reviewing a merged edit. Unsent transport writes remain `guarded` across restarts; generic outbox drains cannot bypass shopping's fresh guard. Unknown dispatched outcomes are not replayed. Known claim acknowledgements and absent dispatch records recover interrupted preparation safely. Account changes cancel work and reset shopping forms.

Two participating clients coordinate through unique negative-ID claim objects in Grocy's standard custom-object API, under a hidden `stillroom_shopping_sync_v1` user entity. Temporary row claims serialize edits/checkout; separate permanent purchase receipts prevent another client's repeat purchase while allowing completed-item edits/deletion. Claim/receipt writes use the transport outbox. This requires MASTER_DATA_EDIT (included in ADMIN) in addition to shopping permission; a denied claim never falls back to an unsafe shopping write. No server fork, proxy, new external service, or later feature stage was implemented. Details: [shopping behavior and concurrency boundary](SHOPPING.md).

**Material concurrency limit:** Grocy has no conditional-write API, and direct web/API writers do not participate in Stillroom's claims. The tests establish no silent loss/no duplicate checkout between participating Stillroom clients, detect stale external changes before replay, and preserve external changes observed during checkout. An uncoordinated web/API writer inside the final GET→PUT window cannot receive an atomic no-overwrite guarantee from this client. That broader guarantee remains unavailable without server support or coordination of all writers. Ambiguous claim ownership remains in review; there is no automatic lock expiry or unsafe purchase retry.

Validation on 2026-10-07 America/New_York, Linux x86_64:

- Read AGENTS.md and STATUS.md. Applied everything-claude-code TDD, review, security, documentation, and verification guidance. Initial tests failed compilation against the missing shopping API. The guarded-replay regression also failed against the absent guard API before its implementation.
- `mise run stage:7`: **passed**, exit 0. Final gate completed in **152.34 seconds**, rebuilding the debug APK, lint, all app/fraction JVM tests, and targeted shopping coverage. Earlier passing verification was strengthened after review found that a queued-but-unsent transport write needed its own persistent guard.
- **44 app JVM tests and 11 fraction JVM tests passed**, zero failures/errors/skips. Stage 7 adds four shopping domain tests, nine shopping integration tests, and a Stage 6-to-7 database migration regression. Existing Stage 6 live stock tests also ran against both containers.
- Actual **Grocy 4.7.1 and 4.6.0** container checks passed list creation, fractional add/edit, note-only deletion, offline edit replay, stale-edit conflict preservation, and two-client purchase-from-list. One reviewed **2.5-unit** purchase reached stock and produced exactly **one purchase journal entry**; the other client opened conflict review. Repeated drains did not purchase again. Independent official API reads verified stock quantities, notes, list completion and journal entries.
- A forwarding test proxy let each actual container apply a second legitimate **1.5-unit** purchase, then dropped the acknowledgement. After closing/reopening Room, read-only journal reconciliation completed that purchase without a second POST. Another client's checkout was blocked. Final stock was **4 units**, with exactly two legitimate purchase journal entries across the two different rows. Response loss was injected by the proxy; the stock and journal were from the actual containers.
- Room/MockWebServer tests cover persisted/consolidated offline edits, simultaneous edit/checkout clients, lost purchase responses, server changes during checkout, denied shopping access, acknowledged claims interrupted before item dispatch, permanent receipts with later edits, and guarded transport writes after database reopen. A generic drain could not overwrite a row changed while the process was stopped. Domain tests cover numeric quantities, grouping, server unit factors, queued projection, known-price totals and unknown-price exclusions. Migration retains a Stage 6 pending operation.
- Targeted shopping domain/use-case/repository/synchronizer coverage: **91.32% line** (242/265), **83.69% instruction** (3673/4389), **66.60% branch** (321/482). The 80% line gate passed. This is not whole-app or Compose/ViewModel coverage.
- Android lint: **0 errors**, **18 warnings** (existing dependency/tooling/icon/backup/KTX suggestions).
- Synthetic fixture lists, products, units, locations, claim/receipt objects, temporary keys/users, private credential files and test databases were cleaned up. Stage 1 synthetic data remained intact.

No physical phone, emulator, browser UI automation, household-server, or GitHub Actions test ran in Stage 7. In-store sizing is implemented in Compose and built/linted; no device UI assertion is claimed.

## Stage 8 — Grocy chores and tasks

Stage 8: **complete**. Implemented only the authorized stage; stopped after the gate passed.

Household now reads Grocy's chores, instructions, current due dates and assignments, execution history, open tasks, and task categories. Parents can create/edit/delete chores, configure the seven Grocy recurrence types, choose assignment rotation and users, reassign the next execution, and review completed/skipped/undone history. Children see only their active assigned chores, grouped overdue, today, and upcoming, with instructions and a large Complete control. Tasks are listed by category and can be completed when the active key has the required grants. No points, rewards, approval, subtasks, or companion backend were added.

Chore completion uses the active account's own key and verified user ID as the request's `done_by`. A fresh Grocy detail response checks the child's current assignment before accepting completion. The next due date comes from refreshed Grocy responses; no recurrence is calculated locally. The existing Stage 1 upstream permission limitations remain: these application restrictions do not isolate an API key on the server, and no proxy was added.

Reads use the account cache, and writes use its durable outbox. Pending, confirmed, failed, and review states are shown in plain language. Unknown mutation outcomes are never automatically replayed. Child completion requires an online assignment check; cached chores remain readable offline. Confirmed chore saves schedule Grocy's official calculate-next-assignments call with a deterministic outbox identity, including recovery after reopening. Account switches cancel work, clear feature state/forms, and reject stale work through the existing account lease.

Actual validation on 2026-10-07:

- `mise run stage:8`: **passed**, including the final status-label and account-switch guard changes.
- Used only the Grocy **4.7.1 instance already listening at http://127.0.0.1:9283**. No container was started, and nothing was deployed.
- Live native repository test created and assigned a synthetic daily chore, completed it using the separate child key, and verified one Grocy execution log with **child user 22 = done_by_user_id 22**. The refreshed next due date matched `GET /chores/{id}` exactly: `2026-10-09 09:00:00`.
- The same local test verified parent create/edit/delete, recurrence interval, instructions, server-calculated assignment, next-execution reassignment removing the chore from My Chores, history, task-category grouping, and task completion in Grocy's own task record.
- **50 app JVM cases: 48 passed, 2 skipped, 0 failures/errors.** The skips were the previous Stage 6/7 two-container live scenarios, whose credentials were intentionally not supplied. All six Stage 8 domain/integration cases ran. **11 fraction tests passed.**
- Household domain/repository coverage: **97.50% lines, 88.63% instructions, 75.33% branches**; the 80% line-coverage gate passed.
- Debug APK built; Android test sources compiled; lint reported **0 errors and 18 warnings**. No physical phone, emulator, browser UI automation, second Grocy version, or deployment test ran for Stage 8.
- MockWebServer tests verified child write/history/task denial, explicit child-key and attribution binding, reassignment rejection, filtered cached reads, revoked-read denial without cache fallback, uncertain completion retention, and no automatic resend.
- Synthetic chores, logs, tasks, category, temporary child user, temporary API keys, and private credential files were removed. Key-free evidence is in `app/build/reports/stage8/local-parity.json`; JVM, coverage, and lint reports are under `app/build/reports/`.

No next stage is authorized.


APK: `app/build/outputs/apk/debug/app-debug.apk`.
Reports: `app/build/reports/stage7/container.json`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/jacoco/shoppingCoverageReport/html/index.html`, and `app/build/reports/lint-results-debug.html`.

## Stage 9 — CameraX scanner and reviewed lookup

Stage 9: **complete**. Implemented only Stage 9 and stopped after its gate passed.

Added CameraX 1.5.3 with bundled ML Kit barcode scanning 17.3.0. The scanner offers explicit camera permission, torch, linear zoom, a view-coordinate target region, multi-code selection, and manual barcode/UPC-E/QR entry. EAN/UPC checksums are checked, raw leading zeros stay intact, and selected reads remain suppressed until Scan another identical package is chosen. Account changes cancel work and clear scanner/review state.

Lookup checks Grocy barcode records first, then an enabled/supported Grocy external plugin with `add=false`, then direct Open Food Facts for validated grocery codes. QR/household text stays within Grocy/manual review. Public requests contain only the validated grocery code, fixed requested fields, and User-Agent; no Grocy credentials, images, household records, or location are sent. Frames are decoded on-device. No lookup backend was added. See [scanning behavior and primary references](SCANNING.md).

Product suggestions require review before creation, including an explicit existing Grocy stock unit and location. Provider unit/location IDs are ignored. Reviewed creation and barcode attachment use the account outbox; unknown outcomes are retained without automatic replay. No stock unit or due date is invented. Known/confirmed-created products open the existing Stage 6 stock flow for purchase/consume and supported journal undo, preserving its quantity conversions and fraction input/display. Scanner purchase requires a date entered from the package; scanning never books stock automatically.

Actual validation on 2026-10-07:

- `mise run stage:9`: **passed** on the final code, including debug/test APK builds, lint, JVM regressions, scanner coverage, existing-local-Grocy parity, and instrumented fixture scanning.
- **Real instrumented bundled-ML-Kit decode on an isolated API 35 x86_64 emulator: 1 test passed.** Decoded EAN-8 **00123457** with leading zeros and QR **STILLROOM-FIXTURE-QR** from the synthetic PNG. Verified target filtering, multiple choices, duplicate suppression, explicit identical-package reset, and repeated decoding. Wi-Fi/mobile data were disabled during this fixture test. No Grocy key was transferred to the emulator.
- Used only the **already-running local Grocy 4.7.1 at http://127.0.0.1:9283**. The native repository test reviewed/created a synthetic product with an explicit existing test unit/location, attached and resolved its barcode, purchased 2, consumed 1, and verified Grocy stock/journal records. Undoing consume restored 2; undoing purchase restored 0. No container was started.
- **56 app JVM cases: 53 passed, 3 skipped, 0 failures/errors.** All six new scanner domain/integration cases ran. The skips were previous Stage 6/7/8 live scenarios, whose fixture credentials were intentionally not supplied. **11 fraction tests passed** in this Stage 9 validation; the final incremental gate reused that unchanged result.
- Scanner domain/repository/public-client coverage: **90.51% lines, 79.01% instructions, 52.82% branches**; the 80% line-coverage gate passed.
- Lint: **0 errors, 22 warnings**. Debug APK and instrumentation APK built. A formatting lint error found during validation was fixed; no lint baseline/suppression was added.
- MockWebServer tests verified Grocy-first lookup, enabled-plugin lookup with `add=false`, disabled-plugin fallback, public GET/header/body isolation, QR privacy, 403 stopping fallback, child scanner/creation denial, explicit existing-unit validation, no due-date fields from lookup, uncertain creation retention, and no automatic resend.
- Real Grocy external-plugin/Open Food Facts network lookup was **not** exercised; those paths were checked with MockWebServer. Torch, zoom, live CameraX frame mapping, and permission-denial UI were implemented and built/linted, **not physically tested**.
- **No physical-phone scan test ran or is claimed.** This gate is an instrumented scan of a fixture image, not a live-camera hardware test. No lookup backend or production deployment was added.
- Synthetic product, barcode, stock/journal rows, test unit/location, temporary API keys/private credential files, and the owned emulator were removed. Cleanup uses Grocy's supported product-delete cascade; direct stock-log deletion was found unsupported and removed from the test.

Evidence: `app/build/reports/stage9/instrumentation.txt`, `fixture-scan.json`, `local-parity.json`, the JVM reports, `app/build/reports/jacoco/scannerCoverageReport/`, and the lint report. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Stage 9 is complete. No later stage is authorized.


## Stage 10 — recipes, meal plan, and reviewed cooking

Stage 10: **complete**. Implemented only Stage 10 and stopped after the final gate passed.

Meals now uses Grocy's native recipes, ingredient positions, resolved quantities, descriptions/instructions, picture filenames/files, meal entries, sections, and servings. Create, edit, and delete operate through the account outbox. Native generated day/week/shadow recipes are excluded from editing. Recipe-only grants can reach Meals; chore-only/unknown keys cannot read recipe records through this feature. Stock and shopping access remain separately gated.

Scaling saves desired servings while leaving stored base ingredient amounts unchanged. Fraction formatting/input stays in the fraction module; unchanged ingredient input saves its original decimal. Stock labels use actual stock units. Missing quantities honor Grocy fulfillment flags and server conversions, deduct existing outstanding amounts on the chosen list, and use the existing shopping outbox. Consumption opens a review, aggregates effective products in stock units, rechecks current records/availability, and uses the existing consume path with native recipe attribution. Stable review operation IDs prevent repeated confirmation from consuming twice. Variable amounts and missing conversions require resolution before consumption.

Cooking mode provides steps, ingredient checkboxes, multiple elapsed-time timers, and a keep-awake window. Checkboxes do not book stock. Shared text URLs open a review, optionally read bounded public HTTPS metadata anonymously, retain the source in Grocy's description, and require explicit mapping of ingredients/quantities/units to existing products or explicit exclusion. Import never creates products. Reviewed mapped ingredients are durable deterministic follow-ups to confirmed recipe creation. Selected images are reviewed, re-encoded, uploaded to Grocy, and associated with the native picture field; authenticated reads are account cached. See [recipe behavior and limits](RECIPES.md).

Actual validation on 2026-10-07:

- `mise run stage:10`: **passed on the final code**, including debug APK, Android-test compilation, JVM regressions, recipe coverage, lint, and native parity against the **already-running local Grocy 4.7.1 at http://127.0.0.1:9283**. No container was started, no backend added, and nothing deployed.
- Native test: a base two-serving recipe with ingredient amount **1.25** in a **2:1** unit conversion was scaled to four servings. Stored ingredient amount remained **1.25**, required stock was **5**, available stock **3**, and **2** were added to the chosen Grocy list. Repeated missing-item addition created no extra row. After purchasing the missing amount, reviewed consumption booked **5** exactly once; native stock became **0** and one consume journal row carried the recipe ID. Reconfirming the same review did not duplicate consumption. Undo restored **5**.
- The same test verified recipe/ingredient create/edit/delete, source retention through Grocy sanitization, image upload/read/native filename association, meal-plan CRUD with supported server sections and fractional servings, and explicitly mapped import with repeat synchronization creating one ingredient row.
- **64 app JVM cases: 60 passed, 4 skipped, 0 failures/errors.** All eight new recipe tests ran. Skips were the previous Stage 6/7/8/9 live scenarios whose separate fixture credentials were intentionally not supplied. The unchanged fraction module's 11-test result was reused by Gradle.
- Recipe domain/repository/parser/file-client coverage: **92.59% lines, 82.10% instructions, 60.61% branches**; the 80% line gate passed. Compose/ViewModel coverage is not included in this metric.
- Lint: **0 errors, 22 warnings**. Debug APK built; Android instrumentation tests compiled but **were not run**. No Stage 10 emulator, physical-device, browser automation, or live public recipe-page fetch test is claimed.
- Unit/MockWebServer tests covered exact unchanged fraction input, converted aggregation, fulfillment flags, missing conversions, metadata ambiguity/manual yields, instructions/source escaping, stock recipe attribution, denied recipe access without network calls, cached reads, revoked-read rejection, durable edits, interrupted outcomes, and no automatic resend.
- Local compatibility findings: Grocy strips custom HTML attributes, so source retention uses a plain source link. Its resolved missing field did not honor the tested 2:1 conversion, so shortfalls are computed from native scaled quantities, flags, stock, and conversions. No Grocy code was copied or changed.
- Synthetic recipes/ingredients, meal entries, shopping list/rows, product/stock/journal, conversions, units/location, image file, temporary API keys, and private credential files were removed by the tests/gate.

Evidence: `app/build/reports/stage10/local-parity.json`, `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/jacoco/recipesCoverageReport/`, and the lint report. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Stopped at Stage 10. No later stage is authorized.


## Stage 11 — last build stage: household tools and cached home surfaces

Stage 11: **complete**. Implemented only the authorized last build stage and stopped after its gate passed.

Household adds native batteries/charge history/undo, equipment, products, locations, stores, units, conversions, and task categories. Availability follows the server's exposed-entity list; reads and edits honor explicit grants. Native master edits and charge operations use the account outbox, with pending/confirmed/needs-review status and no automatic resend of uncertain outcomes. Unit conversion factors remain decimals; fraction display/input preserves original unchanged values. Battery due dates come from Grocy.

Typed custom-field editors support text, numeric, checkbox, date, and date-time. Other types have a visible caption/type/reason and are omitted from writes, preserving their existing values. Unsupported required types block new records with a reason. Supported custom values use deterministic official userfield endpoint follow-ups after confirmed native creation/edit. Equipment's existing manual filename and additional server attributes are preserved; this editor does not upload equipment manuals.

Today shows cached due chores, today's native meal plan/sections, low stock, and due/expired food. Assigned-child filtering and feature grants apply before presentation. Child navigation now permits Today while Household remains its landing page. Today distinguishes cache reads from explicit server refresh. Three home-screen widgets provide cached chores, shopping counts per list, and a scan/manual entry point; taps reach existing flows after cold-start account verification.

One unique WorkManager periodic job updates widgets from cache and delivers opt-in reminders outside quiet hours, with at most one reminder per account/local calendar day. It performs no network calls, stock bookings, chore completions, or external automation. Quiet hours support crossing midnight and all-day silence. Android notification permission is checked. Background cache readers do not recover/claim an active foreground outbox. Publication is fenced against account switch/logout; existing widgets/notifications clear on account change. Known HTTP 401/403 also suppresses cached background content until successful account verification.

Actual validation on 2026-10-07:

- `mise run stage:11`: **passed on the final code**. Debug APK, Android-test compilation, app JVM regressions, scoped coverage, lint, and native parity all passed against the **existing local Grocy 4.7.1 at http://127.0.0.1:9283**. No container was started; no backend, external automation, or deployment was added.
- Native battery test created a synthetic battery, queued one charge, rejected a duplicate while unconfirmed, and verified **one charge cycle** with matching battery ID/time/history and the exact server next-charge date. Repeated synchronization did not add a cycle. Native undo returned the cycle count to zero and marked its history undone.
- Native master test created synthetic units, location, store, category, product, and equipment. A product-specific conversion factor **2.5** matched the raw and resolved Grocy API records; editing it to **3** matched the native record. Supported equipment custom-field values survived the official userfield follow-up. Master edits/deletion also passed.
- **75 app JVM cases: 70 passed, 5 skipped, 0 failures/errors.** All 11 new catalog/Today/WorkManager cases ran. The skips were earlier Stage 6/7/8/9/10 live scenarios whose separate credentials were not supplied. The unchanged fraction module's 11-test result was reused by Gradle.
- Scoped catalog/Today/cache-reader/WorkManager/preferences coverage: **96.02% lines, 88.60% instructions, 73.03% branches**; the 80% line gate passed. Compose/ViewModel/RemoteViews rendering and encrypted account publication locking are not included in this coverage metric.
- Lint: **0 errors, 27 warnings**. Debug APK built and Android tests compiled. A Kapt compiler error involving a callable-reference property initializer was fixed using an equivalent lambda; no compiler/lint suppression or baseline was added.
- Unit/MockWebServer checks covered denied master/charge/undo calls without network access, exposed/unsupported records, custom types and validation, cached reads, revoked-read suppression, interrupted charge retention/no resend, permission/assignment filtering, meal sections and volatile stock, and a second background reader leaving a foreground in-flight outbox unchanged.
- Robolectric/work-testing executed reminder workers and unique scheduling: overnight/equal quiet hours, no notification or delivery mark during quiet/widget-only work, one notification per day, blocked/superseded publication, and preference validation. These checks are not evidence of physical-phone scheduling, notification delivery, or launcher rendering.
- Synthetic battery/cycles, equipment/custom-field definition, product, units/conversion, location/store/category, temporary API keys, and private credential files were removed by the test/gate.
- **Physical-phone tests: NOT RUN.** No Stage 11 emulator, physical camera, launcher, browser, or device offline replay test ran. [PHONE_TEST.md](PHONE_TEST.md) records the required later child login, child completion, fraction input, real-package scan, and offline purchase replay procedures, plus widget/reminder checks; every physical result is explicitly NOT RUN. Existing upstream child access gaps remain documented and are not resolved by a new backend.

Behavior: [household tools](HOUSEHOLD_TOOLS.md). Evidence: `app/build/reports/stage11/local-parity.json`, JVM reports, `app/build/reports/jacoco/catalogCoverageReport/`, and lint reports. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The last build stage is complete. Stopped; no further implementation is authorized.

## Post-Stage-11 defect review — 2026-10-07

The user's subsequent request authorized bug fixes and cleanup of existing behavior. Completed that maintenance scope; no feature stage was added. [REVIEW.md](REVIEW.md) names every bug, production file, failing-before-fix regression, and remaining risk.

Actual validation:

- Ran `mise run stage:11` with the existing Stage 6–10 fixture variables also supplied, all pointing to temporary credentials on **existing local Grocy 4.7.1 at http://127.0.0.1:9283**. No services were started. The gate passed in 2m31s.
- **84 app JVM tests passed; zero failures, errors, or skips.** All six live repository scenarios ran: stock/journal/undo; shopping conflict/offline replay/two-client purchase/lost-ack reconciliation; child chore attribution and server next due date; reviewed scanner creation/stock/undo; recipes/meals/images/consumption; batteries/conversions/equipment. Historical test names mentioning two versions do not imply a second version ran here: every new evidence report identifies 4.7.1 only.
- The new recipe shortage regression failed against local Grocy before the fix: it added zero rows instead of one after an external API edit selected a different display unit. It now passes and independent reads verify two outstanding stock units. API writes/reads use the same records as Grocy's web app; browser UI automation was not run.
- Nine new host JVM/Robolectric cases plus two strengthened existing cases reproduced defects before their fixes. Host Compose checks cover unchanged fraction values, repeating unit conversion, desired servings, account-switch draft reset, and stale placeholder text. Mock/injected transports cover denial handling; they do not claim server results.
- The fraction module's **11 passing tests were reused unchanged by Gradle**. Debug APK assembled, Android test sources compiled, and lint passed with **0 errors, 27 warnings**. The Stage 11 scoped coverage gate passed.
- Sync, shopping, and recipe **80% line-coverage gates also passed**, using the completed suite's execution data (`-x :app:testDebugUnitTest` avoided repeating tests). These scoped metrics do not cover the complete app or establish device behavior.
- Temporary API keys, the separate child user, and private credential files were removed. Synthetic records were removed by test cleanup. Reports: `app/build/reports/stage6/container.json`, `stage7/container.json`, `stage8/local-parity.json` through `stage11/local-parity.json`, JVM XML/HTML reports, lint, and `app/build/review/{red,red-second,red-third,green,final-gate}.log`.
- **No phone or emulator test ran.** No physical camera, launcher, notification delivery, Android process-kill, browser UI, second Grocy version, or CI validation is claimed. Account Keystore/device tests were read but not rerun.

Remaining risks include upstream child permission gaps, external-writer races around non-conditional writes, assignment changes between child preflight and execution, interrupted multi-product recipe consumption, unresolved ambiguous creates, and intentional loss of unsent local changes on logout. See REVIEW.md before interpreting this review as an atomicity or device-validation guarantee.

### Second pass — same day

Two more stored-value defects were fixed after a failing host test for each. Shared-recipe review no longer reparsed an untouched base-servings value, and a product edit no longer wrote zero for an unset decimal such as calories. Unused `readCached` and `enqueueMutation` wrappers were removed. No feature was added.

Actual validation:

- Before the fixes, `ShoppingFormsTest.importReviewWithoutEditingPreservesBaseServings` failed with `expected:<0> but was:<1>` (`0.5001.compareTo` the saved quantity). `productEditDoesNotInventZeroForUnsetDecimals` failed because the saved payload contained `calories`.
- After the fixes, `./gradlew :app:testDebugUnitTest :fractions:test` passed **88 tests, 0 failures, 2 skipped**. The skips were the stock and shopping live cases, which had no fixture variables in that invocation. Fraction tests were unchanged and up to date.
- Those two live cases were then run with temporary keys on the already-running listeners. Both passed: stock/journal on Grocy **4.7.1 and 4.6.0**, and shopping add/edit/delete, offline replay, conflict preservation, one purchase journal, and lost-ack reconciliation on both versions. Reports: `app/build/reports/stage6/container.json` and `stage7/container.json`.
- The same unit-test invocation, with temporary local credentials, also passed child chore attribution (`log_done_by` 24 matches the child), scanner create/stock/undo, recipe scale/consume/meals, and battery cycle/conversion/equipment on **Grocy 4.7.1 at http://127.0.0.1:9283**. Reports: `stage8/local-parity.json` through `stage11/local-parity.json`.
- Temporary keys, the Stage 8 child user, and private credential files were removed. **No phone, emulator, camera, launcher, notification-delivery, or browser UI test ran.**

### Third pass — same day

Four more defects were fixed after failing host tests. No feature was added. An unedited shopping unit change now scales the original decimal. A second database lease can no longer drop an in-flight success. The stock journal shows a negative consume amount. Reactivation keeps a child's last verified grants when that user cannot re-read permissions and no administrator is stored.

Actual validation:

- Before the fixes, `ShoppingFormsTest.unitChangePreservesUneditedApproximateQuantity` failed with `expected:<0> but was:<1>`. `OutboxTest.secondLeaseRecoverDoesNotDropAnInFlightSuccess` expected `confirmed` and observed `needs-review`. `StockScreenTest.consumeJournalAmountDoesNotCrash` threw `Quantity cannot be negative`.
- After the fixes, `./gradlew :app:testDebugUnitTest :fractions:test` passed **93 app tests, 0 failures, 0 skipped**. The fraction module's tests were unchanged, so Gradle reused its previous result.
- The same run used temporary keys on the already-running listeners. Stock and shopping passed on Grocy **4.7.1 and 4.6.0**, including offline replay, conflict preservation, one purchase journal, and lost-ack reconciliation. Child chore attribution on **4.7.1** recorded `log_done_by` 29 for child user 29 and next due `2026-10-09 09:00:00`. Scanner creation/stock/undo, recipe scale/consume/meals, and battery cycle/conversion/equipment also passed on 4.7.1. Reports: `app/build/reports/stage6/container.json`, `stage7/container.json`, and `stage8/local-parity.json` through `stage11/local-parity.json`.
- The grant decision is covered by `AccountPolicyTest`. The repository activation path was not executed: Robolectric has no Android Keystore, and `adb` showed no device. Temporary keys and private credential files were removed. **No phone, emulator, camera, launcher, notification-delivery, or browser UI test ran.**

### Settings → Stock → Shown details — 2026-10-08

Pantry rows can show extra built-in attributes and the account's Grocy product userfields, chosen per account in Settings → Stock → Shown details. Defaults reproduce the previous row exactly. Display only; no userfield values are written. Details: `docs/STOCK_DETAILS.md`.

Actual validation:

- `./gradlew assembleDebug testDebugUnitTest` passed **146 app tests, 0 failures, 6 skipped** (the live-server cases, no fixture variables). The same command on the base (feat/quantity-display-style + fix/account-switch-stale-results) passed 131 tests, 6 skipped. New: `StockDetailsTest` (13) and `StockDetailsUiTest` (2, Robolectric).
- Userfield bulk availability was checked against the recorded Grocy 4.6.0/4.7.1 responses in `docs/evidence/stage1/`, not a live server. **No phone, emulator, or live Grocy test ran for this change.**
