# Stillroom interaction specification

Basis: actual Compose/ViewModel/domain source, 2026-10-07. No phone or camera test was performed. Principles: Nielsen visibility of system status, user control, consistency, error prevention; recognition over recall; progressive disclosure; WCAG 2.2 AA focus, target size, status announcements; Material 3 state layers. This clarifies existing capabilities; it adds no stock action or integration.

## Interaction contract shared by all screens

| State | Required behavior |
|---|---|
| Default | One filled primary action per task; quiet secondary/text actions. Controls have visible names and 48dp preferred touch height. |
| Hover | Material state layer on desktop/pointer input; no content movement, scale, or tooltip required to understand a control. |
| Focus | Visible focus indicator; screen order matches reading order. Moving between steps puts focus on the new heading or first invalid field. |
| Pressed | Standard Material ripple/state layer immediately; no size change. |
| Disabled | Expose disabled semantics. Place the reason beside the action when a required choice, permission, or unavailable capability disables it. Never use disabled color as the only explanation. |
| Loading | Disable repeated submission immediately. Preserve field values and context. Announce the task ('Finding product', 'Saving purchase'); keep busy feedback at the task, rather than a remote spinner. No optimistic stock count. |
| Success | Announce confirmed server outcome, with product and action. Separate 'Saved in Grocy' from 'Waiting to sync'. Do not report saving merely because a request ended. |
| Error | Preserve values; attach validation to the field. Transport errors have one explicit Retry for reads. Unknown mutation outcome has Review changes, never automatic replay or Retry save. |

Feedback begins in the next rendered frame; visible progress appears while work runs. Transitions are 150–250ms ease-out with no bounce. Motion only explains a step or expanded section; stable rows do not animate on refresh. Reduced motion uses immediate replacement and a static textual loading state. Success/error feedback remains readable and is announced once through a polite live region; validation uses field error semantics.

## Scanner: action first, explicit stock write

Current source: ScannerScreen.kt, ScannerViewModel.kt, CameraScanner.kt, Scanner.kt, StockScreen.kt, StockViewModel.kt. Purchase and Consume are the only scan actions already exposed. Suppress unauthorized actions; if neither is permitted, scanning remains a read task with no booking action.

1. Select Purchase or Consume before detection; keep the chosen action across packages. Select the first permitted action initially. Action changes are quiet segmented controls with explicit labels. No forced Purchase fallback for a consume-only account.
2. Show camera target and optional manual-entry disclosure. Enable camera asks permission on deliberate activation. Denial keeps manual entry usable. Torch reflects actual availability; disabled means 'Flash unavailable'. Zoom is a labelled adjustable control.
3. A single valid unseen code automatically performs lookup. Several distinct valid codes pause analysis and ask 'Choose a barcode', with one radio/list choice per raw code. Do not make every choice a primary filled button. Cancel resumes the target without marking codes seen.
4. A unique product match automatically opens the review form. Several matches ask 'Choose a product' and show product names; do not open a random match. Unknown barcode opens the existing editable creation review only with product-edit permission; otherwise explain the permission and provide Scan next.
5. Product review shows product name, confirmed amount/unit, chosen action, quantity, unit, location and the existing required package date for scanned purchases. Advanced purchase price stays behind a disclosure. Stock entries, price history and journal belong to pantry detail and do not precede this task. Labels must say 'Due date (required)' where the scanner currently enforces a date.
6. Press Purchase or Consume to submit. Detection never authorizes a mutation. Disable submission, Scan next, Repeat package and action switching while the stock request runs. Use stock busy/outcome, not scanner lookup busy, to govern this lock.
7. On confirmed outcome, show 'Purchased [product]' or 'Consumed [product]' and a confirmed quantity if supported by the returned read. Provide Scan next and Repeat package. Returning to capture preserves action, camera permission/activation, zoom and torch setting. Ordinary Scan next leaves seen-code suppression intact. Repeat package deliberately allows that same code again. It never repeats a stock booking automatically.
8. Pending/guarded means 'Waiting to sync'; needs-review means 'Could not confirm this change. Review changes before trying again.' Keep the operation visible and block duplicate submission of that operation. A generic network error cannot safely mean failure after durable enqueue. Definitive failed state shows the failure and allows correction through the existing outbox contract. Confirm the submitted operation identity before showing success; unrelated historical confirmed records must not qualify.

Keep the camera component mounted through lookup, choices and review; pause analysis when not in capture. Merely conditionally hiding CameraScanner destroys its remember state and currently forces reactivation. Dispose and turn off torch when leaving scanner or switching account. Never transfer code, product, field values or pending operation identity across accounts.

If lookup fails before a stock write, offer Retry lookup for the last code; this bypasses new-frame suppression for that explicit read retry only. Current choose() marks the code seen before transport and otherwise makes recovery read as a duplicate. Barcode/QR format choices remain available inside manual-entry options; they do not dominate the camera task.

## Component specifications

