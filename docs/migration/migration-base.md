# Module migration base

This file is the reusable base for migrations from a .NET source feature to a
Kotlin backend module. Do not fill it in directly. Before starting a migration,
copy it to `docs/migration/<module>-migration.md`, then replace every value in
angle brackets in that module-specific copy. Never edit this base for an
individual migration.

The workflow lives in the `migrate-dotnet-feature` skill
(`.agents/skills/migrate-dotnet-feature/SKILL.md`); the migration rules live in
[`module-migration-guide.md`](module-migration-guide.md). Do not copy content
from either into the module copy. Record only module-specific facts, decisions,
deviations, and history. Explicit decisions recorded in the module copy
override defaults in the guide.

## Status

`<analysis | awaiting-approval | implementation | complete>`

Keep this value current whenever the migration changes phase so that a later
session can continue from the correct phase.

## Task parameters

Target module:

`<Module>`

Source feature:

`<path to the .NET feature inside the legacy repository, normally checked out next to this one at ../voenix-shop>`

Target package:

`<repo-relative path to the Kotlin package, for example backend/modules/<module>/src/shop/voenix/<module>>`

Analysis checkpoint:

`<continue-automatically | wait-for-approval>`

Known consumers:

- `<client, module, integration, or none known>`

Approved deviations from current behavior:

- `<approved deviation or none>`

Explicitly deferred work:

- `<follow-up with owner or none>`

## Analysis deliverable

Before implementation, record these analysis artifacts in this file:

1. the behavior-evidence-classification-verification matrix;
2. the operation contract table;
3. material ambiguities and proposed deviations;
4. the Kotlin operation interface and production type map;
5. the runtime composition design: `XModule`, `createXModule`,
   `installXModule`, their visibility, and exported capabilities;
6. application-composition and Flyway changes;
7. the test plan; and
8. deferred work and its owner.

Every required behavior must have a planned verification. Use the table
formats from the guide and record only the module-specific findings and
design.

If `Analysis checkpoint` is `wait-for-approval`, stop after sharing this
analysis. If it is `continue-automatically`, continue unless a stop condition
from the guide applies.

## Decision log

Record every checkpoint result, approval, and material decision with its date.

### `<YYYY-MM-DD>` — `<checkpoint, approval, or decision>`

`<what was decided, by whom, and why>`

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| `<behavior or none>` | `<source, test, client, or decision>` | `<Kotlin behavior>` | `<classification>` | `<approval or owner>` | `<follow-up>` |

## Migration retrospective

Before reporting completion, compare the original analysis and design with the
final implementation, tests, documentation, and simplification changes. Record
late discoveries, avoidable rework, repeated manual effort, and missing checks
that could improve a future migration.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| `<finding or no reusable finding>` | `<test, review, diff, or decision>` | `<scope>` | `<earlier signal or check>` | `<destination and status>` |

Use the scopes and promotion rules from `module-migration-guide.md`. Keep
module-specific findings in this record or the appropriate post-migration
file. Improve the skill, base, guide, `AGENTS.md`, or a mechanical check only
when the finding meets the guide's evidence threshold.

Do not invent an improvement merely to fill the table. Record `No reusable
process finding` when the review finds none. Keep semantic rule changes pending
until Joe approves them.
