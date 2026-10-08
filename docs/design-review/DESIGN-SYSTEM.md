# Stillroom: engineering design specification

2026-10-07. Native Android/Compose/Material 3. Existing Grocy capabilities only. This is the reconciled specification; the six specialist reports preserve evidence and alternative recommendations. Review basis and object inventory: [INVENTORY.md](INVENTORY.md). Implementation and validation notes are recorded in [STATUS.md](../STATUS.md); do not treat this specification as proof that a physical camera or TalkBack test passed.

The user rejected the overly neutral visual result after seeing it on their phone. The current direction restores the existing Today/empty-state photography, cream/clay surfaces, meaningful amber/sage status color and softer shapes. Specialist audits remain historical evidence; their recommendations to remove these images or flatten the palette are superseded. Scanner and task-flow corrections remain.

## 1. Design principles

1. **Keep the household job visible.** A scanner session records groceries added or used. Choose that job before capture; keep it visible through product review. A single code or product is resolved automatically; ambiguity requires a choice. Principle: Jobs-to-be-Done, recognition over recall, Nielsen efficiency.
2. **Show the truth about Grocy.** A detected barcode, saved draft, transmitted request and confirmed change are different states. Show success only for the submitted operation's confirmation; retain uncertainty and its recovery route. Principle: visibility of system status and error prevention.
3. **Put the next decision first.** Product quantity and the active stock form precede history and configuration. Routine lists lead with their items; creation and advanced options are disclosed when needed. Principle: progressive disclosure, cognitive load, Gestalt proximity.
4. **Use one visual grammar.** Warm cream/clay surfaces, a recognizable brown action accent, amber/sage status roles, repeatable rows and named controls across stock, shopping, recipes and household work. Principle: consistency and recognition.
5. **Make the task usable with the phone in hand.** 48dp targets, scalable text, named controls, reachable actions with the keyboard open, manual camera fallback, system Back and reduced motion. Principle: accessibility and user control beat visual density.

## 2. Token sheet

| Color role | Light | Dark | Use |
|---|---|---|---|
| Canvas | #FBF6EF | #211B18 | Page and app bar |
| Group | #F3E8DB | #302620 | Bounded object/form/status |
| Raised surface | #EEE0D2 | #392D25 | Menus and modal hierarchy |
| Text | #30251E | #F7EEE4 | Titles, names, body, quantities |
| Supporting text | #66584B | #D0BAA7 | Dates, units, secondary information |
| Accent | #8B4A32 | #E8B4A0 | Primary action, current selection, focus |
| On accent | #FFFFFF | #211B18 | Filled button content |
| Current-state container | #EED9C8 | #52382B | Selected navigation/control |
| Control outline | #806C5B | #A38C7B | Input/control boundary |
| Divider | #DFCEBD | #554438 | Structural separation only |
| Error | #9B2C2C | #FFB4AB | Error/destructive consequence, always named |
| Error group | #FFF1F0 | #3B1C1A | Persistent error context |

