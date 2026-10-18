## Development Status and Change Freedom

- The product is in an early development phase and is not yet running in production.
- There is currently no production user data and no requirement for backward compatibility.
- Database schemas, migrations, APIs, and internal models may be changed fundamentally when this leads to a simpler and better long-term solution.
- The local development database may be deleted and rebuilt completely when useful.
- Prefer a clear target architecture over defensive transition solutions, compatibility layers, or complicated migration paths.
- Point out destructive consequences, but do not treat them as an automatic reason against a change.
- This freedom applies to the development environment. Do not modify external systems, shared environments, or irreplaceable data destructively without explicit authorization.

## Agent skills

### Issue tracker

Issues and PRDs are tracked as GitHub issues via the `gh` CLI. See `docs/agents/issue-tracker.md`, including what `ready-for-agent` means and when an issue gets a parent.

### Domain language and decisions

- Use the domain terms as defined in the glossary `CONTEXT.md` at the repo root, in code, issues, and docs alike.
- There are no ADRs. The reason for a design and the alternatives it beat are written into the `docs/dev` guide of the package it shapes (for example the numbered decisions in the SPOD fulfillment guide). Read the guides that touch the area you work in; when your proposal contradicts a recorded decision, say so explicitly instead of overriding it silently.

### Council workflow

Substantial tasks are planned with Joe in a normal conversation until the issue set meets the planning bar in `docs/agents/issue-tracker.md` (`ready-for-agent`). Implementation and verification then run autonomously with the repo-local `council` skill (`.claude/skills/council/SKILL.md`): Claude sub-agents implement ticket by ticket; the orchestrator, a Claude reviewer sub-agent, and Codex/GPT as a T3 Code child task review the result, plus two lane reviewers (a simplifier and a security reviewer). The agent definitions live in `.claude/agents/council-*.md`.

## Backend Rules (TypeScript, `backend-ts/`, the active backend)

The backend is `backend-ts/`: TypeScript on Bun with Hono, Zod, and Kysely (`docs/dev/backend-ts/conventions/technology-stack.md`). The Kotlin backend in `backend/` is not developed any more; the rules for it are kept below only until it is removed.
