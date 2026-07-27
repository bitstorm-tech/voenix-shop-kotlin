---
name: migration-council
description: Orchestrate the multi-model workflow for a .NET-to-Kotlin module migration - an independent brainstorming round with an Opus sub-agent and Codex (GPT), implementation delegated to Opus sub-agents, and a three-way verification review. Use when Joe asks to plan, implement, or review a module migration with the council. Do not use for the single-agent migration mechanics, which live in the migrate-dotnet-feature skill.
---

# Migration council workflow

This skill is the orchestration layer on top of the `migrate-dotnet-feature`
skill. That skill and `docs/migration/module-migration-guide.md` remain the
canonical sources of migration rules; do not duplicate their content here. The
orchestrator (the main session) runs this workflow, delegates work, and owns
every synthesis. Joe decides contested points.

## Participants and channels

- **Orchestrator**: the main Claude session. Owns the plan, the ticket cut,
  all syntheses, and the final acceptance of delegated work.
- **Opus reviewer/implementer**: sub-agents launched with the Agent tool as
  `subagent_type: council-opus` (defined in `.claude/agents/council-opus.md`
  with `model: claude-opus-5` and `effort: high` — the Agent tool itself has
  no effort parameter, so the agent type carries it). Continue an existing agent with
  SendMessage when a rebuttal round needs its prior context.
- **Codex (GPT)**:
  `codex exec --sandbox read-only -m gpt-5.6-sol -c model_reasoning_effort="high" "<prompt>"`
  run from the repository root, capturing the answer with
  `--output-last-message <file>`.
  Continue its session for a rebuttal round with
  `codex exec resume --last "<prompt>"`, repeating the same `--sandbox`,
  `-m`, and `-c` flags (they apply per invocation, not per session). Give it every needed pointer as
  repository file paths; it reads the repo itself. Codex only reads; it never
  implements. Codex writes its own session state to `~/.codex`, so the call
  must run outside a write-restricted shell sandbox.

Durable artifacts, not conversation, carry state between phases:

- the decided plan lives in `docs/migration/<module>-migration.md`;
- sub-tickets are GitHub issues per `docs/agents/issue-tracker.md`, with
  native blocked-by dependencies for ordering;
- consolidated review findings are posted as a PR comment (or an issue
  comment when no PR exists).

## Scale the process

Run the full council for large or high-risk modules. For small modules,
propose the slim variant to Joe at the start: orchestrator plus one second
opinion in brainstorming and verification. This is a per-module judgment
call, not a fixed rule.

## Phase 1 — Brainstorming

1. Establish the migration state per `migrate-dotnet-feature` and prepare one
   briefing: source slice paths, target-module and shared-infrastructure
   pointers, relevant domain docs, and the open design questions.
2. Form the orchestrator's own preliminary proposal before reading any other
   proposal.
3. Send the identical briefing independently and in parallel to an Opus agent
   and to Codex. Each returns a structured proposal: module cut, architecture,
   proposed deviations, risks, open questions. Neither sees the other's
   answer.
4. Synthesize the three proposals: list agreements and material conflicts.
   For each material conflict run exactly one targeted rebuttal round
   (SendMessage to the Opus agent; `codex exec resume --last` for Codex).
   Do not simulate an open-ended group discussion.
5. Present the consolidated plan and the unresolved conflicts, each with a
   recommendation, to Joe. Joe decides the contested points.
6. Record the decided plan in the module migration record and create the
   sub-tickets as GitHub issues. Every ticket carries acceptance criteria,
   affected files, and test expectations, and is chained with blocked-by
   dependencies.

## Phase 2 — Implementation

1. The orchestrator owns the ticket cut and execution order. The default
   implementer is one Opus sub-agent per ticket, running serially.
2. Each implementation agent receives the ticket number and the instruction
   to follow the `migrate-dotnet-feature` skill, the canonical guide, and the
   module record.
3. After each ticket the orchestrator runs an acceptance check — diff review
   plus the backend quality gate per `backend/AGENTS.md` — before the next
   ticket starts. Close the ticket with a result comment.

## Phase 3 — Verification

1. Run three independent reviews of the full change set (branch diff or PR):
   the orchestrator, an Opus agent, and Codex. All three receive the same
   review briefing: the required behavior from the module record, the diff
   scope, and the repo's documented standards.
2. Consolidate: dedupe findings, then give each contested finding exactly one
   rebuttal round before accepting or dropping it.
3. Decide the fix list, delegate fixes to Opus sub-agents, and re-verify the
   fixed findings.
4. Publish the consolidated findings and their outcomes as a PR comment.

## Report

Lead with the phase outcome or the decision that blocks it. Distinguish
council consensus, majority positions with recorded dissent, and points Joe
decided. Never present a delegated result as verified before the
orchestrator's own acceptance check has run.
