# Stillroom information architecture review

Scope: existing native Android capabilities in the checked-in app, not future feature proposals. Source inventory reviewed on 2026-10-07. This review is a build specification; no UI implementation or device validation is claimed.

## Principles applied

- **Recognition over recall (Nielsen 6):** label destinations by the user's objects and display names rather than IDs or API nouns.
- **Match to the real world (Nielsen 2) and Jobs-to-be-Done:** organize putting groceries away, using stock, shopping, cooking, and completing assigned work around those tasks.
- **Consistency (Nielsen 4):** one canonical view per object; different entry points carry origin/context rather than create duplicate destinations.
- **Progressive disclosure and cognitive load:** show only the decisions needed for the current stage; reference information follows or lives behind an explicit detail disclosure.
- **Visibility of status and error recovery (Nielsen 1/9):** distinguish no data, loading, denied access, stale data, and unresolved writes; never imply a successful Grocy mutation from decoding a barcode.

## Current object model

| Object | Relations / existing operations | Sources |
|---|---|---|
| Account | Server + verified Grocy user + grants; connect with key or key QR; activate/reverify; log out; account-isolated cache/outbox | `domain/Accounts.kt`, `ui/AccountScreen.kt`, `data/AndroidAccountsRepository.kt` |
| Product | Stock/purchase/consume/price units, location/store, barcode records, unit conversions, minimum stock, food defaults, custom fields | `domain/Catalog.kt`, `domain/Stock.kt` |
| Stock entry | Product, amount, opened state, location, due date, price; purchase/consume/spoil/open/transfer/inventory; journal bookings/transactions and supported undo | `ui/StockScreen.kt`, `domain/Stock.kt` |
| Scan session / suggestion | Raw validated code, candidates, suppressed selected codes, matched product IDs or editable suggestion; reviewed product creation and barcode attachment | `domain/Scanner.kt`, `ui/ScannerViewModel.kt`, `docs/SCANNING.md` |
| Shopping list / item | Product or note, quantity/unit, done status, store/category grouping, estimated total; CRUD, purchase review, conflict review | `domain/Shopping.kt`, `ui/ShoppingScreen.kt`, `ui/ShoppingForms.kt` |
| Recipe / ingredient | Base + desired servings, existing product/unit mapping, instructions, source/image, variable quantities; CRUD, shortage additions, consumption review | `domain/Recipes.kt`, `ui/RecipeScreen.kt` |
| Meal entry | Day, section, recipe/product/note and servings/amount; CRUD | `domain/Recipes.kt`, `ui/RecipeScreen.kt` |
| Chore | Instructions, Grocy due date, recurrence, assigned users/rotation, execution/history; parent management, permitted child completion | `domain/Household.kt`, `ui/HouseholdScreen.kt` |
| Task / category | Description, due date, category, assignee, completion; CRUD | `domain/Household.kt`, `domain/Catalog.kt` |
| Battery / charge cycle | Usage, interval, active state, last/next charge, track/history/undo | `domain/Catalog.kt`, `ui/CatalogScreen.kt` |
| Equipment | Name, description, server manual filename, supported custom fields; CRUD; manual remains available in Grocy | `domain/Catalog.kt`, `ui/CatalogScreen.kt` |
| Supporting records | Locations, stores, units, conversions, task categories; exposed fields/custom fields CRUD | `domain/Catalog.kt` |
| Pending change | Account-bound operation, state, detail, read/reconcile uncertain outcome; never automatic replay of an unknown result | `domain/PendingChanges.kt`, `ui/PendingChangesScreen.kt`, `docs/SYNC.md` |

## Current navigation and issues

Phone: five bottom destinations Today / Pantry / Shop / Meals / Household; tablet sidebar at 600dp; large-text phone uses scrolling chips. Toolbar exposes Search, Scanner, Settings, Accounts. Back handling knows only `ShellPage`, while subordinate selections use screen-local variables. Sources: `ui/StillroomShell.kt`, `ui/ShellViewModel.kt`.

