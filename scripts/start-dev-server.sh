#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_directory="$(cd -- "$script_directory/.." && pwd -P)"
backend_directory="$project_directory/backend"
env_file="$backend_directory/.env"

if [[ ! -r "$env_file" ]]; then
    printf 'Cannot read the development environment file: %s\n' "$env_file" >&2
    exit 1
fi

# Keep variables supplied by the caller. They should take precedence over the
# development defaults in .env, just as they do when dotenv reads the file.
existing_names=()
existing_values=()
while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*= ]]; then
        name="${BASH_REMATCH[2]}"
        if [[ ${!name+x} ]]; then
            existing_names+=("$name")
            existing_values+=("${!name}")
        fi
    fi
done < "$env_file"

set -a
# shellcheck source=/dev/null
source "$env_file"
set +a

for index in "${!existing_names[@]}"; do
    name="${existing_names[$index]}"
    printf -v "$name" '%s' "${existing_values[$index]}"
    export "$name"
done

cd -- "$backend_directory"
exec ./kotlin run