| Component | Default and input | Loading/success/error | Principle |
|---|---|---|---|
| Button | Primary is full text verb; secondary outlined/text; focus/ripple remain Material. Minimum 48dp preferred. | During own submission, label names action in progress; preserve width; disable repeated press. Outcome adjacent to form. | Error prevention; visibility of status |
| Input | Persistent label above/within labelled field; supporting unit/format instruction; proper keyboard. Select-all is not automatic after errors. | Inline error names one problem; first invalid field receives focus. Submitted values stay visible until success/review. | Recognition over recall; WCAG labels |
| Choice group | Only permitted choices; selected state plus label and semantics; wrap/dropdown for long choices rather than an undiscoverable horizontal strip. | Disable changes when they would invalidate an in-flight request. | Consistency; error prevention |
| Search | Search label; clear labelled action when value exists; empty matches distinct from an empty account. | Retain current results during refresh; concise read error and Retry. | Visibility; progressive disclosure |
| List row | Whole row navigates to detail; product/work name first, quantity/due/status secondary; labelled trailing action only for a separate task. | Loading preserves rows. Empty state has contextual next step; an error never says empty. | Gestalt proximity; information scent |
| Navigation | Label plus icon, selected semantics, back returns to owning task. Search/scanner/settings stay utilities. | Permission changes remove unavailable actions. Account switch clears prior account context. | Consistency; user control |
| Dialog/editor | Explicit heading; Cancel and one specific submit. Focus contained; Android Back cancels when no submission runs. Destructive confirmation names object and consequence. | Submit disabled while work runs; retain dialog and fields on failure. | Error prevention; WCAG focus |
| Toast/snackbar | Short confirmation for noncritical completed tasks; never carries the only pending/error status. | Pending and unknown mutation outcomes persist in task and Changes. Undo appears only where current server support permits it. | Visibility; recovery |
| Refresh | One labelled refresh per screen; no duplicate refresh call when selecting a product. | Progress beside screen title; stale data labelled as last synced. Do not replace a useful cached screen with an empty spinner. | User control; status |
| Disclosure | Text label names contained information; expanded semantics; no hidden required fields. | 150–250ms ease-out height transition or immediate under reduced motion. | Progressive disclosure |
| Camera | Target carries meaning; instructions outside preview; stopped/permission denied/unavailable distinguished. | Analyzer suspended while a result or booking is active. Manual fallback always reachable. | Error prevention; status |

## Screen-family interactions

- Today: completion acts on the named chore/meal; mark work pending immediately and announce the confirmed result. Data refresh preserves layout. No animated greeting or decorative transition.
- Pantry: selecting a product opens detail; back clears selection and returns to the same filter/search. Show a loading/error state when product detail is absent: StockDetail currently returns silently. In detail, choose one permitted stock action, then fill its fields and submit. Inventory wording describes a new total; transfer labels name origin/destination; undo names the affected booking/transaction.
- Shopping: checkbox completion and stock purchase are distinct actions. Editing retains values across failures. Conflict review shows server/local values together and explicitly names the chosen merge result. Purchase submission waits for its own durable operation outcome.
- Meals: library → recipe → cook checklist. Timer is a labelled action and current time state. Cook completion and stock consumption review stay separate. Import/mapping errors preserve the URL and resolved mappings.
- Household: chore execution and task completion are separate named actions. Recurrence/assignment follow an explicit labelled editor; history is secondary. Repeated execution disabled while request runs.
- Records: list → named record → editor. Delete confirmation names the record; unsupported custom fields are preserved, not silently dropped.
- Accounts: Connect validates URL/key inline; connecting busy cannot be mistaken for success. Use account and Verify permissions are separate verbs. Logout confirmation is proportional to the existing operation, and switching clears account-owned screens.
- Settings: toggles save their actual setting; grouping labels explain purpose. Theme and reduced-motion changes give immediate local feedback without decorative animation.
- Changes: human action/object first, state second, technical method/path/ID under details. Needs-review offers Read server state. The interaction cannot invent discard/replay/retry actions absent from the existing repository. A server read is evidence, not automatic confirmation of every kind of operation.

## Severity-ranked corrections

1. **Blocker — Scanner product review:** Scan next follows scanner busy, while submission uses stock busy. Tie next/repeat/submit to stock request and operation identity, and preserve unresolved outcome. **Principle: error prevention, visibility of system status.**
2. **Blocker — Scanner mutation feedback:** no action-specific confirmed/pending/needs-review state; whole product detail remains active after durable booking. Expose actual submitted operation state and prevent duplicate stock write. **Principle: error prevention and truthful status.**
3. **Major — Camera capture → review → capture:** conditional removal loses enable/zoom/torch state. Keep camera mounted and pause analyzer. **Principle: user control, consistency.**
4. **Major — Scanner start:** task intent chosen only after two avoidable confirmation steps; one code and one product still need separate choices. Select permitted Purchase/Consume first and automatically resolve unambiguous reads. **Principle: cognitive load, recognition over recall.**
5. **Major — Consume-only account:** StockForm defaults to Purchase even if Purchase is not permitted. Select first permitted action and preserve it through scanner review. **Principle: error prevention.**
6. **Major — Failed lookup:** code is marked seen before network lookup; ordinary retry becomes duplicate suppression. Provide explicit read retry without weakening package duplicate suppression. **Principle: recovery, user control.**
7. **Major — Scanned purchase date:** UI says optional while primary stays disabled without a valid date. Required label, format instruction, inline error and adjacent disabled reason. **Principle: consistency, error prevention.**
8. **Major — Product detail:** missing detail returns blank and allows disappearance of task context. Loading/error state with retry and back. **Principle: visibility of status.**
9. **Major — Scanner product detail:** history/prices/entries precede the booking form. Restage existing form as the result; details remain in pantry. **Principle: progressive disclosure, Jobs-to-be-Done.**
10. **Minor — Refresh and choices:** duplicate read triggers, unlabeled raw source values, equally prominent filled choice buttons. Single refresh owner, human labels, quiet selection lists. **Principle: consistency and hierarchy.**
