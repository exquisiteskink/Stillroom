# Stillroom copy deck

Source review: 2026-10-07; `ui/*Screen.kt`, `StillroomShell.kt`, `StateComponents.kt`, `CameraScanner.kt`, `domain/{Stock,Scanner,Accounts,Catalog,Recipes,Household,Shopping}.kt`, `background/{HouseholdWork,HouseholdWidgets}.kt`. This deck specifies existing tasks; it does not authorize features or promise server behavior that has not happened. Apply mappings to visible strings and accessibility names, retaining server-owned product names, units, custom-field captions, instructions, and permission boundaries.

## Principles

1. Name the household task in the user's language: Nielsen match between system and real world. “Add stock” and “Use stock” display existing Purchase/Consume operations.
2. State what happened and who confirmed it: Nielsen visibility of system status. Local intent, Grocy confirmation, and uncertain outcomes have different copy.
3. Say how to recover at the point of failure: Nielsen help users recognize, diagnose, and recover from errors. Never give a write-retry instruction when the result is uncertain.
4. Keep names stable; write actions as verbs: Nielsen consistency and recognition over recall. Navigation uses concrete nouns; buttons say what they do.
5. Remove idioms, personality, and implementation jargon from working screens: cognitive-load reduction and WCAG 2.2 labels/instructions. Use specific fields, not paragraphs explaining the implementation.

## Shared language and states

| Existing / context | Replacement |
|---|---|
| Today / Pantry / Shop / Meals / Household | Keep these established section names; labels are nouns, not commands. |
| Batteries & more | Records |
| Grocy journal | Stock history |
| Pending changes | Pending changes; retain as recovery destination, not routine navigation. |
| Just a moment / Setting the table. | Loading {section}… / Loading your saved data. Only mention Grocy when fetching Grocy. |
| Welcome to Stillroom / The account is not connected. | Connect to Grocy / Connect an account to see your household. Action: Connect account. |
| Something went wrong / Try again | Could not load {section} / {specific read failure}. Action: Reload. |
| You're offline / We'll keep the last kitchen snapshot until you're back. | Offline / Showing saved data. Changes may need review when you reconnect. |
| This section is closed / kitchen metaphor | Access unavailable / This account cannot view {section}. Action: Manage accounts when already available. |
| Generic mutation success | {Object} saved only after known confirmation. Pending: Waiting for Grocy. Unknown: Result needs review. |
| Permission cannot be reverified | Access needs verification / Verify this account's permissions in Accounts. |
| Empty filtered results | No matching {objects} / Change the filters to see other {objects}. Action: Clear filters if reset is already implemented. |

Do not wrap raw exception messages or API paths as the only user-facing explanation. Preserve a support reference separately. Use singular/plural counts, local dates and quantities, and explicit units. Never display `null`, raw booleans, missing dates, or internal enum names.

## Scanner: task copy and every state

