## Backend Rules

- Use the Kotlin Toolchain for backend commands. Do not use Gradle or Maven for backend compile, build, run, or test tasks.
- Keep exactly one top-level Kotlin type declaration per file. This includes classes, data classes, objects, enums, interfaces, sealed types, and type aliases. Name the file after that type.
- Kotlin Toolchain `*Plugin.kt` action files are the narrow exception: `plugin.yaml` requires addressable top-level `@TaskAction` functions, so these files contain functions and no top-level type.

## Persistence Error Handling

- Never derive an application result from a database constraint name, index name, or localized error message.
- Database constraints remain the concurrency-safe authority. Repositories may configure `PostgresWrite` to map SQL state `23505` to a generic conflict result and SQL state `23503` to one unambiguous missing-reference result. Undeclared and other SQL states must be rethrown.
- Do not use a preliminary existence query as the only conflict protection because it races with concurrent writes.
- Integration tests for unique conflicts must cover normal duplicate writes and concurrency.
- Database object names may be used inside persistence and migration code, but request and service results must not expose them.
- See `docs/dev/backend/persistence-error-handling.md` for the implementation pattern and its trade-off.

## Kotlin Toolchain Examples

Run from `backend/`:

```sh
./kotlin task :backend:compileJvm
./kotlin build
./kotlin check
./kotlin test
./kotlin run
```

## Quality Gates

- Before the final quality gate, run `./kotlin do ktfmt` from `backend/`.
- Before reporting backend work complete, run `./kotlin check` from `backend/`.
- The gate passes only when all tests, ktfmt, ktlint, and Detekt report no issues.
