## Backend Rules

- Use the Kotlin Toolchain for backend commands. Do not use Gradle or Maven for backend compile, build, run, or test tasks.
- Keep exactly one top-level Kotlin type declaration per file. This includes classes, data classes, objects, enums, interfaces, sealed types, and type aliases. Name the file after that type.
- Top-level functions and properties may accompany the type they belong to (for example `createXModule` and `installXModule` next to `XModule`, or an extension function next to the type it returns). The rule counts type declarations only.
- Kotlin Toolchain `*Plugin.kt` action files are the narrow exception: `plugin.yaml` requires addressable top-level `@TaskAction` functions, so these files contain functions and no top-level type.
- For migrations from the .NET backend, follow the repo-local `migrate-dotnet-feature` skill in `.agents/skills/migrate-dotnet-feature/SKILL.md`. It follows `docs/migration/module-migration-guide.md` and maintains `docs/migration/<module>-migration.md` as the target module's task and decision record. The skill name refers to the .NET source feature; Kotlin targets are modules.

## Kotlin Visibility

- Use the narrowest practical visibility: prefer `private`, then `internal`.
- Treat `public` declarations as intentional module APIs. Make a declaration `public` only when it must be accessed from another module or by a framework requiring public visibility.
- Do not add redundant visibility modifiers to members whose containing type already limits their effective visibility.
- When making something `public`, keep the exposed surface small and avoid leaking implementation-specific types across module boundaries.

## Persistence Error Handling

- Never derive an application result from a database constraint name, index name, or localized error message.
- Database constraints remain the concurrency-safe authority. Repositories may configure `executePostgresWrite` to map SQL state `23505` to a generic conflict result and SQL state `23503` to one unambiguous foreign-key result. A write has an unambiguous foreign-key result when only one relationship can fail it: an insert or update with a single reference reports the missing row, a delete protected by `ON DELETE RESTRICT` reports that the row is still in use. Undeclared and other SQL states must be rethrown.
- Do not use a preliminary existence query as the only conflict protection because it races with concurrent writes.
- Integration tests for unique conflicts must cover normal duplicate writes and concurrency.
- Database object names may be used inside persistence and migration code, but request and service results must not expose them.
- See `docs/dev/backend/persistence-error-handling.md` for the implementation pattern and its trade-off.

## Kotlin Toolchain Examples

Run from `backend/`:

```sh
./kotlin task :app:compileJvm
./kotlin build
./kotlin check
./kotlin test
./kotlin run
```

## Quality Gates

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
