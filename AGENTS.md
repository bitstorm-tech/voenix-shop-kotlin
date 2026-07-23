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
- Add new documentation to `docs/dev` where it is useful.
- Keep the style of the existing documentation, whose target audience is Kotlin beginners.

## Agent skills

### Issue tracker

Issues and PRDs are tracked as GitHub issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The canonical triage labels use their default names. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository. See `docs/agents/domain.md`.
