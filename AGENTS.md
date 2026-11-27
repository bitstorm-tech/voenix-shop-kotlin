## Development Status and Change Freedom

- The product is in an early development phase and is not yet running in production.
- There is currently no production user data and no requirement for backward compatibility.
- Database schemas, migrations, APIs, and internal models may be changed fundamentally when this leads to a simpler and better long-term solution.
- The local development database may be deleted and rebuilt completely when useful.
- Prefer a clear target architecture over defensive transition solutions, compatibility layers, or complicated migration paths.
- Point out destructive consequences, but do not treat them as an automatic reason against a change.
- This freedom applies to the development environment. Do not modify external systems, shared environments, or irreplaceable data destructively without explicit authorization.

## Issues and decisions

- Issues live on GitHub; use the `gh` CLI. A PR that finishes an issue says `Closes #<number>` in its body.
- There are no ADRs. The reason for a design and the alternatives it beat are written into the `docs/dev` guide of the package it shapes (for example the numbered decisions in the SPOD fulfillment guide). Read the guides that touch the area you work in; when your proposal contradicts a recorded decision, say so explicitly instead of overriding it silently.

## Planning and the council workflow

By default, clarify open decisions with Joe in the conversation, then implement directly. The decisions go into the PR description and, where they shape a design, into the `docs/dev` guide. No GitHub issue is needed for that.

A driving issue and the council are for changes where a missed defect is expensive: database migrations, money (checkout, prices, payments), security, or a change across many modules — or whenever Joe asks for them. Only then is the plan written into a driving issue that carries what the `council` skill expects as input (`.claude/skills/council/SKILL.md`). Implementation and verification then run autonomously with that skill: Claude sub-agents implement ticket by ticket; the orchestrator, a Claude reviewer sub-agent, and Codex/GPT as a T3 Code child task review the result, plus two lane reviewers (a simplifier and a security reviewer). The agent definitions live in `.claude/agents/council-*.md`.

## Git worktrees

- Implement code changes in a new Git worktree located under `<REPO_ROOT>/.worktrees`.

## Backend

The backend is `backend/`: TypeScript on Bun with Hono, Zod, and Kysely (`docs/dev/backend/conventions/technology-stack.md`). The former Kotlin backend was removed; its last state is the Git tag `kotlin-backend`.
