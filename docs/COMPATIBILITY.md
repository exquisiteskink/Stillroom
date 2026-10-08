# Grocy compatibility and child access — Stage 1

**Child mode needs a proxy on both tested versions.** Separate users and API keys work, but the requested grants do not isolate a child to chores. Unrelated authenticated reads succeed, `CHORES` also enables undo, execution can name another user through `done_by`, and files can be uploaded/deleted. Most unrelated writes are denied. UI filtering is not access control. This is a discovery result, not an implemented proxy or child mode.

## Live evidence and test boundary

Started the pinned fixtures with `mise run grocy:up` on Omarchy. Initial startup was blocked by Docker socket permissions; after the user granted access, both images pulled and started successfully. All data created was synthetic. The existing official Grocy server software and web app were used unchanged.

| Fixture | API base | Live version / DB version | OpenAPI paths | Image pin |
| --- | --- | --- | --- | --- |
| Stable | `http://127.0.0.1:9283/api` | 4.7.1 / 257 | 74 | `linuxserver/grocy:version-v4.7.1@sha256:c0d0d9d22d3a54fe3f779a743baa2d7bab96b73fad5597ff1a631e2be4b863c8` |
| Previous minor | `http://127.0.0.1:9284/api` | 4.6.0 / 255 | 73 | `linuxserver/grocy:version-v4.6.0@sha256:e05cef69d982b8a478abfb5761694ea9fa948449794a5d42a3b830564a513001` |

Both reported PHP 8.5.6 and SQLite 3.53.4. The date in New York was 2026-10-06; response timestamps are 2026-10-07 UTC. Exact request paths, JSON bodies, status codes, and response bodies are archived in [stable responses][R47] and [previous-minor responses][R46], indexed by `label`. [Manifest](evidence/stage1/manifest.json) records provenance and raw document hashes. [SHA256SUMS](evidence/stage1/SHA256SUMS) covers the evidence files.

Live authenticated `GET /openapi/specification` returned 200 JSON on each server: [4.7.1 schema][S47] and [4.6.0 schema][S46]. Live `GET /objects/permission_hierarchy` returned 200: [4.7.1 hierarchy][P47] and [4.6.0 hierarchy][P46]. No GitHub documentation or another Android client's code was substituted for these live documents. API headers, cookies, passwords, and key material are omitted; configuration is restricted to feature flags/default permissions, and error stack traces are omitted. Everything in the response archives is synthetic fixture data.

No phone, emulator, Android sync, or GitHub Actions test ran in Stage 1. These HTTP experiments do not establish full compatibility or web-to-phone synchronization. No app screens, networking, database, credential storage, or proxy were built.

## Users, key creation, and permission inheritance

Each fixture has a separate `stillroom_stage1_parent` (user 2) and `stillroom_stage1_child` (user 3). Created users through the official `/users` API, replaced default permissions through `PUT /users/{id}/permissions`, and generated each user's key through its own authenticated web session at `/manageapikeys/new`. Creation redirects returned 302; final keys authenticated `GET /user` as the correct separate owners (200). These were default API keys, not calendar-sharing keys. Final parent grant is `ADMIN` (ID 1); final child grants are exactly `CHORES` (ID 10) and `CHORE_TRACK_EXECUTION` (ID 24). Synthetic fixture keys remain in Grocy's isolated volumes, never in this repo. Bootstrap and superseded probe keys were revoked. [R47] / [R46], `parent-key-create`, `child-key-create`, `parent-final-key-identity`, `child-final-key-identity`, `parent-explicit-grants`, `final-child-explicit-grants`.

The `feature-config` response reported `DEFAULT_PERMISSIONS: ["ADMIN"]`. **Creating a child without replacing its default grants would accidentally create an admin.** Both servers accepted `PUT /users/3/permissions` with `{"permissions":[10,24]}` (204), and the subsequent read returned only those two explicit rows. Grant management is admin-only; the child gets 403 for `GET /users/3/permissions`.

The hierarchy is identical across these two versions. The following is a transcription of the live hierarchy, not a proposed Android role model. [P47] / [P46].

