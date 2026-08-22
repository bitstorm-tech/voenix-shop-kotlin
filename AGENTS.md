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
- Add new documentation to `docs/dev` where it is useful. `docs/dev/README.md` is the index: it explains the folder layout (`getting-started/`, `backend/conventions/`, `backend/packages/`, `frontend/`, `guides/`) and the section skeleton of a package guide. Put a new guide into the fitting folder and add it to the index.
- Keep the style of the existing documentation, whose target audience is Kotlin beginners.

## Agent skills

### Issue tracker

Issues and PRDs are tracked as GitHub issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The canonical triage labels use their default names. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository. See `docs/agents/domain.md`.

### .NET feature migration

Backend features from the legacy .NET application are migrated with the repo-local `migrate-dotnet-feature` skill. See `.agents/skills/migrate-dotnet-feature/SKILL.md` and `docs/migration/module-migration-guide.md`.

### Council workflow

Substantial tasks are planned and verified by a multi-model council (Claude orchestrator, an Opus sub-agent, and Codex/GPT via `codex exec`) and implemented by Opus sub-agents. The general three-phase workflow is the repo-local `council` skill (`.agents/skills/council/SKILL.md`). Module migrations use its specialization, the `migration-council` skill (`.agents/skills/migration-council/SKILL.md`), which binds the workflow to the migration process.
