# Pantry product editor

The owner asked to "add database items and edit their attributes in the pantry screen". In Stillroom the **Pantry** section (bottom bar / sidebar; also the Search page) is `ui/StockScreen.kt`. Its rows are Grocy **products** (master data), so this means creating products and editing a product's attributes, including its userfields, from that screen.

## Where

- **Add product**: a text button at the start of the Pantry action row (before *Browse locations* and *View stock history*). It is hidden on the barcode-only scanner view.
- **Edit product**: tap a pantry row to open the product, then **Edit product** under the name.
- Both open the same record editor as **Household → Records → Products**, so there is one product form and one userfield editor in the app. Tapping outside the dialog does not close it; only Cancel does, and the editor stays open until Grocy confirms the save.
- No floating button: the app has none elsewhere, and the Pantry already has a row of text actions.

## Product fields

Grocy 4.x `products` table (migrations 0155 onward) requires `name` (unique), `location_id`, `qu_id_purchase` and `qu_id_stock`. `qu_id_consume` and `qu_id_price` are filled by insert triggers (migrations 0210 and 0219) with the stock and purchase unit when omitted. The recorded 4.6.0 evidence creates a product with just those four fields (`docs/evidence/stage1/grocy-4.6.0-responses.json`, `seed-products`).

| Field | Required | Notes |
| --- | --- | --- |
| Name | yes | Grocy rejects duplicates; shown as "A product with this name already exists in Grocy." |
| Description | no | |
| Product group | no | from `/objects/product_groups` |
| Default location | yes | |
| Stock unit, Purchase unit | yes | |
| Consume unit, Price unit | create: no ("Same as stock/purchase unit", as Grocy does); edit: yes | |
| Stock units per purchase unit | when purchase ≠ stock unit | see below |
| Add barcodes | no | one per line; duplicates are checked against all Grocy barcodes before sending |
| Advanced: default store, minimum stock, default best-before days (-1 = never), after opening, quick consume amount, calories, active, disable opening, hide from stock overview, skip recipe stock checks | no | numbers accept `1.5` and `1½` |
| All product userfields | per definition | typed editor below |

**Unit conversion factor.** Grocy 4 has no factor on the product. It uses `quantity_unit_conversions`, and when a product is created with different purchase and stock units, Grocy's `default_qu_conversions` trigger adds a 1:1 product conversion. The editor shows the factor Grocy uses now: the product conversion, else a global one, else 1. If you change it, Stillroom updates that product conversion (`PUT /objects/quantity_unit_conversions/{id}`) or creates one (`POST`) after the product is saved. Other conversions stay in Household → Records → Unit conversions.

**Edits send only what changed.** Fields you did not touch are not sent, so a concurrent change in Grocy's web app to another field is not overwritten. Userfields work the same way, matching Grocy's own form. Existing barcodes are listed. Removing or changing them is left to Grocy's web app.

## Userfield editors (shared; `ui/UserfieldEditor.kt`, `domain/Userfields.kt`)

| Grocy type | Editor | Stored as |
| --- | --- | --- |
| text-single-line | one-line text | text |
| text-multi-line | multi-line text | text |
| number-integral | text, whole numbers | `3` |
| number-decimal | text, `1.5` or `1½` | `1.5` |
| number-currency | text | `4.5` |
| date | text `YYYY-MM-DD`; "now" default fills today on create | `2026-10-08` |
| datetime | text `YYYY-MM-DD HH:mm` | `2026-10-08 14:30:00` |
| checkbox | checkbox | `1` / `0` |
| preset-list | choice list from the field's options (`config`, one per line) + "Not set" | the option |
| preset-checklist | a checkbox per option; stored values no longer offered are kept and shown | `A,B` |
| link | text, must be a full link (`https://…`) | the URL |
| link-with-title | title + link | `{"title":…,"link":…}` (Grocy's format) |
| file, image | read-only: file name and a note | unchanged, never sent |

Unknown types are read-only and never sent. Stillroom's earlier names `text`, `numeric` and `date-time` are treated as `text-single-line`, `number-decimal` and `datetime`. Invalid values show a message under the field, and Save stays disabled until they are fixed.

File and image uploads are not done. Grocy stores them through `PUT /files/userfiles/{name}` with a binary body and a two-part base64 name (`userfieldsform.js`). Stillroom's outbox carries JSON only. A required file/image field therefore blocks **creating** a product with a clear reason; editing other fields still works.

## Saving, order and partial failure

1. Before anything is queued, Stillroom does a fresh read of the product (or the product list) from Grocy. Offline, this fails: **nothing is queued**, the form stays open with "Could not reach Grocy…", and you can retry.
2. The product write (`POST /objects/products` or `PUT /objects/products/{id}`) goes into the account's durable outbox and is sent immediately.
3. Only after Grocy confirms it, the follow-ups are queued in this order: userfields (`PUT /userfields/products/{id}`), the conversion factor, then each new barcode (`POST /objects/product_barcodes`). For a create the id comes from Grocy's `created_object_id`. Each follow-up has a deterministic id derived from the product write, so later syncs never send one twice.
4. The result is read back from the outbox:
   - **Saved**: the editor closes, the pantry reloads, and "Product saved in Grocy." is shown.
   - **Failed** (Grocy rejected the product): Grocy's `error_message` is shown, made readable for duplicate names, barcodes, missing permission and stock-unit changes. Entries are kept.
   - **Partial** (product saved, a follow-up rejected or not sent): the message names what failed and why. The editor switches to editing the new product with your entries kept, so Save retries only what is still different. A failed follow-up stays failed in Pending changes; it is not retried silently.
   - **Unconfirmed** (connection dropped mid-request, `needs-review`): the editor stays open and points to Settings → Pending changes. Nothing is resent automatically, matching the existing outbox rule. While a create or edit is unresolved, another write to the same path is refused with "awaiting confirmation". A retried create cannot duplicate a product, because Grocy rejects the duplicate name.
5. Failed outbox rows now record Grocy's reason (`HTTP 400: <error_message>`) instead of only the status, so Pending changes shows it too.

## Permissions and accounts

- Writing products needs Grocy's `MASTER_DATA_EDIT` (or `ADMIN`). Accounts without it (child, stock-only) do not see **Add product**. The product page says "Editing products needs Grocy's master-data permission (MASTER_DATA_EDIT) for this account." The repository refuses the write as well, and Grocy remains the authority (403 → readable message).
- Editor state lives in the pantry's `AccountBoundState`. Switching accounts closes the editor and drops any late result from the previous account.

## Not done

- File/image upload or removal; deleting or editing existing barcodes; editing sub-product (parent) links, tare weight, freezing days, due type and picture.
- No phone, emulator or live Grocy server was used. Repository behaviour is tested against a MockWebServer that stands in for Grocy (`ProductEditorIntegrationTest`).
