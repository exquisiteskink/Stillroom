# Priority-one extensions

Authorized on 2026-10-09: personal task tools, persistent cooking timers and a reviewed shopping-trip scanner. Grocy remains authoritative. No new service or integration deployment is required.

## Personal tasks

Today offers Add task for accounts with TASKS and MASTER_DATA_EDIT. Its editor uses the existing native task/category APIs, with assignment restricted to the current user or Everyone. Category creation retains the draft and selects Grocy's confirmed category ID. A confirmed task create closes the editor before refreshing, preventing another create if that refresh fails.

Today and Household offer category filters and Open/Completed views. Completed records are loaded from `/objects/tasks`; reopening uses `POST /tasks/{id}/undo` and requires TASKS_UNDO_EXECUTION. Pending completion or undo blocks another action on that task. Today applies its personal filter to completed tasks too; Household remains the broader household management view.

The new Stillroom tasks home-screen widget shows cached open tasks assigned to the preferred account user or Everyone. Its tap opens Today after normal account verification. It never changes task state. The existing opt-in daily reminder now includes personal tasks due today or overdue, excluding undated/future/completed tasks. Quiet hours, notification permission, account publication fences and the once-per-account/day limit remain in use. Widgets/reminders read cache and do not fetch Grocy in the background.

## Cooking sessions and timers

Cooking saves the recipe ID, current step, ingredient checklist and timer deadlines locally per account. Resume cooking appears in Meals and on the recipe page. Finish cooking session clears saved progress; existing timers keep running until dismissed. Ingredient checks, timers and session completion do not consume stock.

Android AlarmManager delivers timer notifications outside the screen/process lifetime. Deadlines use elapsed time during the same device boot and a saved wall deadline after reboot. Boot, package replacement and exact-alarm permission broadcasts restore schedules. Account switches cancel inactive-account alarms/notifications and restore the active account's timers. Logout removes its timers and cooking progress.

Android 12+ exposes an optional Allow precise timers action using SCHEDULE_EXACT_ALARM access. Without it, timers use inexact alarms and the screen explains possible delay. Android 13+ requests notification permission when starting a timer. Notification delivery also depends on the system/channel settings and battery policy. Denied notifications leave the finished timer visible in the app. Notifications use private visibility and only open cooking for their owning active account. They never book stock. Force-stopping an app can suspend alarm delivery until the app is opened again.

## Shopping trips

Scanner → Shopping trip keeps an account-specific persistent draft. Known single-match barcodes append suggested quantities without booking stock. Exact barcode amounts and unit conversions are retained; saved total prices are converted to price per stock unit. Repeated camera frames stay suppressed; Scan same code again explicitly permits another identical item. Unknown or ambiguous products use the existing product choice/review workflow.

Review scanned items pauses camera input. Quantities, package dates and prices can be edited; amounts are in the product's stock unit. Tare-weight products show gross quantity and require it to exceed the saved tare weight. A blank date explicitly uses Grocy's default due date. Draft items can be removed and the draft discarded before submission. These are standalone pantry purchases: use either this trip review or Shop's purchase checkbox for the same purchase. This change does not automatically match or complete grocery-list rows.

Confirm purchases first validates all remaining products online, including their reviewed stock units and tare settings. Changed settings block submission before any purchase. All lines receive persistent operation identities and are durably queued before any HTTP purchase. Trip operations are guarded: ordinary sync/account activation does not send them. Confirmation sends one line at a time, displays confirmed versus unsent/uncertain results and stops at the first unconfirmed line. Purchase ownership must be Stillroom; external ownership blocks submission.

A submitted trip is immutable. Restarting or confirming it again reuses the same IDs and skips confirmed lines. An interrupted preparation can queue missing lines, and a safe interruption before HTTP restores the guarded state. An uncertain request remains in Pending changes and is not automatically replayed. A new trip is available after every purchase is confirmed. Grocy does not make multiple purchases atomic: already confirmed lines remain booked if a later line fails. Stock journal undo remains the existing correction path.

Camera/manual scanning is supported in this change. Vendor-specific hardware scanner broadcast integrations remain a later extension.

## Validation

See [STATUS.md](STATUS.md) for actual build, lint, host-test and scoped coverage results. Host tests use production Room/outboxes, MockWebServer, pure domain checks and Robolectric Compose/Android shadows. They do not establish physical camera, launcher, notification delivery, real reboot or live Grocy behavior.

Protocol/platform references: [Grocy API](https://github.com/grocy/grocy/blob/master/grocy.openapi.json), [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms).
