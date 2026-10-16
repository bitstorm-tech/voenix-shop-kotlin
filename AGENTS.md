## Development Status and Change Freedom

- The product is in an early development phase and is not yet running in production.
- There is currently no production user data and no requirement for backward compatibility.
- Database schemas, migrations, APIs, and internal models may be changed fundamentally when this leads to a simpler and better long-term solution.
- The local development database may be deleted and rebuilt completely when useful.
- Prefer a clear target architecture over defensive transition solutions, compatibility layers, or complicated migration paths.
- Point out destructive consequences, but do not treat them as an automatic reason against a change.
- This freedom applies to the development environment. Do not modify external systems, shared environments, or irreplaceable data destructively without explicit authorization.

## Acceptance Criteria

- Always keep the developer documentation in `docs/dev` up to date.
- Add new documentation to `docs/dev` where it is useful. `docs/dev/README.md` is the index: it explains the folder layout (`backend-ts/getting-started/`, `backend-ts/conventions/`, `backend-ts/packages/`, `frontend/`, `guides/`, and the retained Kotlin folders) and the section skeleton of a package guide. Put a new guide into the fitting folder and add it to the index.
- Keep the style of the existing documentation, whose target audience is developers new to TypeScript, Hono, and Kysely (and, in the retained Kotlin guides, Kotlin beginners).

## Agent skills

### Issue tracker

Issues and PRDs are tracked as GitHub issues via the `gh` CLI. See `docs/agents/issue-tracker.md`, including what `ready-for-agent` means and when an issue gets a parent.

### Domain language and decisions

- Use the domain terms as defined in the glossary `CONTEXT.md` at the repo root, in code, issues, and docs alike.
- There are no ADRs. The reason for a design and the alternatives it beat are written into the `docs/dev` guide of the package it shapes (for example the numbered decisions in the SPOD fulfillment guide). Read the guides that touch the area you work in; when your proposal contradicts a recorded decision, say so explicitly instead of overriding it silently.

### Council workflow

Substantial tasks are planned with Joe in a normal conversation until the issue set meets the planning bar in `docs/agents/issue-tracker.md` (`ready-for-agent`). Implementation and verification then run autonomously with the repo-local `council` skill (`.claude/skills/council/SKILL.md`): Claude sub-agents implement ticket by ticket; the orchestrator, a Claude reviewer sub-agent, and Codex/GPT as a T3 Code child task review the result, plus two lane reviewers (a simplifier and a security reviewer). The agent definitions live in `.claude/agents/council-*.md`.

## Backend Rules (TypeScript, `backend-ts/`, the active backend)

The backend is `backend-ts/`: TypeScript on Bun with Hono, Zod, and Kysely (`docs/dev/backend-ts/conventions/technology-stack.md`). The Kotlin backend in `backend/` is not developed any more; the rules for it are kept below only until it is removed.

### Commands

Run from `backend-ts/`:

```sh
bun install                    # once per checkout
bun run dev                    # src/main.ts under the watcher (migrates first)
bun run migrate                # only the migrations
bun run format                 # Prettier, writes
bun run typecheck              # tsc --noEmit
bun run lint                   # ESLint with typescript-eslint and the boundaries plugin
bun test src/modules/<name>    # the tests of one module
bun run check                  # the full gate: typecheck, lint, format check, every test
```

The development stack (backend plus Vite frontend) starts with `bun run dev` in the repository root (`bun run dev:ngrok` adds the ngrok tunnel); the catalog seed is `scripts/seed-catalog.mjs` (`docs/dev/backend-ts/getting-started/`).

### Module boundaries

- One module per folder `src/modules/<name>/` with `index.ts`, `routes.ts`, `service.ts`, `repository.ts`, `schema.ts`, and its tests next to the code (`docs/dev/backend-ts/conventions/source-file-organization.md`). Group related declarations in one file; there is no one-type-per-file rule.
- A module imports another module only through that module's `index.ts` and only along an edge of the graph in `docs/dev/backend-ts/conventions/module-architecture.md`; `eslint-plugin-boundaries` fails the gate on anything else. A new edge is added to `eslint.config.ts` and to the guide in the same change, with the reason.
- A module queries only the tables its own `schema.ts` declares and asks the owning module's capability for anything else; the shared `Tables` type cannot enforce this, so the review does.
- Factory functions and plain objects, no classes for services and repositories, no interface with a single implementation. A capability that must join the caller's transaction takes the `Transaction<Tables>` as its first parameter.
- Every request body is validated once, at the route, by a Zod schema (`jsonBody`, `formBody`); services trust their input (`docs/dev/backend-ts/conventions/request-validation.md`).

### Logging

