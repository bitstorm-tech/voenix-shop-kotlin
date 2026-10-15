# Request validation

A request body is checked **once**, at the route, by a Zod schema. The service
receives the parsed, typed value and trusts it; there is no second validation
in the service and no `validate()` method on an input type.

## The schema lives in `routes.ts`

```ts
export const countryInput = z.strictObject({
  name: z
    .string({ error: 'Name is required' })
    .trim()
    .min(1, 'Name is required')
    .max(255, 'Name must be at most 255 characters'),
  countryCode: z
    .string({ error: 'Country code is required' })
    .trim()
    .min(1, { error: 'Country code is required', abort: true })
    .length(2, { error: 'Country code must be exactly 2 characters', abort: true })
    .regex(/^[A-Za-z]+$/, 'Country code must contain only letters'),
}) satisfies z.ZodType<CountryInput>
```

Three conventions:

- `z.strictObject` rejects an unknown property. The Kotlin backend ignored
  unknown keys; the port decided for the hard check.
- The `error` of `z.string(…)` is the message for a **missing, `null`, or
  non-string** value, so `{}` answers `name: ["Name is required"]` exactly as
  before. A required field therefore needs no nullable type.
- `abort: true` on a check stops the following checks of that field, so a
  blank code reports "is required" and not "must be exactly 2 characters" as
  well. Without it Zod reports every failing check.

`satisfies z.ZodType<CountryInput>` ties the schema to the input type the
service declares: a schema that produces a different shape is a compile error,
and the dependency still runs from the route to the service only.

## The round trip of a rejected body

`jsonBody(schema)` from
[`respond.ts`](../../../../backend-ts/src/platform/http/respond.ts) is the one
middleware a JSON route installs. It wraps `@hono/zod-validator` and answers:

| Request | Answer |
| --- | --- |
| content type other than `application/json` | `415 {"message":"Unsupported media type"}` |
| a body that is not JSON | `400 {"message":"Malformed request body"}` |
| JSON the schema rejects | `400 {"message":"Validation failed","errors":{…}}` |
| JSON the schema accepts | the handler runs; `c.req.valid('json')` is the typed value |

The `errors` map is built by `validationFailed(error)` from the Zod issues:
one entry per field, keyed like the JSON (`name`, `size.width`,
`mugVariants[0].name`), in the order the issues were found; an unknown
property is reported under its own name with the message `Unknown property`.
Messages accumulate per field, so two rules on one field report two messages.

## Rules the schema cannot check

A rule that needs the database (does this country exist? is this name taken?)
is answered by the service with `invalid(errors)` or `conflict`, in the same
`ValidationErrors` shape. `buildValidationErrors((add) => …)` collects
messages per field and appends instead of overwriting; a single rule writes
the record directly:

```ts
return invalid({ countryId: ['Country not found'] })
```

## What is deliberately not shared

The message texts are part of each module's HTTP contract, and modules word the
same rule differently. There is no platform-wide `requiredText` or email
check; a rule shared *within* a module is a plain Zod schema fragment in that
module's `routes.ts`.

## Tests

The schema is tested once, as a unit test of the Zod schema through
`validationFailed`, with the field names and messages the client sees
(`validation.test.ts` in the country module). Route tests do not repeat the
field cases; the platform test `app.test.ts` pins the `415`, the malformed
body, the unknown property, and the shape of the `400`.
