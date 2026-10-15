# Shared operation results

A service answers with
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
`invalid(errors)` build the variants, so a service reads
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

The service returns the result without choosing a status code; the route maps
it:

| Result | HTTP answer |
| --- | --- |
| `ok` | the route's success status and the value as JSON |
| `invalid` | `400` with `message: "Validation failed"` and the field errors |
| `notFound` | `404` with the route's message |
| `conflict` | `409` with the route's message |

Expected persistence outcomes stay small: the repository returns the stored
value, `undefined` for a missing row, the number of affected rows for a delete,
or the string the repository declared for a constraint violation
(`'conflict'`). The service maps those to the result; SQL states never reach a
route.

## One mapping from a result to an HTTP answer

Every route file declares its messages once and passes them to the two
functions of [`respond.ts`](../../../../backend-ts/src/platform/http/respond.ts):

```ts
const COUNTRY_RESPONSES = { notFound: 'Country not found', conflict: 'Country name or code already exists' }

admin.get('/', async (c) => respondResult(c, await countries.listAdmin(), COUNTRY_RESPONSES))
```

`respondResult(c, result, responses, successStatus = 200)` answers an `ok`
with the value and hands every other variant to `respondFailure`. A route that
builds the success answer itself, a `201` with a `Location` header or a `204`
delete, calls `respondFailure` directly:

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
