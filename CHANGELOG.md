# Changelog

## 0.2 — 2026-10-09

The first update after 0.0.1. Install `Stillroom-0.2.apk` from [Releases](https://github.com/exquisiteskink/Stillroom/releases/latest). It uses the same signing certificate as 0.0.1, so it can replace that install. A debug build already on the phone will not upgrade in place; uninstall the debug build first.

### Connect to Grocy

- If your Grocy uses a certificate from your own private CA, install that CA's **root** in Android's CA certificate settings. Stillroom then trusts every user-installed CA on the phone, not only one Grocy server.
- The server must send its intermediate certificates, and the address you type must match the name on the certificate.
- A failed certificate check does not switch the connection to HTTP. Local HTTP still needs the Allow insecure HTTP toggle.

### Pantry

- Add and edit products in Grocy from the pantry, including Grocy's extra fields.
- Choose which details each pantry row shows (amount, unit, due date, and optional extras).
- Opening a product starts on Use stock.
- Use soon and Running low can add those items to a shopping list.
- An unknown barcode can be attached to a product you already have. A wrong attachment has to be removed in Grocy.

### Meals and Today

- Recipe photos show Grocy's fulfillment badge (for example Missing 3). That badge does not change stock.
- Cooking a recipe shows the scaled amount and unit for each line.
- Confirming a cook queues the missing lines together and keeps that review until every line is confirmed.
- Today can open a planned recipe or meal.

### Display and layout

- Settings can show quantities as fractions or decimals.
- Recipe pictures use less memory.
- Add and save buttons share one full-width height, with filter chips on their own row.
- Pantry product pages use even spacing. Dialog Cancel or Close dismisses; the main action uses the primary button.

### Reliability

- Switching accounts no longer shows the previous household's rows.
- If Android Keystore is briefly unavailable, the saved account stays put instead of being deleted.
- Cancelling a change before it is sent returns it to pending, so it is not lost.
- A partial new product keeps the unit and extra-field defaults from create time, so Save stays valid.

## 0.0.1 — 2026-10-07

First public build. Today, Pantry, Shop, Meals, and Household against the Grocy pantry you already run. Sign in with a Grocy API key (type it or scan Grocy's Manage API keys QR). Camera barcode scanning stays on the device. HTTPS is the default.