| State/control | Copy |
|---|---|
| Title | Scan |
| Mode labels | Add stock / Use stock |
| Ready, add | Scan a barcode to add stock. |
| Ready, use | Scan a barcode to use stock. |
| Camera opt-in | Use camera |
| Permission explanation | Allow camera access to scan barcodes. You can also enter the code. |
| Permission denied | Camera access is off. Enter the code, or allow camera access in Android settings. |
| Manual disclosure | Enter code |
| Manual field / formats | Barcode or QR text / Barcode / UPC-E / Household QR code |
| Look up manual entry | Find product |
| Multiple detected codes | Choose a barcode / Choose {printed code}; secondary action: Scan again. |
| Looking up | Finding product… |
| Product ambiguity | Choose a product / {product name}; accessible action: Choose {product name}. |
| Detected product review | Add stock / Use stock; product name; Quantity; Unit; Location. |
| Submit | Confirm add / Confirm use |
| Submit busy | Adding stock… / Recording use… |
| Confirmed | Added {quantity} {unit} of {product}. / Used {quantity} {unit} of {product}. |
| Next package | Scan next |
| Deliberate same-code repeat | Scan same code again |
| Duplicate ignored | Code already scanned. To record another package, choose “Scan same code again”. Informational, not an error alert. |
| Unknown barcode | Product not found / This code is not linked to a Grocy product. |
| Unknown and no create permission | Ask someone with product-edit access to add this barcode in Grocy. |
| New product heading | Create product |
| Suggestion source | Suggested by {source}; saved suggestion: Saved suggestion. Never imply this is household stock. |
| New-product description | Check the product details before saving. |
| Fields | Product name / Description or brand / Stock unit / Location |
| Unit hint | This unit is used for purchases and use. You can change units in Grocy. |
| Missing reference records | Add a stock unit and location in Grocy, then find this code again. |
| Confirm and create in Grocy | Create product |
| Creation busy | Creating product… |
| Creation uncertain | Product creation needs review. Check Pending changes before creating it again. |
| Stock write uncertain | Grocy may have saved this change. Check Pending changes before recording it again. Action: Review pending changes. |
| Lookup read failure | Could not find this barcode. Check your connection and try again. Action: Find product. |
| Bad checksum | Check the printed barcode digits. The check digit does not match. |
| Bad manual text | Enter a code with 1–512 characters. Remove line breaks and control characters. |
| Package date | Best-before date; hint: YYYY-MM-DD. For scanner purchase, required with inline “Enter the package's best-before date.” Never say optional while gating submission. |
| No external date | Use the date printed on the package. |
| Torch / zoom names | Turn flashlight on / Turn flashlight off / Zoom; label actual selected zoom. |

## Today and Pantry

| Context | Copy |
|---|---|
| Greeting pair | Today plus formatted date; remove duplicate greeting and “Hello, {username}”. Show account in account control. |
| To do today / On the table | Due chores / Planned meals |
| No due chores | No chores due today / View other chores in Household. Action: View chores. |
| No meals | No meals planned today / Plan a meal in Meals. Action: View meals. |
| Pantry filters | All / In stock / Use soon / Running low / Opened |
| Everything else already listed | Remove; category headings already explain grouping. |
| Show every location | Show all locations |
| Purchase / Consume / Inventory / Transfer / Open / Spoilage | Add stock / Use stock / Set stock amount / Move stock / Mark opened / Record spoilage |
| Quantity field | Quantity; supporting text: Decimals and fractions accepted, for example 1.5 or 1/2. |
| Inventory field | New stock amount |
| Input / stock quantity summary | {entered quantity} {unit} = {stock quantity} {stock unit}; show only when conversion matters. |
| Transfer | From location / To location |
| Due date | Best-before date; format and requiredness in supporting text. |
| Price | Price per {stock unit}; supporting text: Optional. |
| Action submission | Confirm add / Confirm use / Save stock amount / Move stock / Mark opened / Record spoilage |
| Tare | Enter weight including the container. Grocy calculates the stock weight. |
| Product detail | In stock / Opened / Best-before / Locations / Stock entries / Price history / Stock history; resolve location, parent product and store IDs to names. |
| Stock entry status | Opened / Unopened; spoilage: Spoiled; undone: Undone. |
| Undo booking / transaction | Undo change / Undo transaction; keep reference ID in quiet metadata only when useful. |
| Empty pantry | No products in stock / Add stock for an existing product. Action: Add stock when authorized. |
| Missing details | Product unavailable / Reload the pantry to check Grocy. |

## Shopping

| Context | Copy |
|---|---|
| Create-list fields/action | List name / Create list |
| At the store | In-store view |
| Group chips | Group by: Category / Store / No grouping |
| Estimate | Known total: {amount}. {count} items have no price. Do not imply a full total or assume currency. |
| No lists | No shopping lists / Create a list to start adding items. |
| Empty list | No items on this list / Add an item. |
| Bought it | Add to stock |
| Mark checkbox | Mark {item} complete / Mark {item} incomplete; completion alone does not promise stock booking. |
| Still catching up with Grocy | Pending: Waiting for Grocy. Failed: Could not save. Conflict/uncertain: Needs review. |
| Note only | Item without a product |
| Notes / quantity | Notes / Quantity; keep optionality and fractions in supporting text. |
| Queue reviewed change | Add item / Save item, determined by create/edit context. Pending feedback conveys queuing. |
| Purchase review | Add to stock |
| Review paragraph | Review this purchase. The list item is completed after Grocy confirms the stock change. |
| Back without purchasing | Cancel |
| Actual quantity in {unit} | Quantity ({unit}) |
| Purchase note | Note |
| Confirm purchase and complete item | Confirm purchase |
| Conflict review | Review shopping change |
| Original / Your intent / Server | Before your edit / Your edit / In Grocy |
| Missing outcome | Item or result unavailable. |
| Keep server version | Keep Grocy version |
| Review a merged edit | Review combined edit |
| Never-resent explanation | This purchase will not be sent again. Check Grocy before recording another purchase. |

