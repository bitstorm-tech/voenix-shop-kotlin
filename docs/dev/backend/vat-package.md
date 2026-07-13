# Backend VAT package

This guide explains the Kotlin code in
[`backend/src/shop/voenix/vat`](../../../backend/src/shop/voenix/vat).

## What this package does

VAT entries are admin-managed master data. Each entry has:

- a unique name;
- an integer percentage from 0 through 100;
- an optional description; and
- an `isDefault` flag.

The package provides create, list, read, update, and delete operations under
`/api/admin/vat`. There is no public VAT route.

At most one entry can be the default. It is also valid to have no default:
updating the current default with `isDefault: false` or deleting it does not
automatically choose another entry.

## The package structure

The package contains eleven production files:

- [`Vat.kt`](../../../backend/src/shop/voenix/vat/Vat.kt) is the
  stored value and JSON response.
- [`VatInput.kt`](../../../backend/src/shop/voenix/vat/VatInput.kt)
  is shared by create and update requests.
- [`VatInputValidator.kt`](../../../backend/src/shop/voenix/vat/VatInputValidator.kt)
  contains every field rule in one place.
- [`VatOperations.kt`](../../../backend/src/shop/voenix/vat/VatOperations.kt)
  is the use-case interface used by the routes.
- [`VatResult.kt`](../../../backend/src/shop/voenix/vat/VatResult.kt)
  lists the expected success and failure outcomes.
- [`VatService.kt`](../../../backend/src/shop/voenix/vat/VatService.kt)
  validates, normalizes, and maps repository results.
- [`VatRepository.kt`](../../../backend/src/shop/voenix/vat/VatRepository.kt)
  owns the Exposed queries, transactions, and conflict detection.
- [`VatWrite.kt`](../../../backend/src/shop/voenix/vat/VatWrite.kt) is
  the internal validated and normalized value passed to persistence.
- [`VatWriteResult.kt`](../../../backend/src/shop/voenix/vat/VatWriteResult.kt)
  is the internal result of a repository create or update.
- [`ValueAddedTaxes.kt`](../../../backend/src/shop/voenix/vat/ValueAddedTaxes.kt)
  maps the PostgreSQL table.
- [`VatRoutes.kt`](../../../backend/src/shop/voenix/vat/VatRoutes.kt)
  binds HTTP requests and maps results to responses.

Every file follows the backend rule of exactly one top-level Kotlin type whose
name matches the file.

## Follow one create request

An admin can send:

```json
{
  "name": " Standard ",
  "percent": 19,
  "description": " German standard rate ",
  "isDefault": true
}
```

The request passes through these steps:

1. Ktor authenticates the session.
2. `AdminRouteProtection` requires the `ADMIN` role and a valid CSRF token.
3. Content Negotiation reads the JSON as `VatInput`.
4. the application-owned `RequestValidation` plugin calls
   `VatInputValidator`;
5. `VatService` calls the same validator for callers that do not use HTTP;
6. the service trims the name and description;
7. `VatRepository` demotes an existing default and inserts the new entry in
   one serializable transaction. If any unique rule rejects the write,
   `PostgresWrite` returns the generic typed conflict result; and
8. the route returns `201 Created`, the normalized `Vat`, and
   `Location: /api/admin/vat/{id}`.

An invalid HTTP request stops after step 4. It cannot call `VatOperations`.

## Validation and normalization

The rules are:

| Field | Rule | Error |
| --- | --- | --- |
| `name` | required after trimming | `Name is required` |
| `name` | at most 255 trimmed characters | `Name must be at most 255 characters` |
| `percent` | required | `Percent is required` |
| `percent` | from 0 through 100 | `Percent must be between 0 and 100` |

`isDefault` defaults to `false` when the JSON field is missing.
`description` may be missing or `null`. A blank description becomes
`null`; a non-blank description is trimmed.

Normalization happens only after validation succeeds. The repository therefore
receives only valid, normalized values.

## HTTP API

Every route requires an authenticated admin. POST, PUT, and DELETE also require
the `X-XSRF-TOKEN` header.

| Method and path | Success |
| --- | --- |
| `GET /api/admin/vat` | `200` with a direct array, ordered by name then ID |
| `GET /api/admin/vat/{id}` | `200` with one VAT entry |
| `POST /api/admin/vat` | `201` with the entry and a `Location` header |
| `PUT /api/admin/vat/{id}` | `200` with the updated entry |
| `DELETE /api/admin/vat/{id}` | `204` with no body |