- Pantry mixes **filters** (All/In stock/Use soon/Running low/Opened) with **destinations** (Journal/Locations) in one chip strip. Product detail mixes metadata, transaction form, histories, and undo. Principle: grouping by function, progressive disclosure.
- Search resolves to another `StockScreen`, not a cross-product search. Its default In stock filter excludes products with zero quantity even when the name or barcode matches. Two identical routes add no capability and disagree with “Search the kitchen.” Principle: consistency, accurate information scent.
- Scanner requires Enable camera, code selection even for one candidate, matched-product selection even for one match, then a full stock detail before booking. “Back to pantry” inside Scanner displays the entire pantry under a Scanner toolbar. Principle: task continuity, minimum decision burden.
- Household > “Batteries & more” > unlabeled entity dropdown contains batteries, equipment, products, locations, stores, units, conversions, and task categories. Product management is distant from Pantry; stores from Shopping; categories from Tasks. Principle: locality and labels with predictive information scent.
- Today cards jump to a section, not the named record, and meal cards open Recipes rather than the plan. Principle: preserve selection and task context. Routing to the existing record is restaging, not adding a capability.
- Recipes detail has multiple equal-weight primary actions and editing controls interleaved with ingredients/cooking. Shopping persists New list name/Create list in the main list. Principle: one stage/decision at a time.
- Pending changes is linked only in DEBUG Settings, yet scanner/recipes instruct users to inspect it. In a release build, the prescribed recovery route is missing. Principle: error recovery must be reachable in the shipping UI.

## Revised IA

Keep five destinations; change **Shop → Shopping** and **Scanner → Scan a barcode**. Keep Accounts and Settings as utility pages. Keep account restrictions; inaccessible objects do not become visible by this proposal.

```
Today (due chores + today's meal entries; each opens its existing object)
Pantry
  Product list (search; All / In stock / Use soon / Running low / Opened filters)
  Product detail
    Choose stock action → action form → mutation status
    Stock entries / prices / activity (secondary disclosures)
  Locations → products at location
  Stock activity → booking/transaction review and supported undo
  Manage products / locations / quantity units / unit conversions (existing catalog views)
Shopping
  Selected list → item edit or purchase review
  Choose/create list (one labeled list control)
  Group by category / store / no grouping
  At the store (existing density mode)
  Manage stores (existing catalog view)
Meals
  Recipes → recipe detail → cook / shortage additions / reviewed stock consumption
  Meal plan → meal entry → edit or open recipe
  Create/import recipe (secondary actions to existing forms)
Household
  Chores / Tasks / Batteries / Equipment (permission-filtered labeled views)
  Task categories under Tasks (existing catalog view)
Scan a barcode (utility task, origin retained)
  Choose Add stock / Use stock → camera OR manual entry → lookup → matching product OR new-product review
  Focused purchase/consume form → mutation status → scan next / repeat same code
Accounts → saved account selection; add account; verify permissions
Settings → navigation/landing preference; reminders; appearance; motion; Pending changes
Pending changes → account-bound human-readable operations → existing server-state read
```

Implementation guidance: reuse CatalogScreen with a supplied initial entity/allowed entity group rather than proliferating record editors. These contextual management links lead to one canonical catalog view. Group batteries/equipment away from pantry setup; remove the catch-all “Batteries & more” route. Keep an optional setup grouping if needed on small screens, but label each destination explicitly and avoid adding another object called “Catalog.”

Global Search should be removed as a duplicate route and its input kept in Pantry, labeled **Search products**. If retaining the toolbar entry is necessary, it must route to that canonical list with focus on search and an All-products scope; do not imply recipe/chore search, which does not exist.

Use actual nested destinations or an explicit local back stack: device Back first dismisses a modal, then exits the current action/editor/detail, then returns to the original section, then exits the app. Scanner Back returns to capture from product/review and to its launching section from capture. Section changes abandon neither an unresolved write nor its account-bound record; drafts may be cancelled explicitly. Preserve the existing account-switch reset of scan/review data.

## Primary flows, rewritten as exact steps

### Put a scanned grocery into stock

