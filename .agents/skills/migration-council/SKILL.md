---
name: migration-council
description: Run the council workflow for a .NET-to-Kotlin module migration. Binds the general council skill to the migration process - canonical migration rules, the module migration record as the durable plan, and migration-aware briefings. Use when Joe asks to plan, implement, or review a module migration with the council. The general workflow lives in the council skill; the single-agent migration mechanics live in the migrate-dotnet-feature skill.
---

# Migration council

Follow the general workflow in `.agents/skills/council/SKILL.md` with these
migration-specific bindings. Do not duplicate the general workflow or the
canonical migration rules here.

## Bindings

- **Canonical rules**: the `migrate-dotnet-feature` skill and
  `docs/migration/module-migration-guide.md`. Implementation agents are
  instructed to follow both, together with the module record.
- **Durable plan**: `docs/migration/<module>-migration.md`, maintained per
  `docs/migration/migration-base.md`. The decided plan and all council
  decisions are recorded there, not only in GitHub issues.
- **Phase 1 briefing**: establish the migration state per
  `migrate-dotnet-feature` first. The briefing contains the source slice
  paths, target-module and shared-infrastructure pointers, relevant domain
  docs, and the open design questions. Council proposals cover module cut,
  architecture, proposed deviations, risks, and open questions.
- **Phase 2 acceptance check**: the full backend quality gate
  (`./kotlin check`) with the Kotlin Toolchain from `backend/` per
  `backend/AGENTS.md`, run by the orchestrator. Implementer agents run only
  their module-scoped checks, per their agent definition.
- **Phase 3 review briefing**: includes the required behavior matrix and the
  deviation log from the module record.
- **Retrospective**: after verification, run the migration retrospective
  from `migrate-dotnet-feature` and route findings through the canonical
  guide's improvement process.