Missing entries return `404 VAT not found`. A duplicate normalized name
returns `409 VAT name already exists`. Unexpected database failures are
logged and returned as the generic `500 Internal server error`.

The JSON response shape remains:

```json
{
  "id": 1,
  "name": "Standard",
  "percent": 19,
  "description": "German standard rate",
  "isDefault": true
}
```

## Plugin and security ownership

[`Application.kt`](../../../backend/src/shop/voenix/Application.kt) installs
shared plugins once:

```kotlin
installHttpRuntime()
ApplicationAuth.install(this, authSettings)
countryModule(database)
vatModule(database)
```

`installHttpRuntime()` installs Content Negotiation, StatusPages, and one
RequestValidation plugin with typed Country and VAT registrations. The VAT
package does not install an application-wide plugin. `VatInput` implements the
feature-neutral `RequestValidationInput` interface, which lets shared
`StatusPages` recover structured field errors without a feature-specific
`Any` dispatch.

`VatRoutes` only installs the auth-owned `AdminRouteProtection` on the
authenticated `/api/admin/vat` subtree. New handlers added inside that
subtree are therefore protected by default.

## Conflict handling and concurrency

VAT follows the shared
[persistence error-handling pattern](persistence-error-handling.md). PostgreSQL
enforces the unique VAT rules. Any SQL state `23505` becomes
`VatWriteResult.Conflict`, which the service maps to `VatResult.Conflict`. The
route returns one generic `409` message without querying which unique rule
rejected the write.

VAT's integration tests cover duplicate names and concurrent writes. Other SQL
errors still become `DatabaseError`.

## PostgreSQL and Flyway

[`V2__create_value_added_taxes.sql`](../../../backend/resources/db/migration/V2__create_value_added_taxes.sql)
creates `value_added_taxes` with:

- the existing column names and PostgreSQL types;
- the `pk_value_added_taxes` primary key;
- the `ck_value_added_taxes_percent_range` check;
- the case-sensitive `ux_value_added_taxes_name` unique index; and
- the partial `ux_value_added_taxes_single_default` unique index, which prevents
  two rows from being default.

Create and update use the repository's `serializableTransaction` helper. It
configures Exposed's JDBC `suspendTransaction` with serializable isolation and
up to three attempts. Setting `isDefault = true` demotes the previous default
and writes the requested row inside the same transaction. The partial unique
index is the final concurrency-safe guarantee.

Flyway owns schema creation. Exposed never creates or changes production tables
at runtime. V2 creates VAT on an empty database and after the supported Country
V1 baseline.

## Approved database compatibility

Only the new Flyway database path is supported for VAT. An already existing EF
table named `value_added_taxes` is not adopted, inspected, or repaired. Such a
database must be migrated to the new path outside this feature before the
Kotlin application starts.

This is an intentional migration decision, not an accidental Flyway behavior.
Keeping V2 unconditional makes an unsupported mixed schema fail visibly instead
of starting with an unknown table definition or partially compatible data.

## Tests and verification

The VAT tests are:

| Test | Purpose |
| --- | --- |
| [`VatInputValidatorTest.kt`](../../../backend/test/shop/voenix/country/vat/VatInputValidatorTest.kt) | complete field-rule matrix and boundaries |
| [`VatServiceIntegrationTest.kt`](../../../backend/test/shop/voenix/country/vat/VatServiceIntegrationTest.kt) | normalization, ordering, defaults, rollback, generic conflicts, direct validation, concurrency, and database failures |
| [`VatRouteSecurityAndValidationTest.kt`](../../../backend/test/shop/voenix/country/vat/VatRouteSecurityAndValidationTest.kt) | admin/CSRF ordering, rejection before operations, and HTTP result mapping |
| [`VatAdminCrudIntegrationTest.kt`](../../../backend/test/shop/voenix/country/vat/VatAdminCrudIntegrationTest.kt) | complete protected CRUD through Ktor, Exposed, Flyway, and PostgreSQL |

Run the final backend gate from `backend/`:

```sh
./kotlin do ktfmt
./kotlin check
```
