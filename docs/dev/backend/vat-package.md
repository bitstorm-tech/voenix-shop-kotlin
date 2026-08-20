# Backend VAT package

This guide explains the Kotlin code in
[`backend/modules/vat/src/shop/voenix/vat`](../../../backend/modules/vat/src/shop/voenix/vat).

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

The package contains five production files. Each file holds one component
together with the types that component owns, following
[Kotlin source file organization](source-file-organization.md).

- [`Vat.kt`](../../../backend/modules/vat/src/shop/voenix/vat/Vat.kt) is the
  stored value and JSON response.
- [`VatRoutes.kt`](../../../backend/modules/vat/src/shop/voenix/vat/VatRoutes.kt)
  binds HTTP requests and maps results to responses. It also holds `VatInput`,
  the request type shared by create and update. Its `validate()` method contains
  every field rule in one place. `VatInput` is `internal`: only this module
  binds and validates it. Its companion object stays non-private, because
  Kotlinx Serialization publishes the generated serializer through it (see
  [Kotlin code quality](kotlin-code-quality.md)); only the constants inside are
  private.
- [`VatService.kt`](../../../backend/modules/vat/src/shop/voenix/vat/VatService.kt)
  validates, normalizes, and maps repository results. Next to the service the
  file holds the two seams the module is used through: `VatOperations`, the
  internal use-case interface the routes call, and `VatReader`, the public
  read-only list and batch-lookup capability consumed by Pricing.
- [`VatRepository.kt`](../../../backend/modules/vat/src/shop/voenix/vat/VatRepository.kt)
  owns every Exposed query on the VAT table, its transactions, and conflict
  detection. It implements `VatReader`, while remaining internal to the VAT
  module. The file also holds everything that only persistence uses:
  `ValueAddedTaxes`, which maps the PostgreSQL table; `VatWrite`, the validated
  and normalized value passed to persistence; `VatWriteResult`, the result of a
  create or update; and `VatDeleteResult`, which distinguishes a successful
  delete, a missing entry, and a VAT entry that is still referenced.
- [`VatModule.kt`](../../../backend/modules/vat/src/shop/voenix/vat/VatModule.kt)
  owns module construction, route installation, and validation registration.
  The runtime handle `VatModule` and its factory `createVatModule` are
  internal, because no caller outside this module needs the assembled handle:
  `installVatModule(database)` already returns the one capability other modules
  use, `VatReader`. Next to it, the public `createVatReader(database)` builds
  that reader alone, without installing the admin routes. Production never
  needs it; it exists so an integration test in a consuming compilation module,
  such as Pricing, can work against a real database while the vat write seam
  stays internal. It hands out nothing but the public capability.
- The shared [`OperationResult`](operation-results.md) lists the expected success and
  failure outcomes returned by `VatOperations`.

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
   `VatInput.validate()`;
5. `VatService` calls the same input method for callers that do not use HTTP;
6. the service trims the name and description;
7. `VatRepository` demotes an existing default and inserts the new entry in
   one serializable transaction. If any unique rule rejects the write,
   `executePostgresWrite` returns the generic typed conflict result; and
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

`VatInput.validate()` implements the shared `Validatable` contract and all
field rules directly. HTTP visibility does not require the Kotlin input type to
be part of the module's public interface.
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
returns `409 VAT entry already exists`. Deleting a VAT that is referenced by a
Price returns `409 VAT is in use`. Unexpected database failures are logged and
returned as the generic `500 Internal server error`.

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

[`Application.kt`](../../../backend/app/src/shop/voenix/Application.kt) installs
shared plugins once:

```kotlin
installHttpRuntime()
install(RequestValidation) {
    validateVatRequests()
}
installAuthModule(authSettings)
val vats = installVatModule(database)
```

`installHttpRuntime()` installs Content Negotiation and StatusPages. The app
installs one Request Validation plugin and calls `validateVatRequests()` inside
its configuration. The VAT package does not install an application-wide
plugin. `VatInput` implements the module-neutral `Validatable` interface,
which lets shared
`StatusPages` recover structured field errors without a module-specific
`Any` dispatch.