1. Tap **Scan a barcode**. Retain the originating section. Choose **Add stock** or **Use stock**, the two existing scanner stock actions; retain that action throughout this scan session. Show camera capture; first use requests permission only after **Use camera**. Previously granted camera opens when this task is entered; manual entry stays available.
2. Align the code in the target. One validated candidate proceeds to lookup automatically. Multiple candidates stop analysis and show a single **Choose a barcode** list; tap a code once. Decoding never mutates stock.
3. Show **Looking up barcode…** with the code. Freeze capture while lookup/review is active.
4. One matched product opens the product action view directly. Multiple matched products show **Choose a product** by name, with codes as secondary text.
5. Open the previously chosen **Add stock** or **Use stock** form; permit changing the action through a quiet labeled control. Add stock is the existing purchase operation, not a new inventory behavior. Product name and current stock remain visible; detailed stock metadata is collapsed below the task.
6. For Add stock enter quantity/unit, an explicit package due date, optional location/price. Due date label says **Due date (required)** in this scanner path. For Use stock enter quantity/unit and optional location. Show unit conversion when it changes what Grocy books.
7. Tap **Add stock** or **Use stock** once. Disable submission while in flight. Show Pending/Confirmed/Failed/Needs review from the existing operation state; “Saved” requires Grocy confirmation.
8. Confirmed: offer **Scan next** and **Scan this code again**. Scan next keeps duplicate suppression; repeating explicitly clears the last code's suppression. Uncertain result: offer **Review pending change**, do not reset into a retry that could duplicate stock.

### Review an unknown scanned product

1–3. Use the same capture and lookup sequence.
4. Show **Product not found** and the raw code. With master-data edit permission, display suggested name/description as editable fields and source/cached status as secondary information. Without permission explain **This account cannot create products**; offer scan another/manual correction.
5. Select an existing stock unit and location; no external value selects these for the user. If either collection is empty, show an explicit prerequisite state and route to the existing Units/Locations catalog editor only when permitted. Otherwise explain to create them in Grocy and retry.
6. Tap **Create product**. Product + barcode uses the existing outbox; the button does not book stock.
7. Confirmed creation opens the same focused stock action view. Pending/uncertain creation remains a status screen with **Review pending change**, not a second Create attempt.

### Enter a code manually

1. From capture tap **Enter code** to replace camera controls with the focused form; keep **Use camera** as the reverse path.
2. Enter barcode/QR text. Barcode is the default; show the existing UPC-E and QR/household format choices under **Code type** rather than three always-on peer chips beside the camera.
3. Tap **Look up code**. Validation error stays beside the code field; preserve input and selected type.
4. Follow the same match/new-product review path. For a suppressed code, offer the existing explicit **Scan this code again** reset rather than asking users to recall hidden session history.

### Find/use stock without scanning

1. Open Pantry; use search or a food-status filter. Search matches all permitted products by name/barcode, including zero stock.
2. Open product; show name, amount/unit, due status, then its stock actions.
3. Choose permitted Purchase/Consume/Spoilage/Open/Transfer/Inventory behavior; reveal only fields needed by that behavior.
4. Submit once; show durable operation state. Undo uses the existing journal capability and only appears when supported/current.

### Shop and book a purchase

1. Open Shopping; select a named list using one control. If no lists exist, show **Create list** as the empty state's primary action.
2. Tap Add item; choose an existing product or existing note-item path, quantity/unit, then save. Editing stays on an editor stage.
3. At store, enable the existing At the store mode; check completion or tap **Add to stock** (current Bought it purchase review). Marking done and stock booking remain distinct.
4. Review stock quantity/conversion, due date/location/price and any reservation/status; confirm purchase once.
5. Show existing pending/confirmed/conflict status against the item. Conflict review explicitly compares saved/your/server values; keep server or review merged edit. Never replay a purchase from this review.

### Cook from a recipe

