# Grocy add-on compatibility foundation

Authorized by the user on 2026-10-09 after the ecosystem research. Grocy remains authoritative; no browser extension JavaScript is executed by Stillroom.

## What the user can do

Settings → Grocy add-ons contains account-specific settings and external-change diagnostics:

- Check changes immediately, control the anonymous Open Food Facts fallback, and inspect disabled server features.
- Choose **Stillroom checkbox** or **Another scanner or importer** as the shopping purchase owner. The default retains checkbox-to-pantry behavior. External ownership makes checkboxes only cross out items; it also holds unsent pending Stillroom shopping purchases. Previously confirmed purchases still finish their list completion.
- Save an optional web add-on shortcut, with explicit HTTP opt-in. Links are opened in the system browser without either API key. Browser authentication remains independent.
- Administrators can save a separate BarcodeBuddy API URL/key, test its connection/current mode, and select **BarcodeBuddy** in the scanner. A detected code is reviewed before **Send scan to BarcodeBuddy**. It uses BarcodeBuddy's current shared mode without changing that mode. The mode can change between review and submission. The BarcodeBuddy route does not call Stillroom's product-creation or stock-booking flow.
- Users with master-data editing permission can manage Grocy custom records, select an entity, browse 50 named records per page, create/edit typed fields, remove records with confirmation, upload image/file fields, preview images, and save files through Android's document picker. Entity and field definitions remain managed in Grocy. Stillroom's reserved shopping coordination records are hidden.

BarcodeBuddy credentials use the existing Keystore-backed encrypted account store in a separate namespace; they never enter settings JSON, HTTP logs, browser links, or Grocy request headers. Logout removes the extra credential record. A failed BarcodeBuddy key does not revoke Grocy access. Administrator-only routing avoids silently using an add-on's more powerful credential from a restricted account.

## Synchronization and capabilities

While the activity is started, the observer checks every 30 seconds. It refreshes config/OpenAPI metadata every minute, uses `/system/db-changed-time`, and sweeps cached read-only resources at least every two minutes. Returning to the foreground forces a check. The periodic sweep handles Grocy's timestamp being precise only to a second. An absent/unsupported token falls back to interval refreshes. Failed reads retain cached data and leave the token unacknowledged for retry.

Polling does not drain outboxes. The active feature republishes its data through a separate read-only refresh; Today loads the newly refreshed shared cache. Refreshable paths use an explicit allowlist that excludes keys, sessions, printing, external lookup, and other action GETs. Cache readers reject print and external-lookup action routes even when requested directly.

Main server feature flags control navigation, household tabs, homepage sections, repository reads, and queued-write dispatch. Meal-plan/opened-stock settings and the stock price/due-date inputs also respect relevant flags. Supported mutation methods are matched against OpenAPI route templates when advertised. Missing metadata is unknown and does not imply a permission grant. Grocy authorizes every request. Disabled or unsupported pending writes remain pending instead of being silently discarded.

## Barcode and media behavior

- Exact barcode matches retain amount, quantity unit, store, last price and note. The stock review uses those suggestions while preserving decimal precision and converting the reviewed quantity to stock units. Saved barcode prices are reviewed as totals and divided by the net stock quantity, including tare when applicable; manual per-stock-unit prices remain available.
- Configured Grocy lookup plugins receive a preview request with `add=false`. Their name, description, location, stock/purchase units, conversion factor, barcode and image hint are retained. IDs are offered only when present in the available choices. Reviewed purchase/stock conversion is a durable follow-up after product creation.
- Household codes can reach the configured authenticated Grocy plugin; only valid eligible UPC/EAN codes are sent directly to Open Food Facts. The server-side plugin controls its own downstream lookups. Disabling public fallback also prevents reading its saved suggestions.
- Grocycodes identify products and optional stock entries; consuming an entry requires exactly one entry and sends its identity. Chore/battery/recipe labels offer a Grocy browser destination. They do not trigger an automatic household action.
- Product pictures and userfield media load only through Grocy's authenticated file API. Userfield values use Grocy's `base64(storage name)_base64(display name)` format; product pictures use filenames. Uploads use unique filenames, a 5 MB limit, a durable outcome marker and a single outbound attempt. The custom-record field is updated only after a confirmed upload. Previous files are preserved; failed/abandoned uploads can leave an unreferenced file for review in Grocy.
- External lookup image URLs are retained as hints and are not fetched automatically. Product userfield file/image replacement still uses Grocy's web app; their existing content can now be viewed/saved in Stillroom. Custom-record media fields support upload here.

Custom edits send only changed fields and compare those fields with a fresh baseline first. Unknown/read-only fields remain intact. Grocy has no atomic compare-and-set operation here, so an uncoordinated write between that check and the PUT remains possible. Custom creation and its field follow-up use stable outbox identities; an uncertain create is not automatically repeated.

BarcodeBuddy receipts are durable before submission and serialized within an account session. Timeout, cancellation or process interruption leaves an uncertain receipt. That barcode cannot be sent again until **I checked this scan's outcome** is selected after inspecting BarcodeBuddy/Grocy. Confirmed scans refresh Grocy; a failed refresh does not convert a confirmed scan into an unconfirmed booking. Receipts do not automatically resend scans.

## Compatibility boundaries

Ordinary Grocy records produced by RecipeBuddy, GrocyPad and other API clients use the existing native views after refresh. ProductHelper/NerdCore/StatNerd browser workflows, automatic brand-linking scripts and charts still run in the web app; native custom-data access does not reproduce their scripts. No installed-add-on registry is assumed. No Home Assistant integration was added. Native printing is outside this foundation.

Protocol references: [Grocy API specification](https://github.com/grocy/grocy/blob/master/grocy.openapi.json), [lookup plugin contract](https://github.com/grocy/grocy/blob/master/plugins/DemoBarcodeLookupPlugin.php), [Grocycode format](https://github.com/grocy/grocy/blob/master/docs/grocycode.md), [Grocy userfield form](https://github.com/grocy/grocy/blob/master/public/viewjs/components/userfieldsform.js), [BarcodeBuddy API](https://github.com/Forceu/barcodebuddy/blob/master/openapi.json), [Grocy barcode price behavior](https://github.com/grocy/grocy/blob/master/public/viewjs/purchase.js). Archived 4.6.0/4.7.1 API contracts remain in `docs/evidence/stage1/`.

## Validation

Actual commands/results are recorded in [STATUS.md](STATUS.md). Host tests use MockWebServer, production Room/outboxes, pure domain tests and Robolectric Compose tests. No live Grocy/add-on, physical-phone, emulator, or browser test is implied by those results. The localhost Grocy fixture was unreachable during this work.
