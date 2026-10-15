---
name: council
description: Implement and verify a GitHub issue that is labeled ready-for-agent - implementation delegated to Claude sub-agents ticket by ticket, then a verification review by three independent reviewers (orchestrator, a Fable reviewer sub-agent, Codex/GPT) plus a simplifier, consolidated into a PR comment. Use when Joe asks to implement an issue with the council. Planning an issue needs no skill; the planning bar lives in docs/agents/issue-tracker.md.
---

# Council workflow

A two-phase workflow that runs autonomously in its own T3 Code thread: implement
a prepared issue set through delegated sub-agents, then verify the result with
three independent reviews plus a simplifier review. The orchestrator (the main
Claude session) runs the workflow, delegates work, and owns every synthesis.

The input is a **driving issue labeled `ready-for-agent`** whose issue set meets
the planning bar in `docs/agents/issue-tracker.md`: the decided plan in the
driving issue and an ordered list of tickets with acceptance criteria,
affected files, and test expectations. A ticket is either a native sub-issue
(ordered by blocked-by dependencies) or an inline `T<n>` entry under
`## Tickets` in the driving issue (ordered by list position); the planning
bar defines both shapes. That planning happens in a normal conversation with
Joe, not in this skill; the issue set is the only channel into this thread.
Do not re-plan it, and do not cut tickets yourself: a driving issue without
tickets fails the planning bar.

## Participants and channels

The agent definitions name model aliases (`fable`, `opus`), not pinned
model ids, so a new model generation needs no change here. Each definition
also sets its effort explicitly, because the model defaults differ.

- **Orchestrator**: the main Claude session. Owns the execution order,
  all syntheses, and the final acceptance of delegated work.
- **Reviewer**: a sub-agent launched with the Agent tool as
  `subagent_type: council-reviewer` (`.claude/agents/council-reviewer.md`,
  `model: fable`, `effort: high`). The strongest model sits where judgment
  matters most: the verification review. Continue an existing agent with
  SendMessage when a rebuttal round needs its prior context.
- **Implementer**: sub-agents launched as `subagent_type: council-implementer`
  (`.claude/agents/council-implementer.md`, `model: opus`,
  `effort: medium`). Prepared tickets carry the design, so medium effort is
  the implementation default; the orchestrator escalates a genuinely tricky
  ticket to `council-reviewer` and records that decision in the ticket.
- **Simplifier**: one sub-agent launched as `subagent_type: council-simplifier`
  (`.claude/agents/council-simplifier.md`, `model: opus`,
  `effort: high`). Its briefing — least code that satisfies the decided
  plan, report-only — lives in the agent definition, so it binds regardless
  of prompt wording.
- **Codex (GPT)**: `gpt-6.1-sol` at reasoning effort `high`, run from the
  repository root. Give it every needed pointer as repository file paths; it
  reads the repo itself. Codex only reads; it never implements. Codex writes
  its own session state to `~/.codex`, so every call must run outside a
  write-restricted shell sandbox. Flags apply per invocation, not per
  session, so repeat them on every call.
  - Review: Codex's built-in review mode, which computes the diff itself:
    `codex exec review --base <default branch> -m gpt-6.1-sol -c model_reasoning_effort="high" -c sandbox_mode="read-only" --output-last-message <file> "<review briefing>"`.
  - Question or rebuttal (also usable ad hoc while planning with Joe, when a
    second opinion from another model family is worth having):
    `codex exec --sandbox read-only -m gpt-6.1-sol -c model_reasoning_effort="high" --output-last-message <file> "<prompt>"`;
    continue the same session with
    `codex exec --sandbox read-only -m gpt-6.1-sol -c model_reasoning_effort="high" --output-last-message <file> resume --last "<prompt>"`
    (flags before `resume`).

Launch the reviewer agent and the Codex call together in the background
(the Agent tool backgrounds by default; run Codex with Bash
`run_in_background`), then write the orchestrator's own review while both
run.

## Implementation

