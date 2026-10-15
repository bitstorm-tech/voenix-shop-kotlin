# Shared operation results

An operation that can fail answers with
[`OperationResult<T>`](../../../../backend-ts/src/platform/operation.ts), a
discriminated union that describes the outcome of an operation without HTTP
types:

```ts
export type OperationResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'notFound' }
  | { kind: 'conflict' }
  | { kind: 'invalid'; errors: ValidationErrors }
```

`ValidationErrors` is `Record<string, string[]>`: messages grouped by
lower-camel-case field name, the same shape the `400 Validation failed` answer
carries. The constructors `ok(value)`, `notFound`, `conflict`, and
`invalid(errors)` build the variants, so a repository reads
`return country === undefined ? notFound : ok(country)`.

## There is no unexpected-failure variant

The Kotlin backend had `UnexpectedFailure` and wrapped every operation in a
logging helper that turned an exception into it. The port drops both: an
exception propagates to the app's `onError` in
[`app.ts`](../../../../backend-ts/src/platform/http/app.ts), which logs it and
answers `500 {"message":"Internal server error"}` without describing it. A
service therefore wraps nothing; a database timeout, a bug, and a lost
connection all travel the same path and reach the client as the same `500`.

## Responsibilities

The operation returns the result without choosing a status code; the route
maps it:

| Result | HTTP answer |
| --- | --- |
| `ok` | the route's success status and the value as JSON |
| `invalid` | `400` with `message: "Validation failed"` and the field errors |
| `notFound` | `404` with the route's message |
| `conflict` | `409` with the route's message |

## The repository answers the result itself

A repository whose write or lookup can fail returns `OperationResult<T>`
directly: `notFound` for a missing row, `conflict` for a unique violation or a
delete a foreign key restricts, `invalid({ countryId: ['Country not found'] })`
for a missing reference. `executePostgresWrite` takes those values as the
answers to the declared SQL states (see
[Persistence error handling](persistence-error-handling.md)), so SQL states
never reach a route.

The reason is issue #251: a repository that answered strings (`'conflict'`,
`'notFound'`, `'inUse'`) made every service translate them 1:1 into the same
`OperationResult` again, a ladder of `if (stored === 'conflict') return
conflict` with nothing to decide. Without the translation, a service that only
forwarded disappears: the route calls the repository, and `index.ts` wires the
repository into the routes (country, promotion, the VAT entries, the prompt
slots and categories). A service stays where it has work of its own: a join
with another module (supplier's countries), a price, an example image that is
checked before and deleted after the write.

The one exception is a module-specific union the service really branches on.
`order/payment/repository.ts` answers `'superseded'` for a status write the
live-payment index refuses, which the payment service turns into its own
confirmation outcome instead of a failure; the mug and SPOD article writes and
the production destinations answer a handful of outcomes (`'priceRequired'`,
`'channelImmutable'`, …) that the service turns into distinct field errors.
Such a union is never a renamed `notFound` or `conflict`.

A read that cannot fail answers the plain value, and its route answers it
without the result: `admin.get('/', async (c) => c.json(await countries.list()))`.

## One mapping from a result to an HTTP answer

Every route file declares its messages once and passes them to the two
functions of [`respond.ts`](../../../../backend-ts/src/platform/http/respond.ts):

```ts
const COUNTRY_RESPONSES = { notFound: 'Country not found', conflict: 'Country name or code already exists' }

admin.get('/:id', countryId, async (c) =>
  respondResult(c, await countries.get(c.req.valid('param').id), COUNTRY_RESPONSES),
)
```

`respondResult(c, result, responses)` answers an `ok` with `200` and the value
and hands every other variant to `respondFailure`. A route that builds the
success answer itself, a `201` (with a `Location` header, or the stored name of
an uploaded image) or a `204` delete, calls `respondFailure` directly:

```ts
admin.post('/', jsonBody(countryInput), async (c) => {
  const result = await countries.create(c.req.valid('json'))
  if (result.kind !== 'ok') return respondFailure(c, result, COUNTRY_RESPONSES)
  c.header('Location', `/api/admin/countries/${String(result.value.id)}`)
  return c.json(result.value, 201)
})
```

`conflict: null` states that the operations behind a route file cannot
conflict. Reaching it is a bug: `respondFailure` throws, and the client sees
the generic `500` instead of a made-up `409`.

## Reading an id from the path

`integerParam(name, invalidMessage)` validates the path parameter as an
integer id and answers `400` with the route's message otherwise:

```ts
const countryId = integerParam('id', 'Invalid country id')

admin.get('/:id', countryId, async (c) =>
  respondResult(c, await countries.get(c.req.valid('param').id), COUNTRY_RESPONSES),
)
```

`0` and a negative number parse and reach the operation, which answers
`notFound` for them like for any unknown id. Hono keeps one validated value
per target, so a route with two ids declares one `zValidator('param', …)` of
its own with both keys.

## Exhaustive switches

`switch (result.kind)` over the union is exhaustive when every case returns;
where a `default` is needed, `assertNever(result)` from `operation.ts` makes a
forgotten variant a compile error.
