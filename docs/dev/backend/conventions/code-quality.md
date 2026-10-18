# Code quality

One command is the quality gate of the TypeScript backend. Run it from
[`backend/`](../../../../backend):

```sh
bun run check
```

It runs four checks in order and stops at the first failure:

| Script | Tool | Question it answers |
| --- | --- | --- |
| `bun run typecheck` | `tsc --noEmit` | Do the types hold? |
| `bun run lint` | ESLint with `typescript-eslint` and `eslint-plugin-boundaries` | Is the code safe, and does every import respect the module graph? |
| `bun run format:check` | Prettier | Is every file in the canonical layout? |
| `bun test` | `bun test` | Does every test pass against a real PostgreSQL? |

## Daily workflow

After editing, run the narrowest check that covers the change, then the gate:

```sh
bun run format                 # writes the canonical layout
bun run typecheck
bun test src/modules/country   # the tests of the module you changed
bun run check                  # before reporting the work complete
```

`bun test` needs Docker: the preload starts PostgreSQL through Testcontainers.
Without a reachable Docker socket the run fails in the preload with a Docker
error before any test runs; confirm with `docker ps` that containers start.
One container serves the whole run, so the first test file pays about two
seconds and the rest a few milliseconds each.

## What each tool enforces

**TypeScript** ([`tsconfig.json`](../../../../backend/tsconfig.json)) runs
with `strict`, `noUncheckedIndexedAccess` (an index into an array or a record
is `T | undefined`), `exactOptionalPropertyTypes` (an optional property may be
absent but not explicitly `undefined`), and `verbatimModuleSyntax` (a type is
imported with `import type`). The alias `@/*` points at `src/`, `@test-support/*`
at the test support.

**ESLint** ([`eslint.config.ts`](../../../../backend/eslint.config.ts))
starts from `typescript-eslint`'s `strict-type-checked` preset, which uses the
type information: no floating promise, no `any` leaking through a call, no
unnecessary condition. `no-explicit-any` and `no-non-null-assertion` are
errors. `eslint-plugin-boundaries` checks every import against the module graph
of [Module architecture](module-architecture.md). The config file is TypeScript
and is loaded through `jiti`, like the frontend's.

**Prettier** uses the frontend's configuration verbatim
([`.prettierrc.json`](../../../../backend/.prettierrc.json): no semicolons,
single quotes, 100 columns). It is the only formatting authority; no ESLint
rule formats.

**`bun test`** runs every `*.test.ts` under `src/` and `test-support/`, with
`test-support/preload.ts` first (see `bunfig.toml`).

## Suppression policy

Treat a finding as a request to understand the code, not as an obstacle.

1. Fix the code when the finding describes a real problem.
2. If a rule is systematically unsuitable, change the narrowest setting in
   `eslint.config.ts` and add a comment with the reason.
3. Use an `// eslint-disable-next-line <rule>` only for one intentional
   exception that cannot be expressed more clearly in code, with a comment
   next to it.

Never widen a type to `any`, never add a `!`, and never cast through `unknown`
to make a check green.

## Pinned versions

[`package.json`](../../../../backend/package.json) pins TypeScript to the
`~5.9` line the frontend uses (`typescript-eslint` supports `<6.1`), Prettier
to `3.6.2` exactly like the frontend, and takes the current majors of ESLint,
`typescript-eslint`, and `eslint-plugin-boundaries`. `bun.lock` is committed.
Upgrade one tool at a time and finish with `bun run check`.