1. The orchestrator owns the execution order, which follows the tickets'
   blocked-by chain (sub-issues) or list order (inline tickets). The default
   implementer is one `council-implementer`
   agent per ticket, running serially; escalate to `council-reviewer` only
   per the participant rules.
2. Each implementation agent receives the ticket (issue number, or the
   driving issue number plus the `T<n>` id and the ticket text for inline
   tickets), the decided plan, and the canonical rules that apply to the task.
3. Implementation agents verify with module-scoped checks only; the full
   quality gate is deliberately reserved for the orchestrator's acceptance
   run. The rule itself lives in the implementer's agent definition
   (`.claude/agents/council-implementer.md`), so it binds regardless of
   prompt wording.
4. After each ticket the orchestrator runs an acceptance check — diff review
   plus the repository's full quality gate — before the next ticket starts.
   The diff review also applies the simplicity bar (the rules in
   `.claude/agents/council-simplifier.md`): no abstraction without a second
   caller, no speculative configuration, no defensive branch the plan did
   not ask for. Catching this per ticket is cheaper than unwinding it across
   the whole diff in verification. Record the result: close a sub-issue
   with a result comment; for an inline ticket, post the result as a comment
   on the driving issue headed with the ticket id.
5. The orchestrator may implement a ticket itself when delegation would cost
   more than it saves; record that decision in the ticket.
6. When the last ticket has passed its acceptance check, the orchestrator
   commits the work to the working branch, pushes it, and opens a GitHub PR
   against the default branch — automatically, without asking Joe first. The
   PR body says `Closes #<driving issue>` — the exact GitHub closing keyword,
   so the issue closes on merge (`Implements #n` does not close it) — and
   links the implemented tickets (sub-issue numbers, or the inline ticket
   ids). Link the PR to the thread with T3 Code's
   `link_pull_request`. The PR is the verification's review target and stays
   open until the verification has run; this workflow never merges it.
7. Post a recovery comment on the driving issue — PR number, what is done,
   that verification starts now. It is the resume entry point if the thread
   dies. Then continue without stopping.

## Verification

1. Run three independent reviews of the full change set: the orchestrator,
   a `council-reviewer` agent, and Codex (review mode). All three receive
   the same review briefing: the decided plan and its acceptance criteria,
   the diff scope, and the repo's documented standards.
2. In parallel, run one **simplifier review**: a fourth reviewer, launched
   as `council-simplifier`, which receives the same inputs and checks that
   the change set is the least code that satisfies the plan. It reports
   findings only; they go through the same consolidation, rebuttal, and
   fix-list steps as everyone else's. (Do not use the built-in `simplify`
   skill for this role — it applies changes directly and would bypass the
   rebuttal round.)
3. Consolidate: dedupe findings across all four reviewers, then give each
   contested finding exactly one rebuttal round before accepting or dropping
   it.
4. Decide the fix list, delegate fixes to `council-implementer` agents,
   and re-verify the fixed findings.
5. Publish the consolidated findings and their outcomes as a PR comment,
   together with any follow-up work the task recorded.
6. Close the comment with a short **retrospective**: at most three concrete
   proposals for this workflow, its agent definitions, or the repository's
   rules, each naming the friction it observed in this run (a ticket that
   had to be reinterpreted, a finding class that kept recurring, a gate that
   cost more than it caught). Proposals are not applied; Joe decides which
   ones change this skill. "No proposals" is a valid result.

If Codex's login fails, verification runs with orchestrator + reviewer agent
(plus the simplifier) and the findings comment records Codex's absence.

## Stopping

The thread works from the issues alone and does not ask Joe anything. A
genuinely contested point or a destructive action outside the plan still
stops and waits for Joe — but hitting one means the issue set failed its
planning bar: record the gap as a comment on the driving issue so the next
planning round closes it. Never close the driving issue by hand; the PR's
`Closes #<n>` does it on merge.

## Report

Lead with the phase outcome or the decision that blocks it. Distinguish
reviewer consensus, majority positions with recorded dissent, and dropped
findings. Never present a delegated result as verified before the
orchestrator's own acceptance check has run.