## Meals and recipes

Recipes / Meal plan are stable tabs. “This week” may label the selected date range only if the current list is actually filtered to that week. Actions: Add recipe, Plan meal, Import recipe, Review recipe, Edit recipe, Delete recipe, Add ingredient, Remove ingredient, Choose photo, Remove photo, Set servings, Add missing ingredients, Start cooking, Finish cooking, Confirm stock use. Replace “We cooked this” with “Finish cooking”: it opens stock review and must not sound like an already confirmed write. Replace “All recipes” with “Back to recipes”.

Fields: Recipe name, Description, Instructions, Base servings, Servings, Product, Unit, Quantity, Ingredient note, Day, Meal, Notes, Recipe URL. Supporting text: “One step per line.” and date format. “Unsectioned” becomes “No meal section”. Cooking controls: Previous step, Next step, Minutes, Start timer, Cancel timer; completion: Timer finished. Ingredient checkbox name: “Mark {ingredient} ready”. Missing ingredients summary: “{quantity} {unit} needed”.

Import: “Review imported recipe”; “Read public recipe metadata” becomes “Load recipe details”; explanation: “Match each ingredient to a product and unit in Grocy.” “Reviewed amount” becomes “Quantity”. “Exclude ingredient” becomes “Remove ingredient”. Manual field: Ingredient; action: Add ingredient. No inferred stock consumption. Empty recipes: “No recipes / Add a recipe or import a link.” Empty plan: “No planned meals / Plan a meal.” Missing recipe: “Recipe unavailable / Return to recipes and reload.” Unsupported source: “Could not read this recipe link. Enter the recipe details manually.”

## Household and records

Actions: Add chore, Add task, Edit chore, Edit task, Mark done, Skip chore, View history, Delete chore, Delete task, Save chore, Save task. Empty chores: “No chores / Add a chore to set its schedule.” Child/my-only empty: “No chores assigned to you.” History: “No completed or skipped chores.” Empty tasks: “No open tasks.” Do not substitute a congratulatory message for factual status.

Fields: Chore name, Task name, Instructions, Notes, Repeat, Repeat every, Days of month, Weekdays, Start date and time, Assign to, Next assigned person, Due date, Category. “Use rotation” becomes “Use scheduled rotation”. “Track date only” becomes “Record date only”; helper: “Do not record a completion time.” “Rollover” becomes “Carry unfinished chores forward”; preserve actual existing semantics. Raw recurrence values get display names: Daily, Weekly, Monthly, Every {count} days, Manually scheduled. Raw assignment values get Anyone / Choose people / Rotate assignment. If users cannot be fetched: “This account cannot view household members.”

Records names remain Batteries, Equipment, Products, Locations, Stores, Units, Unit conversions, Task categories. Create buttons use singular objects: Add battery, Add equipment, Add product, Add location, Add store, Add unit, Add conversion, Add category. Battery actions: Record charge, View charge history, Undo charge. Status: Never charged / Not scheduled. Equipment manual: “Manual available in Grocy: {filename}”; do not imply a working attachment action.

Catalog field display mapping: Name, Description, Used in, Charge every, Active, Plural name, Product, From unit, To unit, Conversion factor, Default location, Default store, Stock unit, Purchase unit, Use unit, Price unit, Minimum stock amount, Best-before period, Best-before period after opening, Default use amount, Calories per stock unit, Allow opening, Show in stock overview, Check stock for recipes. If inverse toggles are used, invert the stored boolean correctly; otherwise retain “Disable opening / Hide from stock overview / Skip recipe stock checks”. Supporting text: charge interval “Days; 0 means no schedule”; product conversion “Leave empty to apply to all products”; expiry fields “Days; −1 means no expiry”; factor “Multiply the source amount by this value”. Unit shortcut: “Use stock unit for all quantities”.

