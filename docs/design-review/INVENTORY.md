# Stillroom design inventory

Review date: 2026-10-07. Basis: current source and implemented-stage documentation. This is a design review, not observed household research, a camera hardware trial, or a WCAG conformance certification. Historical Stage 2 screenshots predate the current screens and are not evidence of their appearance.

## Audience and constraints

People who maintain a Grocy household: put groceries away, record food used, shop from shared lists, plan/cook recipes, and complete assigned household work. Separate Grocy accounts and permissions define available actions. A child account is a separate server user/key, not a visual profile. Existing UI restriction does not solve the documented upstream child access-control limitation.

Native Android, Kotlin/Compose/Material 3, minSdk 26. Phone bottom navigation; tablet sidebar; large text navigation fallback. Existing light/dark/system, dynamic color, reduced motion, account-separated cached reads, durable mutation records, shopping drafts/conflict resolution, widgets/reminders. Grocy remains authoritative; no new integration or feature stage is part of this spec. Read AGENTS.md and STATUS.md together; the feature matrix is historical discovery rather than the current capability inventory.

## Screen and component inventory

| Family | Existing screens/substates | Source |
|---|---|---|
| Shell | Today, Pantry, Shop, Meals, Household; Search, Scanner, Settings, Accounts, Pending changes; disconnected/restricted states | StillroomShell.kt, ShellPreferences.kt |
| Today | Date/greeting, time-based stock meal photo, due chores, planned meals | TodayScreen.kt |
| Pantry | All/In stock/Use soon/Running low/Opened filters; Locations; Journal; product detail; Purchase/Consume/Inventory/Transfer/Open/Spoilage form; booking/transaction undo | StockScreen.kt |
| Scanner | Enable camera/permission; camera target/torch/zoom; manual entry/format; detected-code choice; product choice; unknown-product review/create; embedded product detail; normal/identical next scan | ScannerScreen.kt, CameraScanner.kt, ScannerViewModel.kt, Scanner.kt |
| Shop | List chooser/create; grouping; shopping/in-store views; add/edit item; purchase review; server/local conflict review/merge | ShoppingScreen.kt, ShoppingForms.kt |
| Meals | Recipe library; meal plan; recipe detail/servings/photo editing; recipe/ingredient/meal editors; import URL/share and ingredient mapping; cooking checklist/steps/timers; stock consumption review | RecipeScreen.kt |
| Household | Chores/My chores, Tasks; create/edit/delete; assignment/recurrence; chore history | HouseholdScreen.kt |
| Records | Batteries/charge/history/undo, Equipment/manual reference, Products, Locations, Stores, Units, Unit conversions, Task categories; record editor/custom fields/delete | CatalogScreen.kt, Catalog.kt |
| Accounts | Connection URL/key/HTTP option; saved accounts/use/reverify/logout; child permissions administrator verification | AccountScreen.kt |
| Settings | Visible sections, child landing, reminders/quiet hours/notification permission, theme/dynamic color/reduced motion; debug-only Pending changes link | StillroomShell.kt |
| Changes | Active-account outbox, refresh, server-state read/reconciliation | PendingChangesScreen.kt |
| Outside app | Chore/shopping/scan widgets; once-daily reminders | HouseholdWidgets.kt, HouseholdWork.kt, household_widget.xml |
| Shared | Cards, list/grid scaffolds, check rows, stock rows, section titles, quantity badges, refresh/spinner, photo tiles, empty/loading/error/offline/permission states | KitchenComponents.kt, StateComponents.kt, StillroomTheme.kt |

## Object relationships

Account → server/user/grants/cache/outbox. Product → barcode(s), stock unit, conversions, default location/store, minimum amount and expiry rules. Stock entry → product/location/quantity/date/open status; booking/transaction → stock journal and supported undo. Shopping list → linked-product or text item/amount/unit/note/completion; purchasing is a separate stock write. Recipe → ingredients/products/units/base and desired servings/image/instructions/nested recipes; meal plan entry → day/section/recipe OR product OR note. Chore → recurrence/assignment/executions; task → category/assignee/due/completion. Battery → interval/charge cycles. Equipment → record/manual reference. Custom field definition → supported value editor or preserved unsupported field.

## Scanning reference

Grocy Android's [FAQ](https://github.com/patzly/grocy-android/blob/master/FAQ.md) documents entering scan mode with an action selected; its [README](https://github.com/patzly/grocy-android/blob/master/README.md) describes batch processing and larger in-store controls. Use these documented workflows as reference only. No GPL source is copied; this review did not operate Grocy Android on a phone. Stillroom scanning currently exposes Purchase/Consume only, and scanning never itself authorizes a stock write.

## Files produced by this review

Six specialist reports, a synthesized build specification, and a separate final critic report. The synthesis is the normative engineering spec; specialist reports preserve audit evidence and may contain recommendations rejected during reconciliation.
