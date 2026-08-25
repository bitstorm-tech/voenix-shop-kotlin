---
name: council
description: Orchestrate the three-phase multi-model council workflow for a substantial development task - an independent brainstorming round with an Opus sub-agent and Codex (GPT), implementation delegated to Opus sub-agents, and a verification review by three independent reviewers plus a simplifier. Use when Joe asks to plan, implement, or review a feature, refactoring, or other significant change with the council. For .NET module migrations use the migration-council skill, which binds this workflow to the migration process.
---

# Council workflow

A three-phase workflow for substantial tasks: brainstorm with three models,
implement through delegated sub-agents, verify with three independent
reviews plus a simplifier review. The orchestrator (the main Claude session) runs the workflow,
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
- **Simplifier**: one sub-agent launched as `subagent_type: council-simplifier`
  (`.claude/agents/council-simplifier.md`, `model: claude-opus-5`,
  `effort: high`) in phase 3. Its briefing — least code that satisfies the
  decided plan, report-only — lives in the agent definition, so it binds
  regardless of prompt wording.
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
in brainstorming and verification. The simplifier review is part of both
variants — it is cheap and catches exactly what delegated implementation
tends to produce. This is a per-task judgment call, not a
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
   **Always present this as a published HTML artifact** (the Artifact tool),
   not as terminal text — after every phase-1 synthesis, decisions pending or
   not (decided by Joe, 2026-08-21). The page puts Joe's open decisions
   first, one card per contested point, and every card explains the point
   so Joe understands it without asking back (decided by Joe, 2026-08-25):
   what it is about in plain language, why it is a problem, with a concrete
   shop example (real numbers, real articles), then per option what happens
   in practice, what it costs, and what risk remains; the council positions
   come after that, as evidence, with the recommendation marked. Every card
   also offers a "my alternative" choice with a free-text field, so Joe can
   reject all offered options and write his own (decided by Joe,
   2026-08-25), plus an optional note field; below them the
   consensus, ticket-cut preview, and risks as collapsible sections — each
   consensus section opens with a "what this means for the shop" paragraph
   before the technical detail and also carries an optional objection/note
   field, so Joe can push back on a settled point without it being a formal
   decision. Give it
   a "copy summary" control that composes the selections into one line Joe
   pastes back into the session (artifacts cannot call home; page comments
   are the alternative channel). When nothing is contested, the page simply
   presents the decided plan with an empty decision section stating that.
   The artifact is a presentation aid only — the durable plan still lives in
   the driving GitHub issue (step 6), never on the artifact page.
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
   The diff review also applies the simplicity bar (the rules in
   `.claude/agents/council-simplifier.md`): no abstraction without a second caller, no
   speculative configuration, no defensive branch the plan did not ask for.
   Catching this per ticket is cheaper than unwinding it across the whole
   diff in Phase 3. Close the ticket with a result comment.
5. The orchestrator may implement a ticket itself when delegation would cost
   more than it saves; record that decision in the ticket.
6. When the last ticket has passed its acceptance check, the orchestrator
   commits the phase's work to the working branch, pushes it, and opens a
   GitHub PR against the default branch — automatically, without asking Joe
   first. The PR body says `Closes #<driving issue>` — the exact GitHub
   closing keyword, so the issue closes on merge (`Implements #n` does not
   close it) — and links the implemented tickets.
   The PR is Phase 3's review target and stays open until the phase-3
   verification has run; it is never merged as part of Phase 2.

## Phase 3 — Verification

1. Run three independent reviews of the full change set (branch diff or PR):
   the orchestrator, an Opus agent, and Codex. All three receive the same
   review briefing: the decided plan and its acceptance criteria, the diff
   scope, and the repo's documented standards.
2. In parallel, run one **simplifier review**: a fourth reviewer, launched
   as `council-simplifier`, which receives the same inputs (decided plan,
   acceptance criteria, diff scope, documented standards) and checks that
   the change set is the least code that satisfies the plan. It reports
   findings only; they go through the same consolidation, rebuttal, and
   fix-list steps as everyone else's. (Do not use the built-in `simplify`
   skill for this role — it applies changes directly and would bypass the
   rebuttal round.)
3. Consolidate: dedupe findings across all four reviewers, then give each
   contested finding exactly one rebuttal round before accepting or dropping
   it.
4. Decide the fix list, delegate fixes to `council-opus-implementer` agents,
   and re-verify the fixed findings.
5. Publish the consolidated findings and their outcomes as a PR comment.

## Report

Lead with the phase outcome or the decision that blocks it. Distinguish
council consensus, majority positions with recorded dissent, and points Joe
decided. Never present a delegated result as verified before the
orchestrator's own acceptance check has run.

## Run split: local planning, remote execution

The workflow is split across two machines (decided by Joe, 2026-08-21):

- **Phase 1 runs locally and interactively with Joe.** Brainstorm and
  discuss with the council until **every** open question is decided —
  the phase does not end with deferred decisions, open conflicts, or
  "to be clarified during implementation" items. Codex is a local-machine
  participant (authenticated CLI), so the full three-model round always
  runs here.
- **Phase 1's output is a GitHub issue set that carries every piece of
  information the implementation needs**: the driving issue holds the
  complete decided plan, the sub-tickets carry acceptance criteria,
  affected files, and test expectations. A remote session must be able to
  implement from the issues alone, without asking Joe anything. Label the
  driving issue `ready-for-agent` only when that bar is met — that label
  is the launch trigger for the remote machinery (next section).
- **Phases 2 and 3 run in one autonomous session on a remote machine**,
  launched by `rc issues`. Do not stop between phases; post the phase 2→3
  recovery comment on the driving issue and continue. If the remote machine has no
  authenticated Codex CLI, phase 3 runs with two general reviewers
  (orchestrator and Opus) plus the simplifier, and the findings comment
  records that Codex did not review.
  A genuinely contested point or destructive action still stops and waits
  for Joe — but hitting one means phase 1 failed its exit bar; record the
  gap in the driving issue so the next phase 1 closes it.

## How the remote session is launched

Launching is not this skill's job. Joe's **remote-agents** project
(`~/projects/remote-agents`, `rc issues` on the server) starts one
container session per open issue labeled `ready-for-agent` (sub-issues are
skipped — the driving issue's session handles the whole bundle) and hands
Claude a fixed starting prompt: implement the issue with the council,
phases 2 and 3 autonomously, no re-planning, no questions to Joe.

Consequences for phase 1:

- the issue set is the **only** channel to the remote session — the
  driving issue and its sub-issues must carry everything (plan, ticket
  order via blocked-by chain, special instructions, working-branch hints);
  there is no separate handoff prompt;
- `ready-for-agent` on the driving issue is the launch trigger — apply it
  only when the issue set is complete, and never label sub-tickets with it.

In the remote session: post a phase 2→3 recovery comment on the driving
issue before starting phase 3 (the resume entry point if the run dies);
never merge the PR; never close the driving issue by hand (the PR's
`Closes #<n>` does it on merge); a genuinely contested point is recorded
as a phase-1 gap in an issue comment, then the session stops. Codex is
installed in the remote-agents image; if its login is unavailable anyway,
phase 3 runs with orchestrator + Opus (plus the simplifier) and the
findings comment records Codex's absence. After phase 3, the final PR comment lists the findings,
their outcomes, and any follow-up work the task recorded.