| Parent permission | Direct children |
| --- | --- |
| `ADMIN` (1) | `USERS` (2), `USERS_EDIT_SELF` (6), `STOCK` (7), `SHOPPINGLIST` (8), `RECIPES` (9), `CHORES` (10), `BATTERIES` (11), `TASKS` (12), `EQUIPMENT` (13), `CALENDAR` (14), `MASTER_DATA_EDIT` (30) |
| `USERS` (2) | `USERS_CREATE` (3) |
| `USERS_CREATE` (3) | `USERS_EDIT` (4) |
| `USERS_EDIT` (4) | `USERS_READ` (5) |
| `STOCK` (7) | `STOCK_PURCHASE` (15), `STOCK_CONSUME` (16), `STOCK_INVENTORY` (17), `STOCK_TRANSFER` (18), `STOCK_OPEN` (19), `STOCK_EDIT` (20) |
| `SHOPPINGLIST` (8) | `SHOPPINGLIST_ITEMS_ADD` (21), `SHOPPINGLIST_ITEMS_DELETE` (22) |
| `RECIPES` (9) | `RECIPES_MEALPLAN` (23) |
| `CHORES` (10) | `CHORE_TRACK_EXECUTION` (24), `CHORE_UNDO_EXECUTION` (25) |
| `BATTERIES` (11) | `BATTERIES_TRACK_CHARGE_CYCLE` (26), `BATTERIES_UNDO_CHARGE_CYCLE` (27) |
| `TASKS` (12) | `TASKS_UNDO_EXECUTION` (28), `TASKS_MARK_COMPLETED` (29) |

An explicit `CHORES` grant enables its descendant undo permission even though ID 25 is absent from the explicit grant list. With only ID 24 temporarily granted, the same child key's undo request returned **400**, with `error_message: "Permission missing: CHORE_UNDO_EXECUTION"`; restoring `[10,24]` made undo return **204**. Stock reads remained **200** even with only ID 24. The exact requested grant is accepted, but cannot mean “track only, no undo.” Final grants were restored to `[10,24]`. [R47] / [R46], `track-only-grant`, `track-only-explicit-grants`, `track-only-child-undo`, `track-only-child-stock`, `restore-child-grants`, `full-child-grants-undo-control`.

## Child authorization probes

Calls used a key header alone, without a parent session cookie. `/user` confirms child user 3. A deliberately invalid key returned 401 for `/stock`, so successful child reads were not anonymous fallback. Stock, shopping, recipes, master data, tasks, equipment, batteries, user fields, and custom objects contained synthetic records before the probes. Some dependent views were empty; their 200 results alone do not prove field-level data exposure. [R47] / [R46], `bad-key-control`, `child-identity`, and the response bodies below.

