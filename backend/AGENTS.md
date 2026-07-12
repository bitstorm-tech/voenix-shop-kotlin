## Backend Rules

- Use the Kotlin Toolchain for backend commands. Do not use Gradle or Maven for backend compile, build, run, or test tasks.
- Keep exactly one top-level Kotlin type declaration per file. This includes classes, data classes, objects, enums, interfaces, sealed types, and type aliases. Name the file after that type.
- Kotlin Toolchain `*Plugin.kt` action files are the narrow exception: `plugin.yaml` requires addressable top-level `@TaskAction` functions, so these files contain functions and no top-level type.

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
