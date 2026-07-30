---
name: council
description: Orchestrate the three-phase multi-model council workflow for a substantial development task - an independent brainstorming round with an Opus sub-agent and Codex (GPT), implementation delegated to Opus sub-agents, and a three-way verification review. Use when Joe asks to plan, implement, or review a feature, refactoring, or other significant change with the council. For .NET module migrations use the migration-council skill, which binds this workflow to the migration process.
---

# Council workflow

A three-phase workflow for substantial tasks: brainstorm with three models,
implement through delegated sub-agents, verify with three independent
reviews. The orchestrator (the main Claude session) runs the workflow,
delegates work, and owns every synthesis. Joe decides contested points.

Specializations (such as `migration-council`) bind this workflow to a
domain-specific process; they define which canonical rules apply and where
the durable plan lives. Without a specialization, use the defaults below.

## Participants and channels

- **Orchestrator**: the main Claude session. Owns the plan, the ticket cut,
  all syntheses, and the final acceptance of delegated work.
- **Opus reviewer**: sub-agents launched with the Agent tool as
  `subagent_type: council-opus` (defined in `.claude/agents/council-opus.md`
  with `model: claude-opus-5` and `effort: high` — the Agent tool itself has
  no effort parameter, so the agent type carries it). Continue an existing
  agent with SendMessage when a rebuttal round needs its prior context.
- **Opus implementer**: sub-agents launched as
  `subagent_type: council-opus-implementer`
  (`.claude/agents/council-opus-implementer.md`, `model: claude-opus-5`,
  `effort: medium`). Prepared tickets carry the design, so medium effort is
  the implementation default; the orchestrator escalates a genuinely tricky
  ticket to `council-opus` and records that decision in the ticket.
- **Codex (GPT)**:
  `codex exec --sandbox read-only -m gpt-5.6-sol -c model_reasoning_effort="high" "<prompt>"`
  run from the repository root, capturing the answer with
  `--output-last-message <file>`. Continue its session for a rebuttal round
  with `codex exec resume --last "<prompt>"`, repeating the same `--sandbox`,
  `-m`, and `-c` flags (they apply per invocation, not per session). Give it
  every needed pointer as repository file paths; it reads the repo itself.
  Codex only reads; it never implements. Codex writes its own session state
  to `~/.codex`, so the call must run outside a write-restricted shell
  sandbox.

Durable artifacts, not conversation, carry state between phases:

- the decided plan lives in the driving GitHub issue (the PRD issue per
  `docs/agents/issue-tracker.md`), unless a specialization names a
  repository document instead;
- sub-tickets are GitHub issues linked to the driving issue, with native
  blocked-by dependencies for ordering;
- consolidated review findings are posted as a PR comment (or an issue
  comment when no PR exists).

## Scale the process

Run the full council for large or high-risk tasks. For small tasks, propose
the slim variant to Joe at the start: orchestrator plus one second opinion
in brainstorming and verification. This is a per-task judgment call, not a
fixed rule. Tasks too small for any council do not need this skill at all.

## Phase 1 — Brainstorming

1. Establish the task context and prepare one briefing: the goal and its
   acceptance criteria, relevant code and document paths, applicable
   canonical rules, and the open design questions.
2. Form the orchestrator's own preliminary proposal before reading any other
   proposal.
3. Send the identical briefing independently and in parallel to an Opus agent
   and to Codex. Each returns a structured proposal: solution cut,
   architecture, risks, open questions. Neither sees the other's answer.
4. Synthesize the three proposals: list agreements and material conflicts.
   For each material conflict run exactly one targeted rebuttal round
   (SendMessage to the Opus agent; `codex exec resume --last` for Codex).
   Do not simulate an open-ended group discussion.
5. Present the consolidated plan and the unresolved conflicts, each with a
   recommendation, to Joe. Joe decides the contested points.
6. Record the decided plan in the durable plan location and create the
   sub-tickets as GitHub issues, each linked to the driving issue as a
   **native GitHub sub-issue** (see the sub-issue convention in
   `docs/agents/issue-tracker.md`), not merely referenced in the body.
   Every ticket carries acceptance criteria,
   affected files, and test expectations, and is chained with blocked-by
   dependencies. Before a ticket is created, verify every acceptance
   criterion that names another module's route, capability, or type against
   that module's actual surface — a criterion written from memory (ticket #31
   demanded a 409 from a pricing delete route that does not exist) forces the
   implementer to reinterpret the ticket mid-slice.

## Phase 2 — Implementation

1. The orchestrator owns the ticket cut and execution order. The default
   implementer is one `council-opus-implementer` agent per ticket, running
   serially; escalate to `council-opus` only per the participant rules.
2. Each implementation agent receives the ticket number, the decided plan,
   and the canonical rules that apply to the task.
3. Implementation agents verify with module-scoped checks only; the full
   quality gate is deliberately reserved for the orchestrator's acceptance
   run. The rule itself lives in the implementer's agent definition
   (`.claude/agents/council-opus-implementer.md`), so it binds regardless of
   prompt wording.
4. After each ticket the orchestrator runs an acceptance check — diff review
   plus the repository's full quality gate — before the next ticket starts.
   Close the ticket with a result comment.
5. The orchestrator may implement a ticket itself when delegation would cost
   more than it saves; record that decision in the ticket.
6. When the last ticket has passed its acceptance check, the orchestrator
   commits the phase's work to the working branch, pushes it, and opens a
   GitHub PR against the default branch — automatically, without asking Joe
   first. The PR body links the driving issue and the implemented tickets.
   The PR is Phase 3's review target and stays open until the phase-3
   verification has run; it is never merged as part of Phase 2.

## Phase 3 — Verification

1. Run three independent reviews of the full change set (branch diff or PR):
   the orchestrator, an Opus agent, and Codex. All three receive the same
   review briefing: the decided plan and its acceptance criteria, the diff
   scope, and the repo's documented standards.
2. Consolidate: dedupe findings, then give each contested finding exactly one
   rebuttal round before accepting or dropping it.
3. Decide the fix list, delegate fixes to `council-opus-implementer` agents,
   and re-verify the fixed findings.
4. Publish the consolidated findings and their outcomes as a PR comment.

## Report

Lead with the phase outcome or the decision that blocks it. Distinguish
council consensus, majority positions with recorded dissent, and points Joe
decided. Never present a delegated result as verified before the
orchestrator's own acceptance check has run.

## Phase handoff prompt

Each phase runs in a fresh session; the durable artifacts carry the state,
but the next session still needs a precise entry point. End every completed
phase with a ready-to-paste starter prompt for the next one, containing:

- the skill to invoke (this one, or the specialization) and the next phase;
- the task or module, the working branch, and the durable plan location;
- the relevant issue and PR numbers;
- decisions still open for Joe and any special review or implementation
  instructions the finished phase produced;
- what the next phase must NOT do (for example: no `complete` status before
  verification has run).

After the final phase, the handoff prompt is replaced by whatever follow-up
the task recorded (deferred work, post-migration lists).
