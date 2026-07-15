---
name: migrate-dotnet-feature
description: Migrate a backend feature from the legacy Voenix .NET/C# application to the Kotlin/Ktor backend by analyzing source behavior, maintaining the feature migration record, designing an idiomatic vertical slice, implementing it, testing it, documenting it, simplifying it, and turning reusable findings into controlled workflow improvements. Use for analyzing, planning, implementing, continuing, reviewing, or learning from a .NET-to-Kotlin backend feature migration in this repository. Do not use for standalone Flyway work, generic Kotlin refactoring, or frontend-only migrations.
---

# Migrate a .NET feature to Kotlin

Treat this skill as the workflow layer. Treat
`docs/migration/module-migration-guide.md` as the canonical source of migration
rules and `docs/migration/<feature>-migration.md` as the durable record of one
feature migration. Do not duplicate general guide content in the feature record
or this skill.

## Establish the migration state

1. Work from the repository root and inspect `git status --short` before making
   changes. Preserve unrelated user changes.
2. Read every applicable `AGENTS.md`, including `backend/AGENTS.md` and any file
   nearer to the target package.
3. Read `docs/migration/module-migration-guide.md` completely before analyzing
   or changing production code.
4. Identify the requested feature, source project, source package, target
   package, known consumers, approved deviations, deferred work, and analysis
   checkpoint.
5. Check for `docs/migration/<feature>-migration.md`:
   - If it exists, read it completely together with every linked post-migration
     file. Preserve its decisions and history; continue from its current phase.
   - If it does not exist, copy `docs/migration/migration-base.md` to that path
     and replace every angle-bracket placeholder with feature-specific facts.
     Never edit the base for one migration.
6. Determine whether the task is new analysis, analysis revision, approved
   implementation, implementation continuation, or completion review. Do not
   repeat a completed phase without evidence that it is stale.

If required task parameters cannot be discovered safely, ask only for the
material missing decision. Use repository evidence and reasonable defaults for
minor details.

## Analyze behavior and consumers

1. Inspect the complete source production slice, meaningful source tests,
   schema migrations, documentation, and known consumers. Search for additional
   consumers rather than trusting the initial list.
2. Inspect the target application's shared infrastructure and comparable
   Kotlin modules. Reuse established application seams when their semantics fit.
3. Treat source types and framework behavior as evidence, not as a Kotlin
   architecture template.
4. Maintain the guide's required analysis artifacts in the feature migration
   record:
   - behavior, evidence, classification, Kotlin approach, and verification;
   - operation contract;
   - material ambiguities and proposed deviations;
   - operation interface and production type map;
   - application-composition and Flyway changes;
   - test plan; and
   - deferred work with an owner.
5. Plan a verification for every required behavior. Distinguish required,
   proposed-deviation, incidental, and unclear behavior explicitly.
6. Apply every stop condition from the canonical guide. Stop before
   implementation when a material ambiguity or unapproved observable deviation
   requires Joe's decision.

Honor the feature record's analysis checkpoint. When it requires approval,
present the feature-specific findings and decisions, update the record, and
stop. When approval already exists, record it before implementation.

## Design and implement the vertical slice

1. Design from the external contract and domain behavior inward. Justify every
   representation split, wrapper, feature result, transaction abstraction, and
   production type.
2. Prefer the smallest idiomatic Kotlin slice that preserves required behavior.
   Reuse shared operation, validation, HTTP, authentication, database, and
   persistence-error infrastructure when their semantics match.
3. Keep routes thin, application operations independent of Ktor, and SQL details
   inside persistence. Let PostgreSQL enforce concurrency-safe invariants.
4. Implement only the requested feature and the smallest necessary changes at
   existing composition seams. Do not create placeholder consumer modules or
   speculative compatibility infrastructure.
5. Keep the behavior matrix, deviation log, decisions, and deferred work current
   while implementing. Do not reconstruct them only at the end.
6. Put durable cross-feature follow-up work in an appropriate
   `docs/migration/<feature>-post-migration.md` file and name the owning future
   migration. Do not use code TODOs as the only record of unresolved work.

## Verify and simplify

1. Add focused pure, service, route, and PostgreSQL integration tests according
   to the feature's behavior and the canonical guide. Use Testcontainers when
   PostgreSQL semantics matter.
2. Update the beginner-oriented module documentation under `docs/dev` whenever
   implementation, behavior, or architecture changes.
3. Perform the canonical post-migration simplification review before claiming
   completion. Challenge list wrappers, duplicate representations, feature
   result types, delete results, transaction wrappers, copied shared setup, and
   unused compatibility code.
4. Run the backend quality gate with the Kotlin Toolchain from `backend/` as
   required by `backend/AGENTS.md` and the migration guide. Do not substitute
   Gradle or Maven commands.
5. Re-read the migration completion checklist and compare it with the actual
   code, tests, documentation, and feature record.
6. Do not claim completion when a required verification could not run. Record
   the attempted command, environmental limitation, and remaining uncertainty.

## Learn from the migration

Run the canonical migration retrospective after verification and simplification
but before the final completion report.

1. Compare the original analysis and proposed design with the final code,
   tests, documentation, and feature record.
2. Inspect raw evidence from the work: failures, late discoveries, review
   findings, simplification changes, repeated manual effort, and unresolved
   environmental limitations. Do not manufacture a finding when none exists.
3. Complete the `Migration retrospective` section in the feature migration
   record using `docs/migration/migration-base.md`. Add the section to an older
   feature record when it is missing.
4. Route every finding through `Improve future migrations from findings` in the
   canonical guide. Keep one-off knowledge in the feature record and do not
   create a separate learning log.
5. Apply qualifying low-risk clarifications to the smallest authoritative
   source. Record semantic rule changes as proposals and obtain Joe's approval
   before applying them.
6. When this skill changes, use `$skill-creator`, keep the workflow concise,
   verify `agents/openai.yaml`, and validate the skill. Recheck affected links
   and documentation after any promoted improvement.

Do not create a Git commit unless Joe explicitly requests one.

## Report the result

Lead with the migration outcome or the decision that blocks it. Include only
useful sections from the completion-report structure in
`docs/migration/migration-base.md`. Distinguish preserved behavior, intentional
deviations, incidental source behavior, deferred work, validation performed,
and anything still unverified. State whether the retrospective found reusable
findings and which process improvements were applied or remain pending.
