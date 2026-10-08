# Later physical-phone test

**Physical-phone results: NOT RUN.** This is a test plan, not a release sign-off. The local API/JVM/build gates and the Stage 9 emulator fixture-image scan do not establish camera, launcher, notification, or offline behavior on a physical phone.

Record device model, Android version, APK SHA-256, Grocy version, date/time zone, network, tester, and actual results here when someone performs the tests. Never record keys, passwords, household barcodes, or private household screenshots in this repository. Use synthetic test records on the existing Grocy, then remove only those records.

Install the final debug APK from `app/build/outputs/apk/debug/app-debug.apk` using the usual Android installation flow. The phone must reach the existing Grocy on the machine's LAN address: `127.0.0.1:9283` on a phone points to the phone itself. Use HTTPS when configured; explicitly opt in to HTTP only for this local test instance. Do not start another container or deploy a backend.

| Test | Physical-phone result |
| --- | --- |
| Separate child login and denied features | NOT RUN |
| Child chore completion and native attribution | NOT RUN |
| Fraction input and stored amount preservation | NOT RUN |
| Scan an actual package with CameraX | NOT RUN |
| Offline reviewed purchase and replay | NOT RUN |
| Launcher widgets and account switch | NOT RUN |
| WorkManager reminders and quiet hours | NOT RUN |

## Child login

1. In Grocy, create a temporary separate child user/key with `CHORES` and `CHORE_TRACK_EXECUTION` explicit grants. Assign a synthetic chore to that user. Keep the temporary parent's administrator key separate.
2. Connect the child account on the phone. If it cannot read permission definitions, use Stillroom's saved administrator verifier for that same server. Verify `/api/user` identifies the child, not the parent.
3. Confirm the child lands on Household and can open Today. My Chores and Today include only this child's assigned chores. Stock, shopping, recipes, batteries, equipment, master-data editing, and scan must show no household records or mutation controls when denied.
4. Switch parent → child → parent, and log out. Confirm screen state, widgets, and notifications never retain the previous account's data. An unknown/unverified grant set must not reveal records.

The upstream child-read/access-control gaps discovered in Stage 1 remain documented in `docs/COMPATIBILITY.md`. Phone UI filtering does not fix Grocy's server boundary. This build adds no proxy/backend and must not be represented as enforcing restrictions against direct API use.

## Chore complete

1. With the child account active, open the assigned synthetic chore, read its instructions, and tap Complete once.
2. Wait for confirmed status. In the Grocy web app and `/api/objects/chores_log`, confirm the new log belongs to that chore and `done_by` is the child's user ID.
3. Verify the next due date matches Grocy's response. Reassignment by the parent before a child completes must cause the child to refresh or reject the stale completion.
4. Confirm uncertain completion is retained for review and is not automatically submitted a second time.

## Fraction input

1. As the parent, use synthetic products with an existing Grocy stock unit/location and a known unit conversion. Enter `1 1/2` in a stock purchase/consume review. Verify the displayed converted quantity and native decimal booking are correct.
2. Edit a recipe ingredient with a stored decimal such as `0.3333333333333333`. Without changing its amount input, change its note and Save. Compare the before/after server amount: the app must not save an approximate display fraction over the original decimal.
3. Change an amount explicitly, Save, and verify the server changes only then. Scale desired servings and confirm base ingredient amounts remain unchanged. Repeat with the phone's locale decimal separator and large font size.

## Scan a real package

1. Use an actual package. Add its exact barcode to a synthetic existing product in Grocy first, preserving leading zeros. Do not log the package barcode in this document.
2. Grant camera permission, scan through CameraX, test torch and zoom, and place the code both outside and inside the target region. The selected code must resolve against Grocy.
3. Put two codes in view and verify explicit choice. Repeated reads of the same selected code must stay suppressed until Scan same code again is tapped. Test manual entry and an invalid UPC/EAN checksum.
4. Deny camera permission and verify manual entry remains usable. Select Add stock or Use stock before capture; a unique match must open the focused stock review and never occur merely from scanning. Test journal undo.
5. For an unknown grocery code, review any suggestion. Select an existing stock unit/location and enter a real due date before purchase; suggestions must not invent either. QR household text must not be sent to public lookup. A physical scan pass may only be recorded after this test actually runs.

## Offline purchase replay

1. As the parent, prepare a confirmed synthetic shopping-list row for a known product. Open its purchase review while online to cache the row, units, conversions, and location. Record that product's stock quantity and purchase journal rows.
2. Disconnect the phone from the network (airplane mode with Wi-Fi off). From that existing shopping row, review quantity, unit conversion, location, price if entered, and explicit due date, then queue the purchase. Verify pending status and unchanged confirmed stock. Do not recreate the shopping row or re-enter the same purchase.
3. Restore the connection and tap Refresh in shopping. Verify the original queued review reconciles, creates exactly one native stock purchase, and finishes/removes the matching list row. Restart the app and refresh again; quantity and journal count must remain unchanged.
4. Repeat with the row edited by Grocy's web app while the phone is offline. Expect conflict review and no silent overwrite or stock booking.
5. A request interrupted after dispatch is an uncertain outcome, not a safe replay. If the app reports needs-review, inspect native stock/journal and reconcile it; do not delete the queue and book again. Record that outcome as a failure or limitation of the phone scenario rather than marking automatic replay passed.

## Widgets and reminders

1. Add the Stillroom chores, shopping, and scan widgets in the phone's launcher. Verify cached due chores, counts per shopping list, and scan entry. Taps must reach the correct screen after a cold launch and account verification. Denied accounts must not show cached content from an earlier account.
2. Enable reminders in Today and allow Android notification permission. With synthetic due chores/food in cache, wait for WorkManager; scheduling is approximate and may be delayed by battery restrictions. It must not send stock/chore mutations or use external automation.
3. Set quiet hours across midnight and verify no notification occurs within them. Verify notification delivery after quiet hours and at most one reminder per account per day. Equal start/end silences the whole day. Also test notification permission denial.
4. Log out/switch accounts and confirm existing reminders are cleared, old background work cannot republish another account's data, and a known 401/403 hides cached background content until access is verified again.

All results above remain **NOT RUN** until a tester records actual physical-phone evidence. Do not replace this label based on emulator, JVM, or local API success.