1. Meals → Recipes → named recipe. Desired servings scales display while preserving base quantities.
2. Optional **Add missing ingredients** opens the existing shopping-list choice and adds only shortages.
3. **Start cooking** opens instructions/checklist/timers; Back returns to recipe detail. Cooking mode does not consume stock.
4. **Use recipe ingredients** opens consumption review; show each product's required/available quantity in stock units.
5. Confirm consumption once; display operation statuses and supported journal undo. Variable amounts remain blocked until measured/mapped as the current domain requires.

### Plan meals / import recipe

1. Meals → Meal plan → Add meal; select day, existing section, recipe/product/note and servings/amount; save.
2. Open a meal entry to edit it; recipe entries open their named recipe with origin retained.
3. Recipes → Import recipe → enter/share source URL → explicit metadata read → edit name/servings/instructions and map every ingredient to existing product/unit → save. No automatic product creation is introduced.

### Complete household work / manage supporting data

1. Household → Chores or Tasks; restricted child sees only assigned permitted records, grouped by actual due date.
2. Open/read instructions; tap **Complete chore** once. Existing online assignment recheck remains authoritative; uncertain completion goes to review rather than replay.
3. Parent create/edit stages disclose recurrence/rotation after the basic chore fields; execution history and deletion remain secondary actions.
4. Household → Batteries → named battery → **Record charge** → confirmed status; history offers permitted undo. Equipment stays a separate named list/editor; server manuals remain in Grocy.
5. Pantry/Shopping/Tasks contextual Manage action → existing named record list → create/edit → field validation → save → durable status.

### Connect / recover

1. Accounts shows saved users first when present; Add account opens URL/key form. Scan key QR stays within connection, with a review before Connect.
2. Optional administrator verification and insecure local HTTP are secondary sections with explanations; no local preference claims account restriction.
3. Connect verifies server and user before saving; cancellation returns to editable form. Switching account clears previous scan/task context and uses isolated cached records.
4. Any Needs review state opens existing Pending changes for the active account. Select operation, read current server state, and show confirmed or manual-review result. The release UI must expose this existing page; never manufacture resend/discard controls unsupported by the domain.

## State inventory and required staging

| Screen | Existing state | Revised state treatment / principle |
|---|---|---|
| Disconnected sections/scan/search | Generic welcome with account-not-connected text and no action | **Connect to Grocy** action opens existing Accounts; conceal list controls until connected. Error recovery / task completion. |
| Today | Busy progress, absent snapshot whisper, denied text, empty chores/meals, raw error | Show loading before empty; separate unavailable/denied from no due chores/no meals. Cards open their object. Visibility of status. |
| Pantry list | Busy progress, stale snapshot whisper, permission panel, inline error, filter-specific empty | Preserve useful cached list during refresh; query/no-stock/filter-empty states name active scope. Empty state must not imply records do not exist. |
| Product detail | Missing `/stock/products/id` or `product` silently returns; selected screen retains Back button | Show loading, failed load with retry, or product removed; never an apparently blank detail. Visibility of status/error recovery. |
| Stock activity/locations | Empty locations, no journal-empty message | Explicit **No stock activity** / **No locations**; supported management action where permission permits. Recognition. |
| Scan capture | Disabled camera, denied optional permission, absent camera/analysis error, empty candidate collection, raw manual errors | Capture-ready, permission-needed, denied, camera-unavailable, manual-entry, multi-code choice, looking-up are distinct stages with a next action. |
| Scan match/review | Cached source, missing units/locations, denied create, unknown code, raw lookup failure, uncertain create | Separate known match, multiple products, no match, source unavailable, prerequisite missing, queued creation, review required; preserve code/draft and expose recovery. |
| Shopping | Progress, stale snapshot, denied, no lists, empty list, error, checkout, conflict takeover | Loading precedes empty; retained list visibly cached; conflict is scoped to affected item with explicit entry, not an unexplained forced replacement of the entire list. |
| Meals recipes/plan | Progress, stale, denied, empty cookbook/plan, deleted recipe, image/import errors, consumption review | Use object-specific empty action; maintain content when only photo/metadata fails; no claim of empty plan before loading finishes. |
| Chores/tasks | Progress, raw error/denial, “Nothing here” groups, no executions | Omit empty due-date groups; show one overall **No assigned chores** or **No chores due** state, distinct denied/unverified permission state, **No chore history**. |
| Batteries/equipment/catalog | Progress, stale, unavailable reason and unsupported custom-field reason; empty lists/history have no explicit state | Every entity gets singular/plural empty title and permitted create action; empty charge history says **No charges recorded**. Unsupported field explanation stays inside relevant editor. |
| Accounts | Connection progress/cancel, error, no saved accounts, version warning, unverified permissions, active account | Distinguish Connecting/Verification failed/Connected/Permissions unverified; retain server value; never expose keys or QR contents in status text. |
| Settings/reminders | Unsupported dynamic color, reminder permission/save message, disabled save on invalid times | Field-specific time error and clear saved status; preference sections labeled by user effect. |
| Pending changes | No active account, no rows, read/reconcile errors, needs-review state | Initial empty rows are not evidence of completed loading; add actual loading read-state to presentation, preserve account scope, human-readable operation names above technical diagnostics. |