Dynamic custom fields preserve server captions. Unsupported editor: “{caption} cannot be edited here. Its saved value is kept.” Unsupported required field on creation: “Create this record in Grocy. {caption} is required and cannot be edited here.” Do not report “Skipped type…” as successful user intent.

Deletion title: “Delete {object name}?” Body: “Remove this {object type} from Grocy?” Confirm: “Delete {object type}”; secondary: Cancel. Reference rejection: “This {type} is still in use. Update its references in Grocy before deleting it.” Do not bury the actual object behind “this record”.

## Accounts, Settings, pending changes, widgets and reminders

Accounts: Connect to Grocy; Server URL; API key; Connect; Cancel; Saved accounts; Current account; Use account; Verify permissions; Log out. HTTP setting: “Allow HTTP”; explanation: “HTTP sends your API key without encryption. Use only for a trusted local test server.” Child verification heading: “Verify account permissions”; helper: “Choose an administrator account on this server to verify access.” Unverified: “Permissions need verification.” Empty grants: “Grocy has not granted this account any permissions.” Keep any known server compatibility warning specific to the affected function.

Settings groups: Navigation, Appearance, Reminders. Labels: Show {section}, Theme, System, Light, Dark, Use Android colors, Reduce motion, Enable reminders, Quiet hours start, Quiet hours end; Save reminders. Notification off: “Allow notifications in Android settings to receive reminders.” On: “Notifications allowed.” Saved: “Reminder settings saved.” At-least-one section: “Keep at least one section visible.” Quiet hours helper: “24-hour time, HH:mm.”

Pending changes intro: “Review changes waiting for Grocy. If a result is uncertain, check Grocy before recording it again.” Action: Refresh / Check Grocy. States: Waiting to send, Sending, Confirmed by Grocy, Needs review, Could not save, Discarded. Empty: “No pending changes.” Checked-unresolved: “Grocy was checked. This change still needs review.” Use task names (“Add stock: {product}”) before low-level IDs. Technical method/path/reference belongs in diagnostics, not the normal row.

Widget headings: Chores · {account}, Shopping · {account}, Scan · {account}. Actions: View chores, View shopping list, Scan barcode. Bodies: “Connect an account in Stillroom.” / “This account cannot view {section}.” / “No chores due in saved data.” / “No saved shopping lists.” / “Scan a barcode or enter a code.” Keep saved-data indication separate from the button. Reminder title: Stillroom · {account}; body: “Saved data: {count} chores due · {count} products due or expired.” Do not claim freshness or separate due/expired counts when only an aggregate exists.

## Validation and error template rules

Every required field gets “Enter {field}.” / “Choose {field}.” Numeric constraints: “Enter a quantity greater than 0.” Inventory permits zero: “Enter 0 or a positive amount.” Factor: “Enter a conversion factor greater than 0.” Fractions: “Enter a number or fraction, such as 1.5 or 1/2.” Date: “Enter a valid date in YYYY-MM-DD format.” Price: “Enter 0 or a positive price.” Same-unit conversion: “Choose two different units.” Same-location transfer: “Choose a different destination.” Required name and max length: “Enter a {type} name.” / “Use {limit} characters or fewer.” Inline feedback appears at the relevant field; retain the form input.

Account URL: “Enter your Grocy server URL.” / “Enter a valid server URL.” / “Use HTTPS, or allow HTTP for your local test server.” / “Remove credentials, query parameters and fragments from the server URL.” / “Enter a port between 1 and 65535.” API QR: “Scan a Grocy API-key QR code.” Calendar QR: “This is a calendar link. Scan an API-key QR code.” Unauthorized key: “Grocy rejected this API key. Check the key and this account's permissions.” Unreachable read: “Could not connect to Grocy. Check the server URL and connection.”

Read recovery may offer Reload. A definitive rejection may offer Edit details. An uncertain write must offer Review pending changes or Check Grocy and must never offer Retry as its primary action. Generic “Failed requirement”, exception class names and raw endpoint errors must not escape into product copy. Status/copy must reflect actual domain outcome, not infer success from dismissing a dialog.
