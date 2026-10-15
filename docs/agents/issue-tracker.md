# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues. Use the `gh` CLI for all operations; it infers the repo from `git remote -v` when run inside a clone.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc or `--body-file` for multi-line bodies. Issues are written in English.
- **Read an issue**: `gh issue view <number> --comments`.
- **List issues**: `gh issue list --state open --json number,title,labels`.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`
- **Close via PR**: a PR that finishes an issue must say `Closes #<number>`
  (or `Fixes` / `Resolves`) in its body — one line per issue it finishes.
  GitHub then closes the issue automatically when the PR merges into the
  default branch. Wordings like `Implements #<number>` or `Part of #<number>`
  are only context; they do **not** close anything, so the issue silently
  stays open. Use `Implements`/`Part of` deliberately for issues the PR only
  contributes to.

## Labels

- `ready-for-agent` marks a council driving issue whose issue set meets the
  planning bar below. Joe starts the implementation thread himself in T3
  Code (`.agents/skills/council/SKILL.md`); the label launches nothing.
  Never put it on a ticket.
- `needs-triage` marks an issue Joe has not decided on yet.
- `enhancement`, `bug`, `documentation` describe the kind of issue.

## Planning bar for `ready-for-agent`

Planning an issue is a normal conversation with Joe; it needs no skill. The
implementation thread, however, works from the issues alone and must not
ask Joe anything, so the label may only go on when the issue set carries
everything that thread needs:

- The driving issue holds the **complete decided plan**. Every open
  question is decided — no deferred points, no "to be clarified during
  implementation". Special instructions and working-branch hints live here
  too; there is no separate handoff prompt.
- The plan is cut into **tickets**, each the scope of one implementer run.
  A ticket carries **acceptance criteria, affected files, and test
  expectations** and has a defined order. A ticket takes one of two shapes;
  pick one shape per driving issue:
  - a **native sub-issue**, ordered with blocked-by dependencies (both
    conventions below) — the shape for large issue sets or tickets that
    may be reopened independently;
  - an **inline ticket**: a numbered `T1`, `T2`, … entry under a `## Tickets`
    heading in the driving issue, implemented in list order. This is the
    cheaper shape when the plan already lives in one issue and the tickets
    carry no life of their own. The council records each inline ticket's
    result as a comment on the driving issue instead of closing an issue.
- Every acceptance criterion that names another module's route, capability,
  or type is **verified against that module's actual code** before the
  ticket is created. A criterion written from memory (ticket #31 demanded a
  409 from a pricing delete route that does not exist) forces the
  implementer to reinterpret the ticket mid-slice.

When the implementation thread still hits a contested point, it records the
gap as a comment on the driving issue; the next planning round closes it.

## Parent issues and sub-issues

A parent issue exists only for a **council driving issue** and its
implementation tickets when they are sub-issues (for example #238 → its
T1–T6); inline tickets need no parent link. Findings of a review or analysis
become **independent top-level issues**, one per topic, without a common
parent (for example #128–#140, #247–#254); each gets its own tickets later if
it goes through the council.

Whenever an issue is a child of a parent issue, link it as a **native GitHub
sub-issue** — a `Part of #<n>` line in the body is context, not the link.
`gh` has no first-class command; use the REST endpoint with the child's
numeric **database id** (`gh api repos/<owner>/<repo>/issues/<child> --jq .id`,
not the `#number` or `node_id`):
`gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`.
List with `gh api repos/<owner>/<repo>/issues/<parent>/sub_issues`; the
parent's `sub_issues_summary` reports completion.

## Blocking

Sub-issue linking and blocked-by dependencies are orthogonal: the first
models containment, the second ordering — council tickets use both. Add a
blocking edge with GitHub's native issue dependencies:
`gh api --method POST repos/<owner>/<repo>/issues/<blocked>/dependencies/blocked_by -F issue_id=<blocker-db-id>`,
again with the blocker's numeric database id.
