# The technology stack and why it was chosen

The TypeScript backend in [`backend-ts/`](../../../../backend-ts) runs on Bun.
The choice was made on 2026-10-15, when Joe decided that from now on AI agents
write 100 % of the code. From that day on a language and its tooling are
judged by **agent-friendliness**: a fast feedback loop, hard checks at every
boundary, a homogeneous ecosystem with the frontend, and readable diffs. The
[Kotlin technology-stack guide](../../backend/conventions/technology-stack.md)
asked for "a reason the comparison below did not already weigh"; this is that
reason. The decision is settled (issue #262): a proposal that swaps one of the
pieces below needs a reason this guide did not weigh.

## What the backend is built from

| Concern | Choice |
| --- | --- |
| Runtime, package manager, test runner | Bun. The code uses Node-compatible APIs only (`node:crypto`, `node:fs`, `fetch`); the only Bun-specific calls are the server start (`Bun.serve` through Hono's adapter) and `bun test`. |
| HTTP | [Hono](https://hono.dev) with `@hono/zod-validator`. One Hono sub-app per module, mounted by the composition root. |
| Validation and types | [Zod](https://zod.dev) 4 schemas in each module's `routes.ts`; request types are inferred from the schemas. |
| Database | [Kysely](https://kysely.dev) with the `pg` driver and one `pg.Pool`. Each module declares the table interfaces it owns. No ORM, no DDL from code. |
| Migrations | The SQL files in `backend-ts/migrations/`, applied by a small runner in platform (ordered by version, each file in one transaction, recorded in `schema_migrations`). |
| Money | Integer cents as `number`; `numeric` columns are read as strings and parsed once in the owning repository; one rounding helper in platform. No decimal library. |
| Results | `OperationResult<T>`, a discriminated union in platform: `ok`, `notFound`, `conflict`, `invalid`. Exceptions propagate to the app's `onError`, which answers `500`. |
| Auth | Platform middleware: AES-GCM sealed cookies (`voenix.auth`, `XSRF-TOKEN`, `voenix.guest`) with keys derived from `AUTH_SESSION_SECRET`; the same roles, lifetime, and CSRF rules as before. |
| Configuration | Environment variables, parsed once by a Zod schema into a `Settings` object. `.env.development` (checked in) then `.env.local` (ignored), real variables win. |
| Images | sharp. |
| PDF | pdf-lib with `@pdf-lib/fontkit`; tests inspect PDFs with pdf-lib and `pdfjs-dist`. |
| SFTP | `ssh2`. |
| HTTP clients | `fetch`; tests pass a fetch function in instead of mocking globals. |
| Logging | pino, written as human-readable lines through `pino-pretty` (as an in-process stream, not a worker-thread transport). |
| Lint and format | ESLint with `typescript-eslint` `strict-type-checked`, Prettier (the frontend's configuration), `eslint-plugin-boundaries` for the module graph. |
| Tests | `bun test`. One PostgreSQL Testcontainer per run, migrated once into a template database, one fresh copy per test file. HTTP tests call `app.request()` without a socket. |

## Why TypeScript and not Kotlin

The Kotlin backend was a good backend; it lost on the four criteria above.

- **Fast feedback.** A Kotlin change costs a compile before the first test
  runs; the whole backend suite takes minutes. Here `bun test src/modules/country`
  answers in seconds, the PostgreSQL container included, and `bun run check`
  (types, lint, format, every test) runs in well under a minute. An agent that
  iterates on a failing test gets ten times as many attempts per hour.
- **Hard checks at the boundaries.** TypeScript's `strict` plus
  `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`, a Zod schema on
  every request body, a Kysely table type on every query, and a lint rule for
  every import across a module boundary: a wrong shape is a compile error or a
  lint error, not a runtime surprise. Kotlin had the compiler but needed Ktor
  plugins, Exposed DSLs, and three linters to reach the same coverage.
- **One ecosystem with the frontend.** The Vue frontend already runs on Bun,
  TypeScript ~5.9, ESLint 9+, and Prettier 3.6. One language, one formatter,
  one package manager, and one set of conventions for the whole repository
  means every agent prompt, every review, and every guide applies to both halves.
- **Readable diffs.** Factory functions and plain objects instead of classes,
  interfaces, and annotations; a module is five files whose names say what they
  hold. A reviewer, human or model, reads a change without a class hierarchy in
  mind.

## The alternatives that were rejected

- **Node.js** as the runtime: Bun bundles the runtime, the package manager, and
  the test runner, runs TypeScript without a build step, and starts faster. The
  code stays on Node-compatible APIs, so moving back would cost the two
  Bun-specific calls and nothing else.
- **Express or Fastify** for HTTP: Hono is built on the Web-standard `Request`
  and `Response`, which is what lets a test call `app.request()` without a
  socket, and its validator middleware types the parsed body for the handler.
- **Prisma or Drizzle** for the database: both want to own the schema. The
  schema is owned by reviewed SQL migrations, so the database layer only needs a
  typed query builder, which is exactly what Kysely is.
- **A decimal library** for money: every amount is already stored as integer
  cents. The two `numeric` columns are parsed once, and one rounding helper
  reproduces `HALF_UP`.
- **Vitest** for tests: `bun test` is in the box, runs the same `describe`/`test`
  API, and needs no extra runner process.
- **A module-by-module migration** with two live backends: the shop is not in
  production, so a big bang into a new folder with the Kotlin backend left
  untouched until Joe removes it was simpler than a compatibility layer.

## What follows from it

- The SQL migrations own the schema. Kysely never creates or alters a table.
- Every persistence test runs against a real PostgreSQL in Testcontainers.
- Every request body is validated by a Zod schema at the route, once.
- A module's `index.ts` is its only importable file; the lint gate enforces
  the dependency graph of [Module architecture](module-architecture.md).
- A write to an external system goes through a function the application owns.