| Child request | 4.7.1 | 4.6.0 | Result / response label |
| --- | --- | --- | --- |
| `GET /stock` and `/stock/products/{id}` | 200 | 200 | Unrelated stock readable; `child-stock`, `child-stock-detail` |
| `GET /objects/shopping_list` | 200 | 200 | Product and note-only rows readable; `child-shopping` |
| `GET /recipes/fulfillment` and `/recipes/{id}/fulfillment` | 200 | 200 | Recipe fulfillment readable; `child-recipes`, `child-recipe-detail` |
| `GET /objects/products` and `/objects/products/{id}` | 200 | 200 | Master data and synthetic userfield readable; `child-master-data`, `child-master-data-single` |
| `GET /userfields/products/{id}` | 200 | 200 | `child-userfields` |
| `GET /chores`, `/tasks`, `/batteries` | 200 | 200 | Chores plus unrelated tasks/batteries readable |
| `GET /users` | 403 | 403 | `Permission missing: USERS_READ`; `child-users` |
| `GET /users/3/permissions` | 403 | 403 | `Permission missing: ADMIN`; `child-grants-admin-only` |
| `POST /stock/products/{id}/add` | 403 | 403 | `STOCK_PURCHASE`; `child-stock-purchase` |
| `POST /stock/products/{id}/consume` and `/recipes/{id}/consume` | 403 | 403 | `STOCK_CONSUME`; `child-stock-consume`, `child-recipe-consume` |
| `POST /stock/shoppinglist/add-product` | 403 | 403 | `SHOPPINGLIST_ITEMS_ADD`; `child-shopping-write` |
| `POST /objects/products` and `/objects/chores` | 403 | 403 | `MASTER_DATA_EDIT`; `child-master-data-write`, `child-chore-master-write` |
| `POST /objects/recipes` | 403 | 403 | `RECIPES`; `child-recipe-write` |
| `POST /objects/meal_plan` | 403 | 403 | `RECIPES_MEALPLAN`; `child-mealplan-write` |
| `POST /objects/equipment` | 403 | 403 | `EQUIPMENT`; `child-equipment-write` |
| `PUT /userfields/products/{id}` | 403 | 403 | `MASTER_DATA_EDIT`; `child-userfield-write` |
| `POST /tasks/{id}/complete` | 403 | 403 | `TASKS_MARK_COMPLETED`; `child-task-complete` |
| `POST /batteries/{id}/charge` | 403 | 403 | `BATTERIES_TRACK_CHARGE_CYCLE`; `child-battery-charge` |
| `POST /stock/bookings/{id}/undo`, `/stock/transactions/{id}/undo` | 403 | 403 | `STOCK_EDIT`; `child-stock-booking-undo`, `child-stock-transaction-undo` |
| `POST /chores/{id}/execute` with omitted `done_by` | 200 | 200 | Attributed to authenticated child; `chore-daily-execute` |
| Same execute with `done_by: 2` | 200 | 200 | Child can attribute execution to parent; `child-done-by-parent` |
| `POST /chores/executions/{id}/undo` | 204 | 204 | Inherited permission; `child-chore-undo` |
| `GET /files/userfiles/{base64Name}` | 200 | 200 | Child read parent-created synthetic bytes; `child-file-read` |
| `PUT /files/userfiles/{newBase64Name}` | 204 | 204 | Child uploaded new synthetic file; `child-file-new-upload` |
| `DELETE /files/userfiles/{base64Name}` | 204 | 204 | Child deleted parent-created and own files; `child-file-delete`, `child-file-new-delete` |

All 403 rows above include a `Permission missing: …` error naming the listed permission. Status alone is insufficient: other controller paths return 400 for missing permissions. An attempted file overwrite returned 400 (`Error while creating file …`), but a new-file upload succeeded with 204, so that overwrite failure was not an authorization barrier. [R47] / [R46], `child-file-upload`, `child-file-new-upload`.

The `/objects/*` probe iterated each exposed entity (and checked the newer entities on the previous minor). This is a family of real requests, not a literal wildcard endpoint. Exact entity results follow. Every supported listing below except keys/sessions returned 200 to the child; parent and child responses are retained, not inferred from the schema. [R47] / [R46], `child-object-list-{entity}`.

| Child GET path | 4.7.1 | 4.6.0 |
| --- | --- | --- |
| `/objects/products` | 200 | 200 |
| `/objects/chores` | 200 | 200 |
| `/objects/product_barcodes` | 200 | 200 |
| `/objects/batteries` | 200 | 200 |
| `/objects/locations` | 200 | 200 |
| `/objects/quantity_units` | 200 | 200 |
| `/objects/quantity_unit_conversions` | 200 | 200 |
| `/objects/shopping_list` | 200 | 200 |
| `/objects/shopping_lists` | 200 | 200 |
| `/objects/shopping_locations` | 200 | 200 |
| `/objects/recipes` | 200 | 200 |
| `/objects/recipes_pos` | 200 | 200 |
| `/objects/recipes_nestings` | 200 | 200 |
| `/objects/tasks` | 200 | 200 |
| `/objects/task_categories` | 200 | 200 |
| `/objects/product_groups` | 200 | 200 |
| `/objects/equipment` | 200 | 200 |
| `/objects/api_keys` | 400 | 400 |
| `/objects/userfields` | 200 | 200 |
| `/objects/userentities` | 200 | 200 |
| `/objects/userobjects` | 200 | 200 |
| `/objects/meal_plan` | 200 | 200 |
| `/objects/stock_log` | 200 | 200 |
| `/objects/stock` | 200 | 200 |
| `/objects/stock_current_locations` | 200 | 200 |
| `/objects/chores_log` | 200 | 200 |
| `/objects/meal_plan_sections` | 200 | 200 |
| `/objects/products_last_purchased` | 200 | 200 |
| `/objects/products_average_price` | 200 | 200 |
| `/objects/quantity_unit_conversions_resolved` | 200 | 200 |
| `/objects/recipes_pos_resolved` | 200 | 200 |
| `/objects/battery_charge_cycles` | 200 | 200 |
| `/objects/product_barcodes_view` | 200 | 200 |
| `/objects/permission_hierarchy` | 200 | 200 |
| `/objects/uihelper_shopping_list` | 200 | 400 |
| `/objects/sessions` | 400 | 400 |

