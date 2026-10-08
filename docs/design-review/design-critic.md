# Final design critique

Reviewed 2026-10-07 against DESIGN-SYSTEM.md and the Android Compose implementation. This is a source review of the synthesis and its implementation, not a physical-device accessibility or camera certification. Findings below record the initial critic pass; implementation owners are revising concurrently.

## Required revision, ranked

1. **Major — Scanner creation loses its recovery state.** `ScannerViewModel.scanNext` clears `createOperation` after an unconfirmed product creation; `ScannerScreen` can then present repeat/create as an ordinary new task. `GrocyScanRepository.create` already prevents another durable creation for the same barcode, so this is a misleading enabled action and recovery dead end, not a demonstrated duplicate server write. Preserve the unresolved creation identity and direct recovery action; prevent deliberate rearm from erasing that context. Only clear it after confirmation or an explicit known-failure recovery. **Principles: Nielsen error prevention, visibility of system status, user control.**

2. **Major — App-bar Back and system Back disagree during scanner review.** `StillroomShell.ShellToolbar` calls `model.back()` directly, whereas `ScannerScreen` consumes system Back to cancel the local read/review and respects busy state. The toolbar can leave the session while a submitted change is waiting; its accessible name `Back to sections` also misnames the new Pending changes → Scanner return route. Route both Back controls through the same local contract, preserving any durable submitted operation. A plain `Back` accessible name is truthful for the history stack. **Principles: Nielsen consistency and user control; WCAG 2.2 label clarity.**

3. **Major — A failed read still claims an empty household collection.** `RecipeScreen` recipes/meal-plan branches, `ShoppingScreen` empty list/rows branches and `HouseholdScreen` empty chore/task branches choose empty-state copy after `busy` clears even when `state.error` is present. Distinguish initial loading, unavailable/error, successful empty, and cached content. Add a reload action to the failed first-read state; preserve cached rows on refresh error. Do not tell a household to create records because the read failed. `TodayScreen` already takes this distinction seriously and should be the consistency reference. **Principles: Nielsen visibility and error diagnosis; match between system and real world.**

4. **Major — Reduced motion is promised globally but bypassed locally.** `ScannerScreen`, `StockScreen.StockDetail`, and `CatalogScreen` use unconditional indeterminate `LinearProgressIndicator`; Settings promises static loading. Honor `LocalReducedMotion` at these call sites using the same named static operation status as shared components. Preserve visible feedback rather than removing status entirely. **Principles: user control, consistent system behavior; accessibility preference.**

5. **Minor — Repeat-code and recovery copy introduce unnecessary vocabulary.** `ScannerViewModel.manual` references `Scan another identical package`, while the reviewed button says `Repeat package`. Use `Scan same code again` consistently, which describes the actual rearm behavior without assuming packaging. `RecipeScreen` says `Something needs a look` and exposes `stock consume path`/`stock journal` in consumption review. Replace with specific confirmation/recovery language: `A recipe change needs review. Open Settings → Review pending changes.` and `Confirming records these quantities as used in Grocy. Review pending changes if the result is uncertain.` Name the final button `Confirm consumption`. **Principles: recognition over recall, Nielsen real-world match, signal-to-noise.**

6. **Minor — The token contract and page structure still disagree.** `StillroomShell` uses 20sp `titleLarge` for page titles while the spec says 24sp; Scanner adds another page-level heading beneath `Scan products`. Use the shell title as the page identity and remove the redundant scanner heading. `HouseholdHub` has 20dp horizontal padding outside the 8pt grid; shopping form/review and catalog phone panes use 24dp instead of the specified 16dp phone gutter. Align these values to the documented system or explicitly document a purposeful exception. **Principles: hierarchy, Gestalt alignment, consistency.**

## What survives the critique

- **Shell, Accounts, Settings, Pending changes:** neutral surfaces, labeled Scan, release-visible recovery, above-field credentials, masked key, account-specific labels and reversible utility history support the task. Keep them. Pending changes checks server state without replay, which is the right distinction.
- **Today:** removal of generic imagery makes actual chores and meals the content. Loading/unavailable checks preserve truthful empty states.
- **Pantry and Scanner:** focused stock review, single-code/product automatic resolution, editable quantity and required package date, operation-ID receipt, paused analysis and explicit repeat control clarify the existing Add/Use job. Repository duplicate-creation guard and uncertain-write protection must survive every UI revision.
- **Shop:** selected list, disclosed creation, named quantity/unit and distinct Add stock action clarify shopping versus inventory. The pending shopping checkbox still reflects an optimistic local draft; visible sync copy is essential, and it must not be described as server-confirmed completion.
- **Meals:** real recipe images only, Recipes/Meal plan labels, dated plan entries, a separate cooking task and explicit consumption review are sound. Keep cooking checklist completion separate from stock mutation.
- **Household and Records:** waiting chore/task toggles remain unchecked and disabled; concrete record names and bounded searchable selectors reduce hunting. Record charge, edit and history remain separate existing actions.
- **Shared visual system and widgets:** one sans family, neutral surfaces, one primary accent with contrast guard, named semantic errors, standard radius and generous targets provide restraint. No additional decorative treatment or conceptual layer is justified.

## Remaining physical-phone verification

These are verification tasks, not speculative code defects: CameraX initialization and flash/zoom behavior; automatic pause/resume after review; repeated-frame suppression and deliberate repeat with a real barcode; permission denial and manual fallback; TalkBack merged names and traversal for selectors/checklists; 200% text and keyboard visibility in scanner/editor dialogs; light/dark/wallpaper contrast on rendered controls; 320dp toolbar/navigation wrapping; reduced-motion setting during every loading state; widget scaling; and an offline/uncertain-write recovery round trip back to Scanner. Test account switching during lookup/submission separately from normal success. Source evidence alone does not prove these pass.

## Revisions applied after the critic pass

- Retained unconfirmed creation identity; next/repeat locked until the product and barcode attachment confirm. Check product status uses existing reads/synchronization, with no replay.
- Routed app-bar Back through Android's same back dispatcher used by local/system handlers; named it Back. Added task-origin history regression. Unconfirmed creation can leave the scanner without clearing its retained recovery identity; saving still consumes Back until its outcome is available.
- Failed-first-read states now offer a retry; loading and genuinely empty collections are distinct across Meals, Shop, Household and Records.
- Scanner, stock and catalog progress now use shared TaskProgress with a static reduced-motion status.
- Unified Scan same code again / Check changes wording, removed API-path prose from consumption review, removed duplicate scanner heading, aligned page title and phone gutters.
- Added shared primary/secondary/quiet button wrappers to use one 8dp radius, 48dp minimum and neutral secondary text. Repeated battery Record charge actions are secondary; entity creation stays primary.

Validation results belong to STATUS.md; this revision ledger is not a physical-device test claim.
