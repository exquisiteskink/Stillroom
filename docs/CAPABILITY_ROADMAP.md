# Stillroom capability review and extension roadmap

Research date: 2026-10-09. Priority-one implementation subsequently authorized and completed: [PRIORITY_ONE.md](PRIORITY_ONE.md), with actual validation in [STATUS.md](STATUS.md). The proposals below retain the original research context. This is a source/documentation review and proposal, not a new implementation stage. No production code changed or tests reran for this review. Existing constraints remain: Grocy is authoritative, account-separated data and credentials, reviewed writes, MIT implementation, no Home Assistant or Hermes integration.

## What is already present

- Grocery-list shopping: quantity editing, hold to remove, checkbox purchase, clear checked rows, and optional external purchase ownership.
- Personal Today tasks backed by Household, with Everyone tasks, completion and inline category creation.
- Stock actions, product editing, unit conversions, stock journal and textual product price history.
- Recipe URL sharing/import with JSON-LD, manually mapped ingredients, meal planning, shortages-to-shopping, cooking mode and local timers. Recipe images already support upload.
- Chores, batteries, equipment metadata, native master data, typed userfields, cached widgets and opt-in cached reminders.
- The add-on foundation: foreground external-change observation, server capability guards, richer barcode defaults/Grocycodes, custom records and custom-record media, separate administrator BarcodeBuddy credentials and uncertain-scan receipts, and browser shortcuts.

Sources checked include `docs/ADDON_COMPATIBILITY.md`, `docs/RECIPES.md`, `docs/HOUSEHOLD_TOOLS.md` and production repositories/screens/background workers. `docs/FEATURE_MATRIX.md` is explicitly historical Stage 1 discovery and must not be used as today's implementation inventory.

## Review findings to address before expanding integrations

1. **Live compatibility remains unverified for the foundation.** The last recorded build, host tests and lint passed, but local Grocy was unreachable. Validate actual add-on-produced records, media references, disabled-feature deployments, BarcodeBuddy submissions and web-to-phone refresh on supported Grocy versions. Existing host coverage is useful but does not establish those results.
2. **External refresh is foreground-only.** `AddonViewModel` polls while started. `HouseholdWorker` reads cache only. Changes from a scanner/importer while Stillroom is closed will not become fresh widget/reminder data automatically. An optional read-only background sync would be a deliberate behavior change; it must never replay mutation queues.
3. **Custom-record paging bounds displayed/enriched rows, not the initial dataset.** `GrocyCustomRecordsRepository.snapshot` loads all userobjects, then selects a 50-row page and can read values separately for each row. Server filtering/paging, batched values where available, search and bounded cache sweeps would help larger installations. Query routes need explicit safe cache keys and refresh rules.
4. **Custom-record reading currently requires MASTER_DATA_EDIT.** A separately designed read-only experience could let other household members view appropriate custom data. Grocy's actual read exposure and Stillroom's policy must be checked; no client-side screen should imply stronger server isolation.
5. **Media support is uneven.** Custom records upload media and recipes upload pictures; native product userfield media replacement and equipment manual access remain incomplete. `GrocyFiles` currently permits only userfiles/productpictures/recipepictures, not equipmentmanuals.
6. **Addressed in priority one:** personal task widgets/reminders, completed-task reopening, persistent cooking progress and background timer alarms are implemented. Physical launcher, alarm delivery and reboot verification remain outstanding.

These are observed limitations, not claims that an unspecified installed add-on is broken. Custom-record compare-before-write still has the documented non-atomic race; richer integrations must preserve uncertain-outcome handling.

## Recommended next capabilities