`api_keys` (both) and `sessions` (4.7.1) are explicitly excluded from listing by the live schemas. Their 400 response is an entity restriction, not evidence of a chores boundary. `sessions` and `uihelper_shopping_list` are not exposed entities in 4.6.0. The tested 4.6.0 `uihelper_shopping_list` parent request also returned 400; a separate child probe is retained under the same family label.

A future child proxy must keep Grocy authoritative while enforcing an allowlist of chore reads and execution actions, filter permitted chore IDs and returned user data, derive or validate `done_by` against the authenticated child, and reject unrelated generic objects, files, stock, shopping, recipes, and admin routes. If child undo is unwanted, the proxy must deny it despite the inherited Grocy permission. A child-held raw Grocy key and direct server network access would bypass those restrictions: enforce the proxy boundary for the protected child workflow and keep upstream credentials server-side. This does not provide restrictions for an independently accessible unrestricted Grocy web session. No proxy is built in Stage 1.

## Chore recurrence and attribution

The live web form offered **manually, hourly, daily, weekly, monthly, yearly, adaptive** on both versions: [4.7.1 form extraction](evidence/stage1/grocy-4.7.1-chore-form.json), [4.6.0 form extraction](evidence/stage1/grocy-4.6.0-chore-form.json). The live OpenAPI `Chore.period_type` enum lists only the first five, omitting yearly/adaptive. The same enum omission exists on both versions; generated clients must not reject the observed additional modes.

Created seven chores with `start_date: "2026-10-01 09:00:00"`, `period_interval: 2`, `rollover: 0`, `track_date_only: 0`, and no assignment. Monthly used `period_days: 15`; weekly used `period_config: "monday,wednesday"`. Executed each as the child at `2026-10-06 10:30:00`. All creations/details/executions returned 200. Next dates are server-calculated observations for these exact inputs, not a general recurrence algorithm:

| Mode | Before execution | After execution (same on both) |
| --- | --- | --- |
| manually | `null` | `null` |
| hourly | `2026-10-01 09:00:00` | `2026-10-06 12:30:00` |
| daily | `2026-10-01 09:00:00` | `2026-10-08 09:00:00` |
| weekly | `2026-10-01 09:00:00` | `2026-10-14 10:30:00` |
| monthly | `2026-10-01 09:00:00` | `2026-12-15 00:00:00` |
| yearly | `2026-10-01 09:00:00` | `2028-10-01 10:30:00` |
| adaptive | `2026-10-01 09:00:00` | `2026-10-06 10:30:00` (one observation; average frequency `null`) |

Source: [R47] / [R46], `chore-{mode}-before`, `chore-{mode}-execute`, `chore-{mode}-after`. A second adaptive execution at `2026-10-07 10:30:00` yielded average frequency **24 hours** and next execution `2026-10-08 10:30:00` (`chore-adaptive-second-after`).

