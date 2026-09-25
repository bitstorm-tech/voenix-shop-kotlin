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

- `ready-for-agent` is the **launch trigger of the remote council run**:
  Joe's `rc issues` starts one autonomous session (council phases 2 and 3)
  per open issue carrying it. Apply it only to a council driving issue whose
  phase 1 is complete, never to a sub-ticket and never to an issue that has
  not been through phase 1. See `.agents/skills/council/SKILL.md`.
- `needs-triage` marks an issue Joe has not decided on yet.
- `enhancement`, `bug`, `documentation` describe the kind of issue.

## Parent issues and sub-issues

A parent issue exists only for a **council driving issue** and its
implementation sub-tickets (for example #238 → its T1–T6). Findings of a
review or analysis become **independent top-level issues**, one per topic,
without a common parent (for example #128–#140, #247–#254); each gets its
own sub-tickets later if it goes through the council.

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
