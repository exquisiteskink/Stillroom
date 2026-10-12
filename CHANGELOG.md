# Changelog

## 0.4.1 — 2026-10-11

A small update: a way out of a shopping trip that could not finish, plus fixes. Download `Stillroom-0.4.1.apk` from [Releases](https://github.com/exquisiteskink/Stillroom/releases/latest) and install it over your official 0.4 release to keep your saved accounts.

### End a shopping trip that could not finish

- If a trip stopped on an uncertain line and could never be completed, the review screen now offers **Abandon unfinished trip**, with a confirmation first.
- Purchases already confirmed in Grocy stay booked. Lines that were never sent are marked as not sent and will never be booked from that trip; a line whose result is uncertain stays in Pending changes for review.

### Fixes

- Widgets and reminders no longer run the one-time shopping-settings cleanup on your account; only opening the app does that, so a background reader can no longer change saved purchase states.
- The pantry no longer marks food as due soon when Grocy stored a best-before date the app cannot read; the date is shown exactly as stored.
- Stock write actions now match Grocy's permissions: the stock overview grant alone no longer offers purchase, consume, open, transfer, inventory, or spoilage; each action needs its own permission or administrator.
- Scanning and saving no longer crash when a record was deleted in Grocy since the app last loaded it; you get a clear message to refresh instead.
- A confirmed scan follow-up (new product and barcode) is never sent a second time, even after repeated syncs.

## 0.4 — 2026-10-10

Shopping is clearer, and removing an item now gives you a chance to cancel. Download `Stillroom-0.4.apk` from [Releases](https://github.com/exquisiteskink/Stillroom/releases/latest) and install it over your official Stillroom release to keep your saved accounts.

### Choose what happens when you check an item

- Checking a shopping-list product now offers two choices: **Buy and add to pantry** adds its listed amount to Grocy stock and marks it done; **Mark done** only crosses it off the list.
- Use **Mark done** when a scanner or importer has already added the groceries to your pantry. Note-only items still check off directly.
- The **Shopping purchases** setting has been removed. Stillroom scanning, shopping-trip purchases, BarcodeBuddy, and other importers can all be used without switching a global setting.
- Uncheck a completed item to put it back on the list. This leaves your pantry stock unchanged.

### Confirm before removing

- Press and hold a shopping item to open **Remove [item]?**. Tap **Remove** to delete it from the list, or **Cancel** to keep it.
- Removing a list item leaves your pantry stock unchanged.

### Safer shopping updates

- Items waiting to sync or needing review block further purchase, completion, and removal actions until their existing change is resolved.
- If the old setting had paused a purchase, upgrading keeps it for review instead of sending it automatically.

## 0.3 — 2026-10-10

More help with shopping, everyday tasks, and time in the kitchen. Download `Stillroom-0.3.apk` from [Releases](https://github.com/exquisiteskink/Stillroom/releases/latest) and install it over the official 0.2 release to keep your saved accounts.

### Shopping made simpler

- Shopping lists have cleaner, compact rows. Tap an amount to change it, or press and hold an item to remove it.
- Checking off a product adds that amount to your pantry. Note-only items simply check off. Use **Remove checked items** to tidy up the list without changing pantry stock.
- If another scanner or importer already adds your groceries to Grocy, choose it under **Settings → Grocy add-ons → Shopping purchases** so Stillroom's checkboxes only cross items off.
- The scanner has a new **Shopping trip** mode: scan several products, review amounts, prices, and dates together, then confirm your purchases. Your draft is saved if you leave the screen.
- Purchase reviews keep track of what succeeded and what still needs attention, helping prevent the same purchase being sent twice after an interruption.

### Your tasks, close at hand

- Today now shows tasks assigned to you and tasks shared with everyone. Add a task or check one off from the home screen.
- Filter tasks by category, view completed tasks, and reopen them in Today or Household.
- Create a category while adding or editing a task, without losing what you have typed.
- A new home-screen widget shows your tasks. Optional daily reminders now include your due and overdue tasks.

### Pick up where you left off in the kitchen

- Save your place in a recipe, check off ingredients, and resume cooking later.
- Start cooking timers and receive a notification when time is up, even after leaving the recipe screen. Saved timers are restored when you reopen the app or restart your phone.
- Timers follow your active account. Android's notification and precise-timer settings control how promptly alerts arrive.

### Better with your Grocy setup

- Stillroom checks for changes made in Grocy or other connected tools while the app is open, keeping your screens more up to date.
- Screens and actions now follow the features enabled on your Grocy server.
- **Settings → Grocy add-ons** lets you control public barcode lookups, save a web add-on shortcut, and connect BarcodeBuddy for reviewed scans using an administrator account.
- Browse and manage Grocy custom records, including image and file attachments. Existing product attachments can also be viewed or saved.

### Scanner and reliability improvements

- Barcode reviews use saved amounts, units, stores, prices, and notes more accurately, including pack conversions and products sold by weight.
- Grocy's own product and stock labels are recognized. Other household labels can open the matching page in Grocy.
- Product pictures appear in scanner reviews, and Grocy lookup suggestions retain more useful product details.
- Task and purchase controls show when a change is waiting to sync and guard against repeated submissions. Tasks from a previous account stay hidden when you switch households.

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
