# Feature migration base

This file is the reusable base for .NET-to-Kotlin feature migrations. Do not
fill it in directly. Before starting a migration, copy it to
`docs/migration/<feature>-migration.md`, then replace every value in angle
brackets in that feature-specific copy.

## Task parameters

Feature:

`<Feature>`

Source project:

`/Users/joe/projects/joto-ai/voenix-shop`

Source feature:

`<absolute path to the .NET feature>`

Target project:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin`

Target package:

`<absolute path to the Kotlin package>`

Analysis checkpoint:

`<continue-automatically | wait-for-approval>`

Known consumers:

- `<client, module, integration, or none known>`

Approved deviations from current behavior:

- `<approved deviation or none>`

Explicitly deferred work:

- `<follow-up with owner or none>`

## Required instructions and sources

Before analyzing or changing code:

1. Read every applicable `AGENTS.md`, including the file nearest to the target
   package.
2. Read
   [`module-migration-guide.md`](module-migration-guide.md)
   completely. It is the canonical migration workflow and the source of the
   project's Kotlin, Ktor, persistence, testing, and completion rules.
3. Inspect the source production code, meaningful source tests, migrations,
   documentation, and known consumers for this feature.
4. Inspect the target application's shared infrastructure and the existing
   modules that demonstrate relevant conventions.

Repository instructions and explicit decisions in this task override defaults
in the guide. Do not copy general rules from the guide into this file. Add only
feature-specific facts, decisions, and deviations.

## Outcome

Migrate the feature's intentional capabilities, business rules, and required
client contract into the Kotlin backend. The source implementation is evidence
about behavior, not a type or architecture template.

Keep the work scoped to this feature and the smallest required changes at
existing application-composition seams. Do not migrate unrelated features or
create placeholder infrastructure for them.

## Analysis deliverable

Before implementation, produce the analysis artifacts required by the migration
guide, including:

1. the behavior-evidence-classification-verification matrix;
2. the operation contract table;
3. material ambiguities and proposed deviations;
4. the Kotlin operation interface and production type map;
5. application-composition and Flyway changes;
6. the test plan; and
7. deferred work and its owner.

Every required behavior must have a planned verification. Do not repeat the
guide's explanations; report only the feature-specific findings and design.

If `Analysis checkpoint` is `wait-for-approval`, stop after sharing this
analysis. If it is `continue-automatically`, continue unless a stop condition
from the guide applies.

## Implementation

Implement the approved design according to the migration guide. Keep the
feature's behavior matrix and deviation log current while working rather than
reconstructing them at the end.

Create or update:

- the feature implementation and Flyway migration;
- focused Kotlin and PostgreSQL integration tests;
- the beginner-oriented feature documentation in `docs/dev`; and
- a file under `docs/migration` when approved deviations or deferred work must
  survive beyond this task.

Do not create a Git commit unless explicitly requested.

## Completion report

After all applicable checks in the migration guide pass, report only the
sections that contain useful information:

- preserved required behavior and its verification;
- incidental source behavior not preserved;
- approved deviations and deferred work;
- final Kotlin type map and shared infrastructure used;
- validation, security, persistence, and transaction decisions;
- tests, Flyway verification, and quality-gate results;
- environmental limitations; and
- unresolved ambiguities.

Do not claim completion when a required verification could not run. State the
attempted command and what remains unverified.
