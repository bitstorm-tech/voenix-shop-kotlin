# Shared operation results

The backend uses
[`OperationResult`](../../../backend/src/shop/voenix/operation/OperationResult.kt)
as the common return type for Country, Supplier, and VAT operations. It lives
in `shop.voenix.operation` because it describes the outcome of an operation,
not the details of an HTTP request or database call.

## Result variants

```kotlin
sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data object UnexpectedFailure : OperationResult<Nothing>
    data object NotFound : OperationResult<Nothing>
    data object Conflict : OperationResult<Nothing>
    data class Invalid(val errors: Map<String, List<String>>) : OperationResult<Nothing>
}
```

The generic type `T` is the success value. For example,
`OperationResult<Country>` can contain `Success(country)`. A failure uses
`Nothing` because it has no success value. The `out T` declaration allows the
same failure object to be returned from operations with different success
types.

The sealed interface also lets callers use an exhaustive `when`: the compiler
reports an error when a result variant is not handled.

## Responsibilities

The service returns an `OperationResult` without choosing an HTTP status code.
The route maps the result to the feature-specific HTTP response:

| Result | Usual HTTP response |
| --- | --- |
| `Success` | The operation-specific success status and body |
| `Invalid` | `400 Bad Request` with field errors |
| `NotFound` | `404 Not Found` |
| `Conflict` | `409 Conflict` |
| `UnexpectedFailure` | `500 Internal Server Error` without implementation details |

`UnexpectedFailure` deliberately does not name a database. An operation may
fail because of PostgreSQL today and use a different implementation tomorrow.
The service logs the actual exception, while its interface exposes only the
stable operation outcome.

Expected persistence outcomes use feature-specific write results, such as
`CountryWriteResult`, `VatWriteResult`, or `SupplierWriteResult`. The service
maps those internal results to `OperationResult`. Simple delete operations
return Exposed's affected-row count from the repository; the service maps zero
rows to `NotFound` and a deleted row to `Success`. SQL states and transaction
details remain outside operation interfaces and routes.

## Missing references are field errors

Supplier accepts an optional `countryId`. PostgreSQL remains the
concurrency-safe authority for that foreign key. If the submitted country does
not exist, the repository returns `SupplierWriteResult.CountryNotFound`. The
service maps it to:

```kotlin
OperationResult.Invalid(
    mapOf("countryId" to listOf("Country not found")),
)
```

The HTTP response therefore uses the same field-error shape as other validation
failures. A client can display the message next to the `countryId` field instead
of interpreting a Supplier-specific result variant.