| Priority | Extension | Concrete user behavior | Foundation needed |
| --- | --- | --- | --- |
| 1 | Personal task tools | Add a task from Today, filter by category, show assigned/Everyone tasks in a widget, receive due-task reminders, reopen completed tasks | Reuse native task/category records and permissions; keep personal filtering identical everywhere; recurring work should use Grocy chores unless a separate recurrence model is explicitly designed |
| 1 | Persistent cooking timers | Timers survive leaving cooking mode and notify when finished; resume the active cook | Persist sessions/deadlines, define reboot behavior, implement Android alarm/notification delivery and applicable permission handling; completion never consumes stock |
| 1 | Shopping-trip scan review | Scan many items, edit quantities once, confirm a reviewed trip, inspect per-line confirmation | Native purchase review with stable per-line operation identities, partial-failure recovery and no duplicate bookings; support hardware/Bluetooth scanner input while preserving review |
| 2 | Grocy label printing | Print a product/stock-entry label after purchase; print chore/battery labels; optionally export labels | Advertised print routes and label-printer flag; explicit action transport with no polling/cache/retry; first support server-run hooks, identify client-run hooks separately |
| 2 | Better product setup | Barcode association editor, missing-barcode queue, product photos, brand/custom-field filters | Native barcode CRUD with duplicate checks, shared confirmed media upload, configurable field mapping rather than assuming a particular add-on's schema |
| 2 | Richer recipe import | Remember approved ingredient/product/unit matches, choose among multiple recipe candidates, retain sections and preparation/cooking time | Account-specific mappings revalidated against current products/conversions, candidate review, richer Schema.org parsing; no silent product creation or guessed quantities |
| 2 | Custom-data browser | Search/filter/sort, named entity shortcuts, relationship navigation, read-only access where appropriate | Efficient filtered reads, capability-aware query handling, explicit access policy and definition version handling |
| 2 | Household documents | Open equipment manuals, attach product documents, replace product photos and media fields | Add authenticated equipmentmanuals handling, bounded downloads and Android document sharing; reuse durable upload outcomes |
| 3 | Purchase/waste insights | Price trends per product/store, estimated basket cost, recorded spoilage and buying frequency | Build on existing price history and stock logs; normalize units, account for reversals and show missing data; recorded purchases are not a complete household budget |
| 3 | Receipt review/import | Share a receipt, map each line, exclude deposits/discount-only lines, review prices/amounts, import once | Begin with reviewed structured CSV/JSON or a documented installed importer; add PDF/OCR later; receipt fingerprints, per-line identities, total reconciliation and partial-write recovery |

## Ecosystem evidence and implications

- [Grocy's add-on catalog](https://grocy.info/addons) lists companion clients, BarcodeBuddy, RecipeBuddy and GrocyPad. This is a varied ecosystem rather than a single native plugin contract. Prefer native Grocy data first, documented service adapters second, explicit browser destinations for web-only workflows.
- [Basil](https://github.com/sabourinj/Basil) documents hardware scanner intents, purchase/consume/lookup workflows, haptic feedback and label printing. It supports prioritizing scanner ergonomics; Stillroom should preserve its own reviewed-write policy.
- [Grocy label printing](https://github.com/grocy/grocy/blob/master/docs/label-printing.md) supports configured webhook targets run from the server or browser. [The API specification](https://github.com/grocy/grocy/blob/master/grocy.openapi.json) exposes product/stock/chore/battery/recipe print routes and thermal shopping printing. Some are GET actions: never treat them as safe refresh reads. Server-side hook support is the simplest initial scope; arbitrary printer hooks are not automatically compatible.
- [BarcodeBuddy's API](https://github.com/Forceu/barcodebuddy/blob/master/openapi.json) documents optional scan price and bestBeforeInDays as well as global mode control. Stillroom currently submits only the barcode. Reviewed purchase metadata and a mode-change warning could extend this adapter; changing shared mode must be explicit and is not an atomic per-scan action.
- [RecipeBuddy](https://github.com/georgegebbett/recipe-buddy) extracts web recipe metadata and maps ingredients to Grocy products/units. Stillroom already implements that basic workflow. Improve mapping memory and review instead of adding a second basic importer. [Schema.org Recipe](https://schema.org/Recipe) supplies richer yield, section/instruction and timing fields.
- [ProductHelper](https://github.com/Raph563/ProductHelper) documents OFF/OPF lookup, photos, product helpers and brand/link custom entities. [StatNerd](https://github.com/Raph563/StatNerd) is a browser analytics add-on dependent on [NerdCore](https://github.com/Raph563/NerdCore). Native data views and optional configured brand relationships can complement them; editing raw brand fields does not reproduce their JavaScript automation.
- [Receipt Radar](https://github.com/andrecedik/receipt-radar) documents reviewed product mapping and Grocy stock push, with retailer-specific German receipt formats. It demonstrates the workflow but is not evidence of universal receipt support or a stable general connector API. Confirm an adapter contract before promising direct integration.
- [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) explains delivery and exact-alarm permission constraints. Cooking timers require a separate design from the existing periodic cached reminders.

## Suggested sequence

First validate the foundation against live Grocy/add-on fixtures and address large custom datasets. Personal task widgets/reminders, persistent timers and shopping-trip review are now implemented. Optional hardware input, server-backed label printing and product media remain next candidates. Recipe improvements/custom browsing can reuse those components. Receipt import and analytics come later once purchase metadata and reconciliation have reliable end-to-end evidence.

No automatic add-on detection, JavaScript execution, shared-mode scan guarantee, universal OCR accuracy or atomic multi-product purchase is promised by this proposal.