## Severity-ranked IA fixes

| Severity | Exact place | Fix | Principle |
|---|---|---|---|
| Blocker | Scanner `create()` error; RecipeScreen needsReview; `StillroomShell.kt` DEBUG Settings link | Make existing Pending changes recovery reachable from release and from each Needs review state | Nielsen 9; task completion |
| Major | `ScannerScreen.kt` candidates/result/product branches | Single detection → lookup; single matched product → focused action; multi choices only when necessary | Cognitive load; progressive disclosure |
| Major | `ScannerScreen.kt` embedded StockScreen; `StockScreen.kt` Back to pantry and StockDetail | Remove embedded browse escape; action form above secondary product data; explicit scanner back stack | Consistency; task continuity |
| Major | `StockScreen.kt` StockForm scanner due-date label | Required scanner due date receives required label/error; preserve entered date after validation | Error prevention |
| Major | `StillroomShell.kt` Search branch; `StockScreen.kt` filter logic | One product search route; names/barcodes include zero-stock products | Match real-world model; recognition |
| Major | `CatalogScreen.kt` HouseholdHub/CatalogEntity | Place named supporting data near dependent task and remove Batteries & more catch-all | Information scent; grouping |
| Major | `StillroomShell.kt` BackHandler and screen-local editor/detail state | Back pops current task stage before leaving section | User control; consistency |
| Major | Product detail missing data; catalog/history empties; pending initial empty rows | Specify loading/error/removed/empty separately; valid empty only after successful read | Visibility of status |
| Minor | Pantry chip strip Journal/Locations beside status filters | Secondary labeled destinations distinct from product filters | Consistency; grouping |
| Minor | `ShoppingScreen.kt` list-creation controls and grouping values | List picker/create disclosure; explicit Group by label, No grouping copy | Progressive disclosure; recognition |
| Minor | `TodayScreen.kt` cards and Meals This week | Open named object with origin; use Meal plan unless data is actually limited to week | Match real-world model; accurate labels |
| Minor | Generic Nothing here / record / Queue purchase / read server state | Object-specific labels and action/result language | Nielsen 2/9; recognition |

## Removed or restaged

- Remove duplicate standalone product Search screen; retain its capability in canonical Pantry search.
- Remove forced single-code and single-product choice screens; retain multi-selection for genuine ambiguity.
- Remove full stock browse/history from the main scanner action stage; existing information remains behind product details/activity.
- Remove Batteries & more catch-all navigation; existing entities receive named contextual destinations.
- Remove main-list New list name form; existing Create list lives in list selection/empty state.
- Remove API paths/operation IDs from primary hierarchy of Pending changes; keep them as diagnostics so user-readable operation/status leads.
- Remove fictional “This week” scope if the current unfiltered meal data includes other dates; label Meal plan or actually apply the existing date-view scope only if authorized by synthesis.

These changes restage current objects and operations. They introduce no scanner auto-booking, new barcode provider, arbitrary QR-link navigation, new ingredient mapping capability, new backend, new child permissions, batch inventory mode, or new outbox retry semantics.