- Never log the message of a parse or decode error raised by an untrusted payload (an external provider's answer, a client body): the message quotes the input it failed on, so the message _is_ the untrusted content. Log the error's class or name and your own context instead. Never log a secret, a token, a provider body, or a URL that may carry one.

### Persistence Error Handling

- Never derive an application result from a database constraint name, index name, or localized error message.
- Database constraints remain the concurrency-safe authority. Repositories may configure `executePostgresWrite` to map SQL state `23505` to a generic conflict result and SQL state `23503` (and `23001` for a delete blocked by `ON DELETE RESTRICT`) to one unambiguous foreign-key result. A write has an unambiguous foreign-key result when only one relationship can fail it: an insert or update with a single reference reports the missing row, a delete protected by `ON DELETE RESTRICT` reports that the row is still in use. Undeclared and other SQL states must be rethrown.
- Do not use a preliminary existence query as the only conflict protection because it races with concurrent writes.
- Integration tests for unique conflicts must cover normal duplicate writes and concurrency.
- Database object names may be used inside persistence and migration code, but request and service results must not expose them.
- The SQL migrations in `backend-ts/migrations/` own the schema; Kysely never creates or alters a table. See `docs/dev/backend-ts/conventions/persistence-error-handling.md`.

### Quality Gates

- Before reporting backend work complete, run `bun run format` and then `bun run check` from `backend-ts/`. The gate passes only when the type check, ESLint, the Prettier check, and every test report no issue.
- `bun test` needs access to the Docker socket: the preload starts one PostgreSQL container through Testcontainers, migrates a template database, and gives every test file a fresh copy. Without a reachable Docker socket the run fails in the preload before any test runs; confirm with `docker ps` (outside the sandbox) that containers start. Run the tests outside a write-restricted shell sandbox.

## Kotlin Backend Rules (`backend/`, retained until removed)

- Use the Kotlin Toolchain for backend commands. Do not use Gradle or Maven for backend compile, build, run, or test tasks.
- Organize source files idiomatically: group closely related declarations in one file, following `docs/dev/backend/conventions/source-file-organization.md`. There is no one-type-per-file rule.
- Kotlin Toolchain `*Plugin.kt` action files contain addressable top-level `@TaskAction` functions and no top-level type, because `plugin.yaml` requires that shape.

### Kotlin Visibility

- Use the narrowest practical visibility: prefer `private`, then `internal`.
- Treat `public` declarations as intentional module APIs. Make a declaration `public` only when it must be accessed from another module or by a framework requiring public visibility.
- Do not add redundant visibility modifiers to members whose containing type already limits their effective visibility.
- When making something `public`, keep the exposed surface small and avoid leaking implementation-specific types across module boundaries.
- Never widen visibility only so a test can reach a declaration. Same-module tests already see `internal`; use the existing internal seams or constructor injection, test owner-module behaviour in the owning module, and across modules use only what production exports (the blessed test-only reader factories are listed in `docs/dev/backend/conventions/module-architecture.md`, "Visibility is never widened only for a test").

### Kotlin Logging

- Never log the message of a deserialization or parsing exception raised by an
  untrusted payload (an external provider's answer, a client body):
  `kotlinx.serialization` decoding messages quote the input they failed on, so
  the message _is_ the untrusted content. Log the exception class and your own
  context instead. Evidence: Generator migration phase-3 finding, fixed in
  `FalImageGenerator.upstream()`.

### Kotlin Persistence Error Handling

The same rules as for `backend-ts` above, with `executePostgresWrite` of the Kotlin platform; see `docs/dev/backend/conventions/persistence-error-handling.md` for the implementation pattern and its trade-off.

### Kotlin Toolchain Examples

Run from `backend/`:

```sh
./kotlin task :app:compileJvm
./kotlin build
./kotlin check
./kotlin test
./kotlin run
```

### Kotlin Quality Gates

- Before the final quality gate, run `./kotlin do ktfmt` from `backend/`.
- Before reporting backend work complete, run `./kotlin check` from `backend/`.
- The gate passes only when all tests, ktfmt, ktlint, and Detekt report no issues.
- `./kotlin check` needs access to the Docker socket, because the integration
  tests start PostgreSQL through Testcontainers. Inside a restricted sandbox the
  test JVMs launch and then wait forever: no container appears, no error is
  logged, and the run simply never finishes. The other symptom is the opposite
  and more misleading: the run finishes fast and reports failed test containers
  with `Could not find a valid Docker environment`, which reads like a real test
  failure. Either way, confirm with `docker ps` that containers are actually
  starting before looking for the cause in the code — and note that `docker ps`
  itself has to run outside the sandbox to answer truthfully.
- A third environmental symptom, seen twice during the Checkout migration: on a
  loaded machine a suite's _first_ run fails several tests with
  `org.postgresql … SocketTimeoutException: Read timed out` while opening
  connections, and an immediate re-run is fully green with no code change.
  Re-run once before hunting the cause in the code.
