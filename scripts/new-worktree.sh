#!/usr/bin/env bash

set -euo pipefail

# Creates a git worktree with a new branch off main and copies the
# unversioned config files a working checkout needs (Claude sandbox
# settings, secrets, backend local config). The current checkout stays untouched.

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_directory="$(cd -- "$script_directory/.." && pwd -P)"

# Unversioned files to copy into the new worktree. Extend this list when a
# new gitignored file becomes part of the required local setup.
unversioned_files=(
    ".claude/settings.local.json"
    ".secrets"
    "backend/application-local.yaml"
)

if [[ $# -lt 1 || $# -gt 2 ]]; then
    printf 'Usage: %s <branch-name> [worktree-path]\n' "$0" >&2
    printf 'Creates the worktree under ../%s-worktrees/ when no path is given.\n' "$(basename "$project_directory")" >&2
    exit 1
fi

branch="$1"
# Branch names may contain slashes; flatten them for the directory name.
default_path="$(dirname "$project_directory")/$(basename "$project_directory")-worktrees/${branch//\//-}"
worktree_path="${2:-$default_path}"

git -C "$project_directory" worktree add -b "$branch" "$worktree_path" main

for relative_path in "${unversioned_files[@]}"; do
    source_path="$project_directory/$relative_path"
    target_path="$worktree_path/$relative_path"
    if [[ -e "$source_path" ]]; then
        mkdir -p "$(dirname "$target_path")"
        cp -R "$source_path" "$target_path"
        printf 'Copied: %s\n' "$relative_path"
    else
        printf 'Skipped (not present here): %s\n' "$relative_path"
    fi
done

printf 'Worktree ready: %s\n' "$worktree_path"
