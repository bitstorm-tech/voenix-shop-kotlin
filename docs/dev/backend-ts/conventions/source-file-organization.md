# Source file organization

This guide explains how the TypeScript backend groups declarations into files.
There is no one-type-per-file rule: a file is a cohesive unit of one concern,
and a reader should be able to open one file and understand that concern
completely.

## The standard module shape

A module `x` under `src/modules/x/` has these files:

| File | Contents |
| --- | --- |
| `index.ts` | The public surface: `createXModule(deps)`, the `XModule` type, and `export type { … }` of the value types other modules exchange with it. Nothing else is importable from outside. |
| `routes.ts` | `xRoutes(service)` (or `xRoutes(repository)` when there is no service) returning the Hono sub-app, the Zod schemas of its request bodies declared next to the handlers, the `FailureResponses` messages, and the id parameter validators. |
| `service.ts` | `createXService(repository, …)`: the use cases that do more than the repository answers (a join with another module, a price, an example image), returning `OperationResult<T>`. A module whose routes only forward to the repository has no service. |
| `repository.ts` | `createXRepository(db)`: the Kysely queries, the `read`/`write` transactions, `executePostgresWrite` with the declared violations, the `OperationResult<T>` of a write or lookup that can fail, and the stored value and input types it produces and consumes. |
| `schema.ts` | The table interfaces the module owns and the `declare module` block that merges them into the platform's `Tables`. |

A concern that grows past this gets a file of its own, named after the concept
(`sync.ts`, `pdf.ts`, `mollie.ts`), never after a kind (`utils.ts`,
`types.ts`, `helpers.ts`). The deciding question is always: *which component
produces or owns this type?* Put the type in that component's file. `Country`
and `CountryInput` live in the country module's `repository.ts` because the
repository produces the one and consumes the other; the module has no service.

## Factory functions, not classes

Every component is a factory function returning a plain object of functions:

```ts
export function createCountryRepository(db: Kysely<Tables>) {
  return {
    get: async (id: number): Promise<OperationResult<Country>> => { … },
    create: (input: CountryInput): Promise<OperationResult<Country>> => { … },
  }
}
export type CountryRepository = ReturnType<typeof createCountryRepository>
```

The closure holds the dependencies, the object is the interface, and
`ReturnType` is the type. A class would add `this`, a constructor, and a
second place to declare each member. An `interface` is written only where a
second implementation exists.

## The platform

`src/platform/` is grouped the same way, by concern rather than by kind:
`settings.ts`, `operation.ts`, `money.ts`, `logging.ts`, `fetch.ts` at the top,
`db/`, `http/`, `auth/`, `ratelimit/` below. Each file is imported by its
path; see [Module architecture](module-architecture.md).

## Tests

A module's tests live next to its code as `*.test.ts`, so
`bun test src/modules/country` runs exactly one module. Prefer few files, one
per concern, each with a `describe` per route or behaviour:

| File | What it states |
| --- | --- |
| `routes.test.ts` | One happy path per route and the module-specific HTTP errors, through `testApp` against the real database |
| `service.test.ts` or `repository.test.ts` | Service and repository behaviour, the conflict and concurrency cases, the exported capabilities |
| `validation.test.ts` | The request schemas: field names and messages, once |

Platform behaviour (401/403, CSRF, unknown properties, malformed ids, the body
limit, the error shapes) is tested once in `src/platform/**/*.test.ts`, not
again per module.

## Naming

Files are lower-case and hyphenated (`postgres-write.ts`,
`client-ip-rate-limiter.ts`); the main export says what the file holds. Types
are `PascalCase`, functions and values `camelCase`, table columns keep their
SQL `snake_case` in the table interface and are aliased to `camelCase` in the
`select`.
