# Quantity fractions — Stage 4

The `:fractions` module is pure Kotlin/JVM, with no Android, networking, persistence, or UI dependency. It is a quantity input/display utility. The only app consumer in this stage is a design-time Compose preview under `app/src/debug`; no production screen or stored data is changed.

## API and storage boundary

`QuantityFractions(FractionOptions(maxDenominator = 16))` exposes `parse(text, locale, field)` and `format(value, locale, field)`. Values use immutable `BigDecimal`, never binary floating point. Parse returns `ParsedQuantity(value, approximate)` or null for invalid input. Format returns `QuantityDisplay(text, approximate, fraction)` and never writes or replaces the supplied number.

The `format(parsedQuantity, locale, field)` overload preserves parse approximation metadata, including a leading `≈` on decimal fallback. Use it for user input with repeating fractions. The overload accepting a raw `BigDecimal` can mark only display approximation, because the number alone has no input provenance.

The default field is `NumericField.QUANTITY`. Call sites must opt in only for quantities. `PRICE`, `BARCODE`, `DATE`, and `ID` refuse parsing through this module; numeric formatting for those fields stays decimal. Keep identifier/date/barcode strings and their existing parsers outside this module, preserving leading zeros and syntax. Prices require their own currency formatter. This stage does not connect any production field to fraction handling.

Display strings are not serialization. Save the original parsed decimal, including its precision, and carry parse approximation metadata if needed. For example, formatting stored `0.5001` shows `≈½`; the stored value must remain `0.5001`. Parsing that display later represents a different value. Exact decimal/fraction displays round-trip numerically; approximate displays deliberately do not promise a lossless round-trip.

## Input grammar

Accepted examples: `1/2`, `½`, `1 1/2`, `1½`, `1 ½`, `0.5` (US/root), and `1,25` (German). Decimal punctuation comes from the supplied locale, including Arabic decimal separators; Unicode decimal digits are normalized. Locale defaults to `Locale.ROOT`, giving deterministic behavior. Grouping separators, currency, units, signs, exponent notation, dates, and trailing junk are rejected. Surrounding whitespace is allowed; a leading `≈` explicitly marks approximate input. Input length is limited to 4096 UTF-16 characters.

Slash fractions accept nonnegative numerators and positive denominators. Mixed fractions require a positive proper fractional part (numerator less than denominator). Negative inputs, including `-0`, are rejected; negative format values throw `IllegalArgumentException`. Zero formats as `0`. Input fractions are not constrained by the display denominator setting.

Terminating fractions divide exactly. Repeating fractions such as `1/3` use `MathContext.DECIMAL128` (34 significant digits) for the fractional part and set `approximate = true`. Whole parts are added exactly afterward, so very large mixed quantities retain their whole digits. Decimals retain all supplied digits. Approximation flags must be retained separately when a caller needs input provenance; a `BigDecimal` alone does not contain it.

## Display policy

The maximum display denominator is configurable from 1 through 10000; invalid settings throw `IllegalArgumentException`. The formatter chooses the closest proper fraction within the bound, preferring the smaller denominator on equal errors. Standard vulgar fraction glyphs are used when available: `0.5` → `½`, `1.25` → `1¼`. Other fractions use slash notation, with a space after whole parts, for example `1 1/20`.

Exact matches are unmarked. Inexact fraction displays are prefixed with `≈` and set `approximate = true`. A candidate is allowed only when absolute error is at most `0.0005` and relative error is at most `0.5%` of the original fractional part. Both bounds are fixed, conservative display rules, independent of the whole number. Candidates that round the fractional part to zero or one are never used. This avoids concealing a small nonzero amount or rounding a large quantity into a misleading whole number. Fractions outside these bounds fall back to the full decimal without rounding or an approximation marker. Decimal fallback uses the locale's decimal separator, omits redundant trailing zeros, and never uses scientific notation.

Use domain-specific decimal formatting instead when even this marked approximation is inappropriate. The utility has no units or measurement-error model.

## Validation

Run `mise run stage:4`. It builds the debug APK/preview, runs existing app JVM regressions and the pure module suite, and generates/checks JaCoCo coverage (minimum 80%). No emulator, Grocy mutation, or phone test is part of this gate.

Seeded property loops exercise thousands of exact round trips across US/German/Arabic locales, zero and negative rejection, repeating decimal denominators, and 256-bit whole values. Example tests cover malformed grammar, approximation markers, conservative decimal fallback, denominator configuration, excluded fields, and preservation of stored decimal values. Seeds are fixed for reproducibility. The test and coverage reports are `fractions/build/reports/tests/test/index.html` and `fractions/build/reports/jacoco/test/html/index.html`; actual gate results are recorded in `STATUS.md`.

## Display style setting

Settings → Appearance → **Show quantities as** offers **Fractions** (default, matching what screens showed before the setting) or **Decimals**. The choice is stored with the other device-local shell preferences (`shell_preferences`, key `quantity_style`). Every quantity display reads one shared `QuantityFormatter` (`:fractions`), provided to Compose as `LocalQuantityFormatter` by `StillroomShell`.

- Fractions: whole part plus the closest of ⅛ ¼ ⅓ ½ ⅔ ¾ (`1½`, `2¾`) when the fractional part is within **0.01** of it, otherwise the decimal display. A fraction more than **0.001** away from the value is prefixed with `≈`, so `0.333`/`0.6667` show `⅓`/`⅔` and `0.33` shows `≈⅓`. 3/8, 5/8, 7/8 and other fractions fall back to decimals.
- Decimals: half-up to 3 places, trailing zeros trimmed, locale separator, no grouping or exponent; a nonzero amount that would round to 0 keeps 3 significant digits.
- Negative journal amounts get a leading `-`.

This is display only. Editors still save the original server decimal unless the field is edited, and every quantity input still accepts `1 1/2`, `1½` and `1.5` in either style. Conflict-review rows in Shop deliberately show the raw server value.