`installVatRoutes` only installs the auth-owned `AdminRouteProtection` on the
authenticated `/api/admin/vat` subtree. New handlers added inside that
subtree are therefore protected by default.

`installVatModule(database)` returns `VatReader`. `app` passes that narrow
capability to Pricing; Pricing cannot access `VatRepository`,
`ValueAddedTaxes`, `VatOperations`, or `VatInput`, because all of them are
internal to this compilation module. The only other way to obtain a reader is
`createVatReader(database)`, which returns the same capability without
installing routes.

## Conflict handling and concurrency

VAT follows the shared
[persistence error-handling pattern](persistence-error-handling.md). PostgreSQL
enforces the unique VAT rules. Any SQL state `23505` becomes
`VatWriteResult.Conflict`, which the service maps to `OperationResult.Conflict`. The
route returns one generic `409` message without querying which unique rule
rejected the write.

VAT's integration tests cover duplicate names and concurrent writes. Other SQL
errors still become `UnexpectedFailure`.

Price foreign keys use restricted deletion. `VatRepository.delete` maps the
unambiguous PostgreSQL foreign-key violation to `VatDeleteResult.InUse`; the
service then returns `OperationResult.Conflict`. The repository does not inspect
a constraint name or database error message.

`VatDeleteInUseIntegrationTest` covers that rule. It writes one `voenix.prices`
row by raw SQL, because `prices` is the only table with a foreign key into
`value_added_taxes`, and then asserts both ends: the service answers
`OperationResult.Conflict` and the route answers `409 VAT is in use`. After the
price row is gone, the same delete succeeds.

## PostgreSQL and Flyway

[`V2__create_value_added_taxes.sql`](../../../backend/modules/platform/resources/db/migration/V2__create_value_added_taxes.sql)
creates `value_added_taxes` with:

- the existing column names and PostgreSQL types;
- the `pk_value_added_taxes` primary key;
- the `ck_value_added_taxes_percent_range` check;
- the case-sensitive `ux_value_added_taxes_name` unique index; and
- the partial `ux_value_added_taxes_single_default` unique index, which prevents
  two rows from being default.

`VatRepository` opens its everyday transactions through the shared
`Database.read` and `Database.write` helpers from the platform module:
`database.read { … }` for read-only queries such as `list` and `findById`, and
`database.write { … }` for the one default-isolation write, `delete`. Country,
Supplier, and Payment use the same two helpers; see
[Persistence error handling](persistence-error-handling.md).

Create and update need more than that and use the repository's own
`serializableTransaction` helper instead, which stays in this file because the
reason for it is a VAT rule. It configures Exposed's JDBC `suspendTransaction`
with serializable isolation and up to three attempts. Setting `isDefault = true` demotes the previous default
and writes the requested row inside the same transaction. The partial unique
index is the final concurrency-safe guarantee.

Flyway owns schema creation. Exposed never creates or changes production tables
at runtime. V2 creates VAT after the Country schema from V1. On an existing
Kotlin database, Flyway uses `flyway_schema_history` to determine whether V2 is
still pending.

## Tests and verification

The VAT tests are:

| Test | Purpose |
| --- | --- |
| [`VatInputValidationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatInputValidationTest.kt) | complete field-rule matrix and boundaries |
| [`VatServiceIntegrationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatServiceIntegrationTest.kt) | normalization, ordering, defaults, rollback, generic conflicts, direct validation, concurrency, database failures, and the `VatReader` batch lookup |
| [`VatRouteSecurityAndValidationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatRouteSecurityAndValidationTest.kt) | admin/CSRF ordering, rejection before operations, and HTTP result mapping |
| [`VatAdminCrudIntegrationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatAdminCrudIntegrationTest.kt) | complete protected CRUD through Ktor, Exposed, Flyway, and PostgreSQL |
| [`VatDeleteInUseIntegrationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatDeleteInUseIntegrationTest.kt) | a referenced VAT entry stays undeletable, as a service `Conflict` and as `409 VAT is in use` over HTTP |

Run the final backend gate from `backend/`:

```sh
./kotlin do ktfmt
./kotlin check
```
