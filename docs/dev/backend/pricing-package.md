# Backend Pricing package

This guide explains the Kotlin code in
[`backend/src/shop/voenix/pricing`](../../../backend/src/shop/voenix/pricing).

## What this package does

Pricing calculates purchase and sales amounts from integer cents, percentage
inputs, and two VAT entries. It can calculate without saving, build a default
input, create a Price, read a Price, and update a Price.

Only calculation inputs and VAT IDs are stored. Net, tax, gross, and calculated
percentages are derived every time a Price is read. A later VAT name or percent
change therefore changes the next Pricing response. Prices are not historical
snapshots.

The standalone module deliberately has no list or delete endpoint. Article,
Prompt, and Cart relationships are added only when those modules are migrated.

## The important types

The package contains 16 production files. They fall into five groups:

- [`PriceInput.kt`](../../../backend/src/shop/voenix/pricing/PriceInput.kt),
  [`CalculatedPrice.kt`](../../../backend/src/shop/voenix/pricing/CalculatedPrice.kt),
  [`PriceAmount.kt`](../../../backend/src/shop/voenix/pricing/PriceAmount.kt), and
  [`PriceVat.kt`](../../../backend/src/shop/voenix/pricing/PriceVat.kt) define
  the request, response, monetary amount, and compact VAT projection.
- [`PriceCalculationMode.kt`](../../../backend/src/shop/voenix/pricing/PriceCalculationMode.kt),
  [`PurchaseActiveRow.kt`](../../../backend/src/shop/voenix/pricing/PurchaseActiveRow.kt),
  and [`SalesActiveRow.kt`](../../../backend/src/shop/voenix/pricing/SalesActiveRow.kt)
  select which inputs drive a calculation.
- [`PriceCalculator.kt`](../../../backend/src/shop/voenix/pricing/PriceCalculator.kt)
  is the pure calculation code, while
  [`PriceInputValidator.kt`](../../../backend/src/shop/voenix/pricing/PriceInputValidator.kt)
  owns the field rules.
  [`PricePercentagePolicy.kt`](../../../backend/src/shop/voenix/pricing/PricePercentagePolicy.kt)
  keeps the shared precision, scale, range, and normalization policy in one
  place.
- [`PriceOperations.kt`](../../../backend/src/shop/voenix/pricing/PriceOperations.kt),
  [`PriceService.kt`](../../../backend/src/shop/voenix/pricing/PriceService.kt),
  and [`PriceRoutes.kt`](../../../backend/src/shop/voenix/pricing/PriceRoutes.kt)
  form the application and HTTP boundary.
- [`Prices.kt`](../../../backend/src/shop/voenix/pricing/Prices.kt) and
  [`PriceRepository.kt`](../../../backend/src/shop/voenix/pricing/PriceRepository.kt)
  own persistence. [`BigDecimalJsonNumberSerializer.kt`](../../../backend/src/shop/voenix/pricing/BigDecimalJsonNumberSerializer.kt)
  keeps decimal percentages compatible with JSON numbers.

Every file follows the backend rule of exactly one top-level Kotlin type.

## How a calculation works

Money uses integer cents. `PriceAmount` contains `net`, `tax`, and `gross`.
`PriceCalculationMode.NET` means the submitted value is net; `GROSS` means it
already includes VAT.

The calculator works in this order:

1. calculate the purchase price with the purchase VAT;
2. calculate purchase cost from either a fixed amount (`COST`) or a percentage
   of the purchase price (`COST_PERCENT`);
3. add the net, tax, and gross components separately to form the purchase total;
4. calculate sales from a fixed margin (`MARGIN`), percentage margin
   (`MARGIN_PERCENT`), or final total (`TOTAL`); and
5. return all normalized inputs and calculated values.

Cent values and calculated percentages use `RoundingMode.HALF_UP`, which rounds
midpoints away from zero. Calculated percentages have two decimal places. A
zero base produces `0` instead of dividing by zero.