Retain system/light/dark and the existing wallpaper-color preference. Wallpaper contributes a single contrast-checked primary accent; the warm surfaces and semantic errors stay stable. Due/expiring labels use amber (#80531C light / #DBBD88 or #E8BF82 dark); stocked labels use sage (#4D6548 light / #ABC79E dark), always with readable text. Expired/overdue warning text may use the error role with a named condition. Keep button and input text at AA contrast; disabled controls remain understandable and explain their missing requirement nearby.

One family: Android sans-serif. Sizes are sp and scale with accessibility settings. Roles: large quantity 32/40 medium; page title 24/32 semibold; section/dialog title 20/28 semibold; body/row title 16/24 normal or medium; controls/support 14/20 normal or medium; optional metadata 12/16 normal. Explicitly map every Material typography slot to this scale; no off-scale inherited typography.

Spacing is dp: 4 within label/value groups; 8 between related controls; 16 content gutters, card padding and field groups; 24 between sections; 32 larger separation; 48 target minimum. Rows use flexible height, normally at least 64dp; never lock text to a fixed height. Keep large-font navigation labels readable using the existing adaptive layout. Content panes center within a 720dp maximum width on wide displays. The same 16dp gutters keep lists and forms aligned.

Radius: 12dp input corners; 24dp buttons/cards/standard dialogs; 32dp large modal shapes. Platform selected navigation indicators retain their accessible shape. Border: 1dp control outline; focus visible in the accent role. Elevation: 0dp content/navigation/cards; use the platform modal/menu elevation consistently. No ornamental shadows, gradients or glass. Motion is functional, 150–250ms ease-out; immediate transitions when reduced motion is active. No animation is needed solely to refresh a count.

## 3. Component rules

| Component | Structure and states | Governing principle |
|---|---|---|
| Buttons | One filled primary per task. Specific verbs: Confirm add, Confirm use, Add item, Save item, Save recipe. Secondary actions outlined or text. Min 48dp; icon-label gap 8dp. Material hover/focus/press feedback. Submission locks duplicate actions; label keeps job context. Errors preserve entered values. | Hierarchy; error prevention |
| Inputs | Persistent label above field, 8dp gap, full form width. Accessible name equals visible label. Optional/required truth follows the active operation. Validation immediately beneath its field, with error semantics. Fractions accepted for quantities; prices remain decimals; barcodes remain strings. | Recognition; WCAG labels/errors |
| Selectors | Above-field label; named current value. Open a bounded searchable chooser for products, units, locations, stores and assignees. Bind selection to ID, never display-name identity. Explicit None/Product default where domain allows. No matching options is a distinct state. | Recognition; bounded cognitive load |
| Lists | Name and primary quantity first; due date/unit/status beneath or adjacent. A whole row opens its object. Check rows expose one named toggle; independent trailing edit has its own target. Waiting is unchecked/disabled with text, not visually completed. Preserve readable completed names. | Gestalt; truthful state; target size |
| Navigation | Today, Pantry, Shop, Meals, Household remain the five existing sections. Icon and label; current state uses accent. Search is product search and includes zero stock. Scan is visibly labeled. Records replaces Batteries & more. Back reverses the current local task before leaving its section. | Information scent; consistency; user control |
| Dialogs | Named object and consequence, Cancel plus one committing verb. Scroll body with reachable actions. Long edit/import forms retain an explicit title and section grouping. Deletion/logout require consequence confirmation. | Error prevention; focus order |
| Status/toasts | Persistent inline status for offline, waiting and uncertain writes; polite announcement once on change. Saved in Grocy only when the exact operation confirms. Transient success must not be the only receipt. Recovery routes open existing Pending changes; no uncertain-write Retry. | Visibility; recovery |

Default, hover, focus and pressed feedback use Material interaction indications; do not invent per-screen effects. Disabled states say what is missing. First-load states name the operation; refresh keeps existing content. Reduced motion substitutes a static status for rotating or indeterminate progress. Camera detection feedback means a code was read, never that stock was saved. Detailed state/timing matrix: [interaction-design.md](interaction-design.md).

## 4. Screen-by-screen redesign

### Shell and disconnected states

Warm cream/clay app bar; section title in primary text; remove the permanent accent rule. Bottom navigation on phones, sidebar on tablets, existing scrolling labeled navigation for large text. Scan is an icon plus visible Scan label. Universal search/back/settings icons retain explicit screen-reader names. Global search says Search products and shares Pantry's product results, including zero-stock products. Utility recovery returns to the task that opened it.

Disconnected: Connect to Grocy / Connect an account to see your household. The Connect action opens Accounts. Do not describe an empty household before a connection exists. Permission failure: Access unavailable / This account does not have permission to view this section. Offline cached data remains visible and labeled. First-load failure replaces only unavailable content; refresh errors accompany preserved content.

### Today

Time-of-day greeting and date, then the original breakfast/lunch/dinner photograph in a 200dp-high, 24dp-rounded frame, followed by due chores and today's planned meals. Photography provides the welcoming household character; planned meals are separately labeled records rather than inferred from the image. Chore rows show name/due; meal rows show actual name/section/servings. Existing section links remain quiet. Empty says No chores due today or No meals planned today only when reads are available; missing/loading data says Loading today's records or identifies unavailable content. This screen's job is orientation, so it need not manufacture a filled action.

### Pantry list, locations and history

Search above stock filters: All, In stock, Use soon, Running low, Opened. Locations and History are labeled secondary destinations, distinct from product filters. Product row: name, quantity/unit, named due condition. Keep existing attention grouping but do not repeat the same product in multiple attention sections. Searches, filters and truly empty stock have different messages. Clear filters or change the query without discarding the search unnecessarily.

Locations show names and lead into products at that location. History shows product/action/quantity/date/location, then supported Undo. Use resolved names and formatted values; hide booleans, API paths and internal IDs from the main line. Undo is an explicit server action; never promise support if the API or permissions do not offer it.

### Pantry product and stock form

Title → stock quantity/unit → active form → supporting product metadata/history. Show a loading or recoverable unavailable-product state rather than returning a blank screen. Existing actions retain their operation semantics: Add stock, Use stock, Mark opened, Move stock, Set stock total, Record spoilage. Only relevant fields appear. Quantity/unit first; source/destination for transfer; due date and price only where supported. Inline validation identifies the specific invalid field. Metadata and journal remain available below the action rather than blocking it.

### Scanner capture, review and repeat

Grocy Android's [documented scan-mode workflow](https://github.com/patzly/grocy-android/blob/master/FAQ.md) selects an action before scanning. Adopt that task structure using Stillroom's existing Purchase/Consume operations. No GPL implementation is copied.

1. Open Scan products. Select Add stock or Use stock; preserve that choice for the session.
2. Start camera explicitly. If permission is denied or hardware unavailable, Enter barcode remains available. Camera target, flash and zoom are named controls. Manual format choices are disclosed inside manual entry.
3. One valid code starts lookup; several distinct codes require Choose a barcode. One matched product opens the focused stock review; several products require Choose a product. Preserve significant leading zeros.
4. Review product, current stock, quantity and unit. Add stock requires a package due date; no guessed expiry. Location/price follow. Use stock omits purchase-only fields.
5. Confirm add or Confirm use performs the existing durable operation. Lock further submission while that operation is waiting or uncertain. Receipt binds to its operation ID, not an unrelated historical success or the absence of a spinner.
6. Show Saved in Grocy, Waiting for Grocy/Waiting to sync, or Result uncertain with Review changes. Preserve all existing account isolation, permission, offline and no-blind-replay boundaries.
7. Scan next returns to the previously enabled camera without another permission/activation step. Repeated frames stay suppressed. Scan same code again deliberately rearms the last code; an unresolved stock operation cannot be resubmitted merely by changing barcode.

Capture pauses during review, including turning off flash, while the user's chosen camera settings remain available on return. Back cancels the current read/review without creating stock. Submission cannot be abandoned as if no operation exists. Unknown-code creation has its own name/description/unit/location review; source suggestions remain editable and do not supply stock or expiry. Create product remains separate from adding stock. A durable but unconfirmed creation disables another creation attempt and exposes Review changes. Linking an unknown barcode to an existing product is not implemented and is not added here.

### Shop lists, in-store view, item form and purchase

List selection first, then Add item and existing grouping. Create list is disclosed rather than an always-visible input. Rows show name, quantity/unit, note and specific sync state. Completed rows are distinct from pending operations. At the store increases targets and keeps the supported Add to stock action reachable; ticking a shopping row and booking stock remain different operations.

Add/edit uses product-or-text choice, quantity/unit and note; Add item/Save item is the single primary. Preserve precise unchanged quantities through note/unit edits. Purchase review identifies actual stock quantity, location, date, price, store and note. Keep due-date policy consistent with its existing domain; do not prefill today as a package expiry. Confirm purchase is explicit. Conflict review shows Original, Your change and In Grocy as labeled values, with Keep Grocy version or Review merged edit. Purchases with uncertain outcomes never resend from this view.

### Meals: recipes, meal plan, recipe detail and cooking

Use Recipes and Meal plan. The existing plan can contain any date; do not label it This week unless it is actually week-filtered. Display dates/sections on entries. Recipes retain actual user photos where available; missing photos use a text row rather than a stock image. Add recipe and Import recipe disclose separate forms. Library empty: No recipes yet / Add a recipe or import a link.

Detail: recipe name and servings, ingredients/availability, Start cooking primary. Edit, photo management, add missing items and Finish cooking are quiet, explicit actions. Servings save retains its existing precision and scaling semantics. Cooking is a distinct task view: labeled ingredient checklist, Step n of total, current instructions, Previous/Next and existing timers. Local checklist completion never consumes stock. Finish cooking opens the existing consumption review; only Confirm consumption books quantities. Show each product's required and available stock and any unavailable consumption action.

Recipe/ingredient/meal editors group basic values before advanced fulfillment flags. Date and type are explicit in meal editing; recipe/product/note remain existing choices. Import review keeps source, required base servings, instructions and per-ingredient product/unit/amount mapping; excludes are deliberate, products are never created implicitly. Photo review shows the selected user image before saving. Keep long reviews scrollable and keyboard-safe.

### Household: chores, tasks and editors

Chores, Tasks and Records are named subsections. Chores group Overdue, Today and Upcoming; restricted users see My chores and actual permitted actions. Name/due/assignee lead; Complete is a named toggle or action. Pending completion remains unchecked with Waiting for Grocy; do not turn waiting into Done. Tasks group by their actual category with assignee/due values.

Editors order name/instructions, recurrence or due date, then assignment. Show recurrence-specific fields only for the chosen period; weekday names replace numeric values. Advanced existing recurrence options keep concise help. Preserve original account/grant boundaries. Histories use date/person/status; empty says No completions yet. Delete identifies the chore/task and consequence. Missing permissions/users are contextual and do not masquerade as empty lists.

### Records and record editors

Replace Batteries & more with Records. Keep existing entity names: Batteries, Equipment, Products, Locations, Stores, Units, Unit conversions, Task categories. Each entity has a real empty state and a singular add/edit verb. Batteries show Used in, Last charged, Next charge; Record charge is distinct from edit. Charge history has dated outcomes and supported Undo. Equipment shows its actual manual reference and clarifies availability in Grocy.

Products group identity/location, units/conversions and stock rules, then existing advanced flags/custom fields. Conversion reads Product or All products, From unit, To unit, Factor. Unsupported custom fields remain preserved and explain why the app cannot edit them; do not fabricate controls. Deletion explains referenced records may prevent it. Catalog loading/errors are independent of an entity having no records.

### Accounts, Settings, Pending changes and widgets

Accounts leads with saved users/server/current state; Add account discloses connection form. Labels Server URL and API key stay above fields; key masked and never put in saved instance state. Optional QR capture fills values for explicit review/connect. HTTP opt-in remains explicit and explains its consequence. Administrator permission verification remains available only with an actual saved administrator on the same server. Logout confirms removal of this phone's saved account/cache; Grocy records remain server-side.

Settings groups navigation, reminders, appearance and recovery. Open Household first replaces Child landing and clearly says it changes the opening screen rather than account access. Quiet hours include HH:mm guidance and validation. Retain dynamic-color/reduced-motion preferences. Review pending changes is available in release builds.

Pending changes leads with active account and plain operation/status. Check Grocy is a read/reconciliation, never replay. Technical method/path/reference are behind Show details. Loading, no changes and unavailable changes are distinct states. Direct scanner recovery opens this page and Back returns to the scanner.

Chore/shopping/scan widgets use warm system light/dark surfaces, 16sp readable content, 48dp action, actual account and cached-state wording. Reminders retain existing once-daily/quiet-hours behavior; copy describes due chores and food to use without suggesting new background capabilities.

## 5. Severity-ranked issues

| Severity | Screen and problem | Fix / principle |
|---|---|---|
| Blocker | Release Settings hides the Pending changes recovery referenced by Scanner/Meals | Expose recovery and route directly from scanner; Nielsen visibility/recovery |
| Major | Scanner next/repeat clears unresolved product-creation context | Retain operation identity, disable rearm, offer read-only status checking; error prevention/visibility |
| Major | Toolbar Back bypasses local review and disagrees with system Back | Use the same dispatcher and preserve the originating task; consistency/user control |
| Major | Failed collection reads show successful-empty copy | Separate loading, unavailable, cached and empty states; visibility/error diagnosis |
| Major | Local spinners bypass the reduced-motion preference | Shared static loading feedback when requested; accessibility/consistency |
| Major | Scanner single code and product each demand a redundant tap | Resolve only unambiguous cases; efficiency/recognition |
| Major | Scanner returns to disabled camera between packages | Preserve enabled session and pause analysis; user control/efficiency |
| Major | Stock booking result can be mistaken for lookup success or earlier operation | Track submitted operation ID; visibility/error prevention |
| Major | Scanner review contains full Pantry browser and metadata ahead of action | Dedicated review with quantity/form first; JTBD/progressive disclosure |
| Major | Scanner date says optional while gating submission | Required label plus inline explanation; consistency/error prevention |
| Major | Fixed scanner content and keyboard make controls unreachable at large text | Scroll, scalable layout, IME insets; WCAG resize/focus visibility |
| Major | Checkboxes/zoom have detached or missing names | Named merged toggle, distinct edit, zoom semantics; WCAG name/role/value |
| Major | Waiting chore/task checkbox looks completed | Unchecked pending plus status, server-confirmed outcome; system-status truth |
| Major | Long product/unit/location chip strips demand hunting | Searchable named bounded choice; recognition/cognitive load |
| Major | Generic errors or disabled submit do not identify invalid input | Field-specific validation and preserved values; error diagnosis |
| Major | Urgency badge uses low-contrast normal text | Tested semantic/neutral pairs with named status; WCAG contrast |
| Minor | Global product search duplicates stock-only filtering | Search all products through same result component; IA/wayfinding |
| Minor | This week renders plan records outside that week | Meal plan plus visible dates; match to actual model |
| Minor | Batteries & more obscures record types | Records with concrete entity names; information scent |
| Minor | Overly flat restaging stripped the household character | Restore Today/empty imagery, warm surfaces, softer shapes and named status color; user preference/recognition |
| Minor | Whimsical state copy hides operation and outcome | Specific loading/pending/error copy; Nielsen real-world match |

Full evidence-ranked findings: [UX heuristics](ux-heuristics.md), [IA](information-architecture.md), [visual design](visual-design.md), [interaction](interaction-design.md), [accessibility](accessibility.md), [copy deck](ux-writing.md). Accessibility criteria follow [WCAG 2.2](https://www.w3.org/TR/WCAG22/) as an audit framework, with native Android dp/sp, keyboard and TalkBack behavior tested separately. A source audit does not certify conformance.

## 6. Removed, and why

- Unrelated recipe thumbnails: recipe identity still uses user photos. Today and empty-state photography is restored as part of the household visual identity.
- The permanent accent rule remains removed. Warm clay/cream surfaces and amber/sage status color are restored; their removal was an overcorrection.
- Single-code and single-product confirmation steps: they resolved no ambiguity. Explicit stock confirmation remains.
- Full Pantry browser within Scanner: it interrupted the capture/review task and introduced misleading Back to pantry navigation.
- Permanent list-creation/import fields: they burdened routine shopping/recipe browsing; existing forms remain on demand.
- Misleading weekly-plan, child-landing, queue and catch-up labels: replacement copy names the actual scope/action/state.
- Main-view API paths, IDs and booleans: technical details remain available where needed for recovery rather than competing with household facts.
- Waiting-as-completed checkbox state: it falsely implied Grocy had confirmed work.

No existing Grocy capability, account isolation, manual scan fallback, permission check or uncertainty protection is removed.
