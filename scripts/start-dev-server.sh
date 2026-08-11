#!/usr/bin/env bash

set -euo pipefail
# Give each background job its own process group so a server and its children stop together.
set -m

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_directory="$(cd -- "$script_directory/.." && pwd -P)"
backend_directory="$project_directory/backend"
frontend_directory="$project_directory/frontend"
env_file="$backend_directory/.env"

with_ngrok=false

usage() {
    printf 'Usage: %s [--with-ngrok]\n' "$(basename "$0")"
}

for argument in "$@"; do
    case "$argument" in
        --with-ngrok|--withNgrok)
            with_ngrok=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 64
            ;;
    esac
done

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

status_directory="$(mktemp -d "${TMPDIR:-/tmp}/voenix-dev.XXXXXX")"
status_file="$status_directory/status"
backend_log_pipe="$status_directory/backend.log"
frontend_log_pipe="$status_directory/frontend.log"
ngrok_log_pipe="$status_directory/ngrok.log"
backend_pid=""
frontend_pid=""
ngrok_pid=""

if [[ -t 1 ]]; then
    blue=$'\033[34m'
    green=$'\033[32m'
    magenta=$'\033[35m'
    reset=$'\033[0m'
else
    blue=""
    green=""
    magenta=""
    reset=""
fi

stop_process_group() {
    local pid="$1"

    if [[ -z "$pid" ]]; then
        return
    fi

    kill -TERM -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
}

force_stop_process_group() {
    local pid="$1"

    if [[ -z "$pid" ]]; then
        return
    fi

    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
}

cleanup() {
    set +m 2>/dev/null || true
    trap - INT TERM EXIT

    stop_process_group "$backend_pid"
    stop_process_group "$frontend_pid"
    stop_process_group "$ngrok_pid"

    sleep 0.5

    force_stop_process_group "$backend_pid"
    force_stop_process_group "$frontend_pid"
    force_stop_process_group "$ngrok_pid"

    wait 2>/dev/null || true
    rm -rf "$status_directory"
}

interrupt() {
    cleanup
    exit 130
}

prefix_output() {
    local prefix="$1"
    local color="$2"

    while IFS= read -r line; do
        printf '%s[%s]%s %s\n' "$color" "$prefix" "$reset" "$line"
    done
}

run_process() {
    local name="$1"
    shift

    "$@" &
    local child_pid=$!
    set +m 2>/dev/null || true

    stop_child() {
        local status="$1"

        trap - INT TERM
        stop_process_group "$child_pid"
        sleep 0.5
        force_stop_process_group "$child_pid"
        wait "$child_pid" 2>/dev/null || true
        { printf '%s:%s\n' "$name" "$status" > "$status_file"; } 2>/dev/null || true
        exit "$status"
    }

    trap 'stop_child 130' INT
    trap 'stop_child 143' TERM

    set +e
    wait "$child_pid"
    local status=$?
    set -e

    { printf '%s:%s\n' "$name" "$status" > "$status_file"; } 2>/dev/null || true
    exit "$status"
}

trap interrupt INT TERM
trap cleanup EXIT

mkfifo "$backend_log_pipe"
mkfifo "$frontend_log_pipe"
if [[ "$with_ngrok" == true ]]; then
    mkfifo "$ngrok_log_pipe"
fi

prefix_output "backend" "$blue" < "$backend_log_pipe" &
prefix_output "frontend" "$green" < "$frontend_log_pipe" &
if [[ "$with_ngrok" == true ]]; then
    prefix_output "ngrok" "$magenta" < "$ngrok_log_pipe" &
fi

(
    cd -- "$backend_directory"
    run_process "backend" ./kotlin run
) > "$backend_log_pipe" 2>&1 &
backend_pid=$!

(
    cd -- "$frontend_directory"
    run_process "frontend" bun run dev
) > "$frontend_log_pipe" 2>&1 &
frontend_pid=$!

if [[ "$with_ngrok" == true ]]; then
    (
        # The tunnel points at the backend so external webhook calls reach the API.
        run_process "ngrok" ngrok http 8080 --log stdout --log-format logfmt
    ) > "$ngrok_log_pipe" 2>&1 &
    ngrok_pid=$!
fi

set +m

while [[ ! -s "$status_file" ]]; do
    sleep 1
done

IFS=: read -r exited_process exit_status < "$status_file"
printf '%s[%s]%s exited with status %s\n' "$reset" "$exited_process" "$reset" "$exit_status"
exit "$exit_status"
