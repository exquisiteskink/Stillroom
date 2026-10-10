# Household tools, Today, widgets, and reminders

Stage 11 is the last build stage. All records remain in the existing Grocy; no companion backend, external automation, or service deployment was added.

## Chores and tasks

Household has three sub-tabs: Chores, Tasks, and Batteries & more.

Chores is a checklist grouped Overdue, Today, and Upcoming. Checking a row tracks execution in Grocy (`POST /chores/{id}/execute`). Due labels show the calendar day only (`MMM d`). Parents can create, edit, assign household members, and delete chore definitions. Assignment users come from `GET /users` when the key can read users (`USERS`, `USERS_READ`, or `ADMIN`); otherwise names already present on assigned chore or task records are offered. Selecting people on a new chore sets Grocy's assignment type away from `no-assignment`.

Tasks is a separate checklist of open Grocy tasks (`done` is not `1`), grouped by task category. Checking a row calls `POST /tasks/{id}/complete`. Parents with `TASKS` and `MASTER_DATA_EDIT` can create, edit, and delete tasks (`POST`/`PUT`/`DELETE /objects/tasks`) with name, notes, due day, category, and assignee. The Add task and Edit task dialogs offer “New category”: enter a name and choose “Create category” to use Grocy’s `POST /objects/task_categories`. The task draft remains open and the new category is selected after Grocy confirms its ID. Blank names are blocked; an unconfirmed create stays in the existing outbox for review and is not duplicated by another tap.

## Batteries, equipment, and master data

Household's Batteries & more tab lists the server-exposed batteries, equipment, products, locations, stores (`shopping_locations`), units, conversions, and task categories. The authenticated OpenAPI exposed-entity list gates availability. A missing entity has a visible unsupported-server reason. Editors cover names/descriptions and the relevant battery interval/usage, location freezer flag, product unit/location/store/stock settings, plural unit name, and conversion references/factor. Additional server fields, including equipment manual filenames, are preserved when editing; manuals can still be managed in Grocy.

Create/edit/delete use the existing account outbox and show pending versus confirmed or needs-review. Referenced rows and required custom-field definitions are checked against Grocy before saving. A unit conversion can apply to a selected product or be explicitly global. Factors use decimal storage with the existing fraction input/display boundary. Charge cycles use `/batteries/{id}/charge`, history uses `battery_charge_cycles`, and supported undo uses `/batteries/charge-cycles/{id}/undo`. The next estimated charge date is read from Grocy; no local recurrence date is calculated.

Read access requires the feature's explicit permission or `MASTER_DATA_EDIT` for native master records. Edits require `MASTER_DATA_EDIT`. Battery status/history and charge/undo additionally require their battery grants. Stock, shopping, and task grants permit read-only access to related master records. Unknown grants and chore-only child keys do not expose master records. A server 401/403 stops cache fallback. A known authorization denial also hides cached background content until account verification succeeds again.

Custom fields (Grocy userfields) use one shared typed editor for every record type and for Pantry → Add/Edit product. It covers Grocy's 12 value types (`text-single-line`, `text-multi-line`, `number-integral`, `number-decimal`, `number-currency`, `date`, `datetime`, `checkbox`, `preset-list`, `preset-checklist`, `link`, `link-with-title`). `file` and `image` are shown by file name and kept unchanged; changing them needs Grocy's binary file API. Unknown types are shown with a reason and kept. The earlier non-Grocy names `text`, `numeric` and `date-time` are read as `text-single-line`, `number-decimal` and `datetime`. Only changed values are sent, via `/userfields/{entity}/{id}`, as deterministic outbox follow-ups after the native row is confirmed. A required file/image field blocks creation with a reason. Interrupted outcomes are not automatically submitted again. The editor stays open until Grocy confirms the save. See [PRODUCT_EDITOR.md](PRODUCT_EDITOR.md).

## Today and background reads

Today reads account-scoped Room cache records for due/overdue chores and today's native meal entries/sections. Child chores are filtered by Grocy's assigned user ID. Opening Today shows the last cache immediately, then refreshes permitted resources from Grocy. Pull down on a screen to refresh again. A child can open Today while Household remains its landing page. Kitchen reminders live in Settings. Low stock and due/overdue/expired food stay on Pantry as Running low and Use soon.

Background cache readers use the saved preferred account and its verified grants. They do not log in, call Grocy, drain the outbox, or recover an in-flight foreground mutation. Publication rechecks account identity and permissions under the same lock used for switch/logout. An old worker cannot republish another account's content. Widgets/notifications are cleared on switch/logout.

Today also has a personal Tasks checklist backed by the same `/tasks` cache and household completion queue. It includes every open task assigned to the active Grocy user plus tasks with no assignee (Everyone), including future and undated tasks. Administrators use the same personal filter; tasks assigned to other members stay in Household. Rows show due dates and notes, and can be checked off with `TASKS_MARK_COMPLETED`. Pending/unknown completions display “Waiting to sync” and disable repeat submission. Completion removes the row after a refreshed server response reports it done. “Manage tasks” opens Household directly on its Tasks tab. Task data and completion controls remain gated by the current account, its verified grants, and account-specific cache; missing or denied data shows an unavailable state.

## Widgets and reminders

Four Android home-screen widgets show cached due chores, personal open tasks assigned to the account user or Everyone, outstanding shopping counts per list, and an entry to scan/manual input. Taps open the corresponding existing screen after account verification, including on a cold launch. They never complete chores or book stock from a launcher tap. Denied accounts see no feature data. Android RemoteViews use immutable PendingIntents; no credentials or household records are placed in intents.

WorkManager 2.10.1 runs one unique periodic cached-data job with a 15-minute interval and unique on-demand widget updates. There are no background network calls or mutations. Reminders are opt-in under Today, require Android notification permission when applicable, and cover cached due chores, personal tasks due today or overdue, and expiring/expired food. Quiet hours use the phone's local time, include intervals crossing midnight, and treat equal start/end as all-day silence. Quiet work does not mark a notification delivered, allowing later work outside quiet hours. At most one reminder per account per calendar day is posted. Delivery is inexact and can be delayed by Android battery restrictions. Disabling reminders does not disable widget cache updates.

## Validation and later phone work

`mise run stage:11` uses the already-running local synthetic Grocy on port 9283. It verifies one native battery charge cycle/history/next-date response, undo, native master CRUD, a product conversion factor of 2.5 and an edit to 3, equipment custom-field storage, cache-only reads, and background outbox isolation. It also runs regression/coverage/build/lint checks. The gate creates and removes synthetic records and revokes its temporary test key; it starts no containers.

WorkManager notification behavior and scheduling are checked with Robolectric/work-testing; domain tests cover permissions, assignment filtering, cached meal/stock data, custom types, and quiet hours. These are not physical launcher, camera, battery-scheduling, or notification-delivery tests. [PHONE_TEST.md](PHONE_TEST.md) describes the later real-device tests; all phone results are NOT RUN. Existing upstream child-key access gaps remain in [COMPATIBILITY.md](COMPATIBILITY.md).

Primary platform references: [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work), [Android widgets](https://developer.android.com/develop/ui/views/appwidgets), [Grocy API](https://demo.grocy.info/api).

## Priority-one task tools

Today supports Add task with current-user/Everyone assignment and inline category creation. Today and Household offer category filters and Open/Completed views; completed tasks can be reopened with the native undo permission. Pending completion or undo blocks duplicate actions. Today retains its personal assignment filter in both views. See [PRIORITY_ONE.md](PRIORITY_ONE.md) for persistence, permissions and validation limits.