A separate daily chore with `rollover: 1` and the same starting date returned next date `2026-10-07 09:00:00` before execution, while the `rollover: 0` fixture returned the original overdue start date. Its next date after execution was `2026-10-08 09:00:00`. Setting `rescheduled_date: "2026-10-09 18:00:00"` changed its next date to exactly that value; execution then cleared `rescheduled_date` and returned next `2026-10-11 09:00:00`. This sample does not characterize every missed-occurrence, DST, monthly edge, or timezone case. Preserve the server schedule. [R47] / [R46], `chore-rollover-*`, `chore-reschedule-*`.

Omitting `done_by` produced `done_by_user_id: 3`. Explicit `done_by: 2` produced `done_by_user_id: 2` with HTTP 200, including on a chore assigned solely to child user 3. Assignment is not an attribution access-control boundary. A nonexistent `done_by: 999999` returned 400 (`User does not exist`). `track_date_only: 1` normalized the submitted `15:45:00` time to `00:00:00`. A scheduled skip returned 200 with `skipped: 1`; skipping a manual chore returned 400. [R47] / [R46], `child-done-by-parent`, `child-done-by-invalid`, `child-date-only-execute`, `child-scheduled-skip`, `child-manual-skip`, `child-assigned-chore-done-by-parent`.

Observed chore objects include `period_interval`, `active`, `consume_product_on_execution`, `product_id`, and `product_amount`, which the live `Chore` schema omits. Execution rows include `done_by_user_id`, `undone`, `undone_timestamp`, `skipped`, and `scheduled_execution_time`, omitted by `ChoreLogEntry`. Use observed data to design tolerant models; do not recreate scheduling locally. Stock consumption as a chore side effect is present as object fields but was not exercised here.

## Shopping row shape and version differences

Observed base rows contain `id`, `product_id` (nullable for a note-only row), `note`, `amount`, `row_created_timestamp`, `shopping_list_id`, **`done`**, and **`qu_id`**. `done` is an integer 0/1 in these responses. The live `ShoppingListItem` schema omits `done` and `qu_id`; single-object reads also include `userfields: null` when unset. Updating a note-only row with `{"done":1}` returned 204 and the subsequent list returned `done: 1`. [R47] / [R46], `shopping-set-done`, `shopping-rows`, `fraction-generic-read`.

The additive route's request uses `list_id` and `product_amount`; the stored row uses `shopping_list_id` and `amount`. `qu_id` is a display unit; the live schema states that `product_amount` is in stock units. Identical `product_amount: 2.5` requests returned 204 on both, then read back **2.5 on 4.7.1 and 2 on 4.6.0**. Generic creation with `amount: 2.5` returned 200 and preserved the fraction on both. Status 204 alone does not guarantee identical persisted data. [R47] / [R46], `fraction-add`, `fraction-add-read`, `fraction-generic-create/read`.

4.7.1's `uihelper_shopping_list` supplies product name, unit names, group fields, barcode strings, and price fields; 4.6.0 returned 400 for the entity. Base rows and reference joins remain the cross-version option. The stable schema also adds `sessions` to exposed entities while prohibiting its listing. Stable adds `POST /stock/products/{productId}/copy`; this path is absent in 4.6.0 (not exercised). Apart from that addition, the path sets match; generic-entity schemas differ, so matching path names do not imply identical contracts. [S47] / [S46], [R47] / [R46], `shopping-uihelper`.

## Stock booking/transaction undo

On both fixtures: purchasing 5 units returned 200 with a stock-log array containing booking `id`, `transaction_id`, `stock_id`, `user_id: 2`, and `undone: 0`; stock read 5. `GET /stock/bookings/{id}` returned 200. `POST /stock/bookings/{id}/undo` returned 204; stock became empty, and the log remained with `undone: 1` and a populated `undone_timestamp`. A second purchase restored 5; consumption of 2 returned 200 and stock became 3. `GET /stock/transactions/{transactionId}` returned the transaction's bookings; transaction undo returned 204, stock returned to 5, and the consume log became undone. [R47] / [R46], `stock-purchase`, `stock-before-undo`, `stock-booking-read/undo/after-undo`, `stock-after-booking-undo`, `stock-consume`, `stock-after-consume`, `stock-transaction-read/undo/after-undo`, `stock-after-transaction-undo`.

