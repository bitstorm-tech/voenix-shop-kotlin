# Shared operation results

The backend uses
[`OperationResult`](../../../../backend/modules/platform/src/shop/voenix/operation/OperationResult.kt)
as the common return type for Country, VAT, Supplier, and Pricing operations. It lives
in `shop.voenix.operation` because it describes the outcome of an operation,
not the details of an HTTP request or database call.

## Result variants

```kotlin
sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data object UnexpectedFailure : OperationResult<Nothing>
    data object NotFound : OperationResult<Nothing>
    data object Conflict : OperationResult<Nothing>
    data class Invalid(val errors: ValidationErrors) : OperationResult<Nothing>
}
```

[`ValidationErrors`](../../../../backend/modules/platform/src/shop/voenix/validation/Validatable.kt)
is a shared type alias for `Map<String, List<String>>`, declared next to the
`Validatable` interface that returns it. The map groups messages by
lower-camel-case field name, and an empty map means that validation found no
errors. The alias gives this recurring shape a domain name without adding a
wrapper object or changing its JSON representation. How a request body is
checked before a service ever sees it, and how those messages are collected, is
described in [Request validation](request-validation.md).

The generic type `T` is the success value. For example,
`OperationResult<Country>` can contain `Success(country)`. A failure uses
`Nothing` because it has no success value. The `out T` declaration allows the
same failure object to be returned from operations with different success
types.

The sealed interface also lets callers use an exhaustive `when`. The compiler
reports an error when a result variant is not handled.

## Responsibilities

The service returns an `OperationResult` without choosing an HTTP status code.
The route maps the result to the module-specific HTTP response:

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

Expected persistence outcomes use module-specific repository results, such as
`CountryWriteResult`, `VatWriteResult`, or `SupplierWriteResult`. The service
maps those internal results to `OperationResult`. Simple delete operations may
return Exposed's affected-row count; the service maps zero rows to `NotFound`
and a deleted row to `Success`. When deletion has another expected outcome,
the repository uses a small typed result. For example, VAT uses
`VatDeleteResult.InUse` for a foreign-key conflict and maps it to `Conflict`.
SQL states and transaction details remain outside operation interfaces and
routes.

## One shared helper for unexpected failures

Every service handles an unexpected persistence failure the same way: log the
exception with its own logger and answer with the operation's failure result
instead of letting the exception reach the route. Coroutine cancellation is not
a failure, so a `CancellationException` is always rethrown.

That rule lives once, in
[`OperationResult.kt`](../../../../backend/modules/platform/src/shop/voenix/operation/OperationResult.kt),
directly below `OperationResult`:

```kotlin
public suspend fun <T> Logger.databaseOperation(
    message: String,
    fallback: T,
    operation: suspend () -> T,
): T
```

The function is an extension on SLF4J's `Logger`, so the log entry keeps the
calling service's logger name. A service uses it like this:

```kotlin
override suspend fun list(): OperationResult<List<Vat>> =
    logger.databaseOperation(
        "Database error while listing VAT entries",
        OperationResult.UnexpectedFailure,
    ) {
        OperationResult.Success(repository.list())
    }
```

The type parameter `T` is the *whole result*, not only the success value, and
the caller passes the fallback. That is what lets the same helper serve
operations that do not answer with an `OperationResult` at all:

```kotlin
// Account answers with its own result type.
logger.databaseOperation("Database error during login", LoginResult.UnexpectedFailure) { … }

// Magic Coins answers a spend attempt with a plain Boolean.
logger.databaseOperation("Magic Coin spend failed for …", false) { … }
```

A `fallback` shaped like `OperationResult` would have forced every module with
its own result type to keep a private copy of the helper. That is exactly the
duplication this function replaced.

Two rules for using it:

- The message is a normal Kotlin string template (`"… entry $id"`), not an SLF4J
  `{}` placeholder, because the helper passes it through unchanged together with
  the exception.
- Wrap only the work whose failure should become a failure *result*. An
  operation that must let the exception surface simply does not call the
  helper. `OrderService` does this for order placement and payment
  confirmation.

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

## One shared mapping from a result to an HTTP answer

Most route files used to carry their own private copies of the same recurring
helpers: a `respondResult` that answered the success value and a
`respondFailure` with the exhaustive `when` over the failure variants. Some
files had both, some only `respondFailure`, some only the id parse below. The
`when` was identical wherever it appeared. Only a handful of message strings
differed.

Those strings now live in a small configuration object, and the `when` lives
once in
[`OperationResultHttpMapping.kt`](../../../../backend/modules/platform/src/shop/voenix/http/OperationResultHttpMapping.kt):

```kotlin
private val VAT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("VAT not found"),
        conflict = ConflictHandling.Respond(ApiError("VAT entry already exists")),
    )
```

The route file declares one such `private val` and passes it to every call:

```kotlin
get { call.respondResult(vats.list(), VAT_RESPONSES) }
```

`respondResult` answers a `Success` and hands every other variant to
`respondFailure`. A route that builds the success answer itself, such as a
`201 Created` with a `Location` header or a `204 No Content` delete, calls
`respondFailure` directly in its `else` branch:

```kotlin
post {
    when (val result = vats.create(call.receive<VatInput>())) {
        is OperationResult.Success -> { /* 201 with a Location header */ }
        else -> call.respondFailure(result, VAT_RESPONSES)
    }
}
```

`UnexpectedFailure` is deliberately *not* configurable. It always answers `500`
with `ApiError("Internal server error")`, for the reason given above: an
unexpected failure must not describe itself.

### Saying that a variant cannot happen

Not every operation can produce every failure. A route file states that in the
mapping instead of inventing a message that no client will ever see:

```kotlin
private val ORDER_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Order not found"),
        conflict = ConflictHandling.Unreachable("Order reads never conflict"),
        invalid = InvalidHandling.Unreachable("Order reads carry no input that could be invalid"),
    )
```

`Unreachable` means "if this ever arrives, it is a bug". The helper calls
`error(reason)`, the exception reaches `installHttpRuntime`'s handler, and the
client sees the generic `500`. The alternative, a made-up `409` message, would
hide the bug behind a plausible-looking answer. `invalid` defaults to
`InvalidHandling.RespondValidationErrors`, so only the unusual case is written
out.

### A different success status

`respondResult` answers `200 OK` by default. A route that answers `201 Created`
with the value in the body passes the status:

```kotlin
call.respondResult(
    subcategories.storeExampleImage(upload.upload),
    ARTICLE_SUBCATEGORY_RESPONSES,
    successStatus = HttpStatusCode.Created,
)
```

### Reading an id from the path

The last duplicated helper was the path-id parse. It is now
`longPathParameterOrRespond`, and each route file keeps a domain-named one-liner
in front of it so the call sites stay readable:

```kotlin
private suspend fun ApplicationCall.vatIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid VAT id"))
```

The parse is exactly `toLongOrNull`. A `0` or a negative number is a valid
`Long`, so it is passed on to the operation, which answers `NotFound` for it
like for any other unknown id. Only a missing, non-numeric, or out-of-range
value is rejected, with the status and body the caller configured, because a
few routes answer a malformed id with `404` rather than `400`.

The `?: return@get` idiom makes the early exit safe. When the helper returns
`null`, it has already written the answer.

```kotlin
get {
    val id = call.vatIdOrRespond() ?: return@get
    call.respondResult(vats.get(id), VAT_RESPONSES)
}
```