Negative margins are allowed when the resulting sales total is still
non-negative. Integer operations are checked, so an overflow never silently
wraps into a different price.

## Validation and normalization

Both `purchaseVatId` and `salesVatId` must be positive and must reference an
existing VAT entry. The purchase price must not be negative. The active
purchase-cost input and an active sales total must also be non-negative.

Active percentage inputs may have at most two relevant decimal places and must
fit into four integer digits. Purchase cost percentages range from `0` through
`9999.99`; sales margin percentages range from `-9999.99` through `9999.99`
because a negative margin can be valid. A value such as `12.340` is accepted
because its trailing zero does not add precision.

Inactive fields do not participate in validation. After validation, the
service replaces them with zero before calculation and persistence. For
example, selecting `COST_PERCENT` stores `purchaseCostInputCents` as `0`.

HTTP validation and direct service calls use the same
`PriceInputValidator`. Field errors use the shared shape:

```json
{
  "message": "Validation failed",
  "errors": {
    "purchaseVatId": ["Purchase VAT not found"]
  }
}
```

## Decimal percentages and JSON

Percentages use `BigDecimal`; they never pass through `Double`. This avoids
binary floating-point surprises in price calculations.

The frontend contract still uses JSON numbers such as `12.5`, not strings such
as `"12.5"`. `BigDecimalJsonNumberSerializer` reads and writes numeric JSON
tokens while preserving the exact plain decimal representation. PostgreSQL
stores percentage inputs as `numeric(6, 2)`, and Exposed maps them with its
standard `decimal(6, 2)` column type. Validation rejects excessive precision
or magnitude before persistence. The service then normalizes accepted values to
scale two without rounding, so responses stay identical before and after a
database round trip.

## HTTP API

Every route requires an authenticated admin. Both POST routes and PUT also
require the shared `X-XSRF-TOKEN` header.

| Method and path | Success | Persistence |
| --- | --- | --- |
| `POST /api/admin/prices/calculate` | `200` with `id: null` | none |
| `POST /api/admin/prices` | `201`, body, and `Location` header | inserts one Price |
| `GET /api/admin/prices/default` | `200` with `id: null` | none |
| `GET /api/admin/prices/{id}` | `200` with a recomputed Price | read only |
| `PUT /api/admin/prices/{id}` | `200` with the updated Price | replaces all inputs |

The create endpoint is an approved development-phase addition. The original
.NET application creates Price rows only inside Article workflows. Keeping the
endpoint now lets us develop and test Pricing independently; Article will later
reuse the same application operation.

The default endpoint prefers the VAT marked as default. If none is marked, it
uses the VAT with the smallest ID. If no VAT exists, it returns
`400 No VAT is configured`. Missing Prices return `404 Price not found`.

## Persistence and transaction composition

[`V4__create_prices.sql`](../../../backend/resources/db/migration/V4__create_prices.sql)
creates the `prices` table. It contains only input fields and required purchase
and sales VAT IDs. PostgreSQL adds:

- restricted foreign keys and an index for each VAT relationship;
- checks for all persisted enum strings; and
- checks for the four non-negative persisted inputs.

The repository uses Exposed `suspendTransaction`. When Pricing is called on its
own, it starts the transaction. When the future Article service already owns a
transaction for the same database, Exposed reuses it. A rollback test proves
that creating a Price inside an outer transaction does not commit separately.
This is what will allow Article and Price to be written atomically.

Deleting a VAT that is referenced by a Price is rejected by PostgreSQL. The VAT
API exposes this expected domain outcome as `409 VAT is in use`.

## Tests and verification

The focused tests cover the pure formulas and rounding, active-field
validation, service behavior against PostgreSQL, auth and CSRF ordering, exact
JSON responses, the complete admin flow, Flyway constraints, outer-transaction
rollback, VAT deletion, and recalculation after a VAT change.

Run the final backend gate from `backend/`:

```sh
./kotlin do ktfmt
./kotlin check
```
