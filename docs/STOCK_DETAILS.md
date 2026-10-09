# Stock shown details

**Settings → Stock → Shown details** chooses what each Pantry (stock overview) row shows, including the account's Grocy product userfields (custom fields). It is display only: Stillroom never writes a userfield value or any other Grocy data from this setting.

## What can be shown

Main details keep their existing place in the row and can only be switched on or off:

| Detail | Source | Default |
| --- | --- | --- |
| Amount in stock | `/stock` `amount` (or the chosen location's entries), shared quantity formatter | On |
| Stock unit | product `qu_id_stock` → `/objects/quantity_units` | On |
| Next due date | `/stock` `best_before_date`, with the existing use-soon/overdue colour | On |

Extra details are lines under the product name, shown in the order chosen with the up/down buttons. A detail with no value for a product is skipped on that row.

| Extra detail | Source | Shown as |
| --- | --- | --- |
| Opened amount | `/stock` `amount_opened` | quantity formatter; hidden when 0 |
| Total including subproducts | `/stock` `amount_aggregated` when `is_aggregated_amount` is set | quantity formatter |
| Default location | product `location_id` → `/objects/locations` | name |
| Product group | product `product_group_id` → `/objects/product_groups` | name |
| Stock value | `/stock` `value` | 2-decimal number, no currency symbol |
| Minimum stock amount | product `min_stock_amount` | quantity formatter; hidden when 0 |
| Barcodes | `/objects/product_barcodes` | comma-separated |
| Each product userfield | `/objects/products` row `userfields` | by type, below |

Every extra, including every userfield, starts **off**. Until the user changes something, rows render exactly as before this setting existed (`StockDetailsTest.defaultsMatchTodaysRowAndHideEveryUserfield`, `defaultRowsRenderNoExtraLines`).

Grocy's `show_as_column_in_tables` flag does not switch a userfield on. Doing so would add lines to existing users' rows the moment they install the update. Settings instead labels those fields "a column in Grocy's tables" so they are easy to find.

## Userfield rendering

Types follow Grocy's `UserfieldsService` constants. Values are the strings Grocy stores.

| Grocy type | Row text |
| --- | --- |
| `text-single-line` | the text |
| `text-multi-line` | lines joined with ` · `, cut at 120 characters with `…`; the row wraps to 2 lines at most |
| `number-integral`, `number-decimal` | shared `QuantityFormatter` (Settings → Appearance → Show quantities as), e.g. `1½` or `1.5` |
| `number-currency` | 2-decimal number, no currency symbol |
| `date` | localized medium date, e.g. `Oct 8, 2026` |
| `datetime` | localized medium date + short time |
| `checkbox` | `Yes` / `No` |
| `preset-list` | the selected option |
| `preset-checklist` | selected options, comma-separated |
| `link` | the URL as text (not clickable) |
| `link-with-title` | the title, or the URL when the title is empty |
| `file` | `File: <name>` (decoded from Grocy's `<id>_<base64 name>` value); not downloaded |
| `image` | `Image: <name>`; the image is not downloaded |
| anything else | the raw value |

Unparseable values (a bad date, a non-number in a number field) fall back to the raw text. An empty value hides the line.

## Saving, accounts and server changes

- Choices are saved on the phone per Grocy account id (`SharedPreferences` file `stock_details`, key `account_<id>`). Switching accounts loads that account's choices; nothing leaks between accounts. **Reset shown details** returns the account to defaults.
- Settings changes are UI events applied to the active account's `AccountBoundState`; the stock view model reloads choices whenever the account changes.
- A userfield deleted on the server disappears from Settings and rows and is pruned from the saved list on the next change. A userfield added on the server appears at the end of the extras, off.
- If userfield definitions cannot be read (offline with no cache, missing permission, an older or unusual server), the pantry still loads, Settings says so, and saved userfield choices are kept rather than pruned.

## Fetching (no per-row requests)

- Values: since Grocy 2.7.0, `GET /objects/products` returns each product's userfields inline as `userfields` (key/value pairs, or `null`); since 3.1.0 empty userfields are included too. Pantry already reads `/objects/products`, so userfield values cost **no extra request**. `GET /stock` embeds `ProductWithoutUserfields` and cannot be used for this. `GET /userfields/products/{id}` is never called, so there is no N+1.
- Definitions: `GET /objects/userfields` (filtered to `entity = products`), plus `GET /objects/product_groups` for group names. Both are read through the account's Room response cache like every other stock read, so they work offline after one sync, and each is optional: a failure is ignored for the rest of the pantry.
- Grocy 4.1.0 also returns userfields for the `stock` entity (individual stock entries). Rows are per product, so stock-entry userfields are not offered here.
- Verified against the recorded 4.6.0 and 4.7.1 evidence (`docs/evidence/stage1/*-responses.json`: `child-object-list-products` contains `userfields`, `child-object-list-userfields` returns definitions to a non-admin user, `/stock` rows carry `value`). `value` is not in the OpenAPI `CurrentStockResponse` schema but is present in both recorded versions; when absent, the line is skipped.

## Not done

- Product detail screen is unchanged; this setting affects Pantry rows (overview, search results, use soon / running low sections).
- Links are not clickable, files and images are not fetched.
- Saved choices are not deleted when an account is removed (small, non-secret UI data).
- No phone or live Grocy server was used for this change; tests are host JVM/Robolectric.