Booking ID identifies one log row; transaction ID is an opaque string grouping bookings. Undo is a server mutation, not deleting cached history. Both child undo calls returned 403 for missing `STOCK_EDIT`. This exercise used one booking per consume transaction; multi-booking atomicity, retry idempotency, and partially spent purchase undo were not tested.

## Barcode observations

Registered synthetic barcode `STILLROOM-STAGE1-001` through `/objects/product_barcodes`. `GET /stock/products/by-barcode/{barcode}` returned 200 with product details and `product_barcodes` associations (unit, amount, shopping location, last price, note). Unknown `STILLROOM-UNKNOWN` returned **400**, not 404, with `No product with barcode … found`. [R47] / [R46], `barcode-associations`, `barcode-found`, `barcode-not-found`.

All five `POST /stock/products/by-barcode/{barcode}/{add,consume,transfer,inventory,open}` routes were exercised successfully as parent (200). Bodies used `amount` for add/consume/open; transfer additionally used `location_id_from` and `location_id_to`; inventory used `new_amount` and `best_before_date`. Their stock action permissions correspond to the ID-based routes. Child transfer/inventory/open returned **400**, with missing `STOCK_TRANSFER` / `STOCK_INVENTORY` / `STOCK_OPEN`, whereas the tested direct add/consume denials were 403. Future error handling must parse the permission error as well as the HTTP code. [R47] / [R46], `barcode-purchase/consume/transfer/inventory/open`, `child-barcode-transfer/inventory/open`.

`GET /stock/barcodes/external-lookup/STILLROOM-UNKNOWN` returned **200 `null`** to parent and **403** (`MASTER_DATA_EDIT`) to child. This default fixture did not prove successful external-provider lookup. Persisted associations are Grocy master data; do not infer that camera scanning, public barcode enrichment, or a native barcode feature is implemented from these routes.

## Reproduction and next boundary

Use only the pinned disposable containers and synthetic users. Start with `mise run grocy:up`; obtain their actual `/system/info`, `/openapi/specification`, `/system/config`, and `/objects/permission_hierarchy` using a fixture admin key. Create separate parent/child users, replace default grants using live hierarchy IDs, create keys in each user's own web session, and confirm `/user` for each key. Replay the archived request bodies in order against fresh synthetic fixtures; generated row/booking/transaction IDs and timestamps will differ. Keep keys outside the repo and never include a parent's cookie in a child probe.

`mise run stage:1` checks required documentation and validates the archived evidence hashes; it does not replay network calls or certify another server. Any different Grocy version/configuration requires fresh probes before enabling protected child mode. These findings are the historical Stage 1 boundary; later authorization is recorded in STATUS.

## Stage 3 connection validation

Stage 3 verified `/system/info` and `/user` with separate parent and child keys on both pinned versions. The current-user response is a one-element array on these fixtures. The permission-list endpoint `/users/{id}/permissions` requires ADMIN even for the child's own user. Stillroom therefore offers explicit verification with a saved administrator on the same server and lists only returned explicit grants. Without that verification the list is unavailable; successful unrestricted reads never imply permissions.

The live Stage 3 child `/users` check returned 403 with `Permission missing: USERS_READ` on both versions, matching Stage 1. The app warns about the known 4.6.0 limitation and versions outside this matrix. It only connects and caches account metadata; feature operations and the required child access-control proxy remain unimplemented. See [actual Stage 3 validation](STATUS.md#stage-3--connection-and-account-isolation).

[R47]: evidence/stage1/grocy-4.7.1-responses.json
[R46]: evidence/stage1/grocy-4.6.0-responses.json
[S47]: evidence/stage1/grocy-4.7.1-openapi.json
[S46]: evidence/stage1/grocy-4.6.0-openapi.json
[P47]: evidence/stage1/grocy-4.7.1-permissions.json
[P46]: evidence/stage1/grocy-4.6.0-permissions.json
