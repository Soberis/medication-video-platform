#!/usr/bin/env bash

# Shared helpers for the macOS/Linux local-development scripts.

LOCAL_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$LOCAL_SCRIPT_DIR/.." && pwd)"
EXAMPLE_ENV_FILE="$PROJECT_ROOT/.env.example"
LOCAL_ENV_FILE="$PROJECT_ROOT/.env"
COMPOSE_FILE="$PROJECT_ROOT/infra/docker-compose/compose.yaml"
LOG_DIRECTORY="$PROJECT_ROOT/logs/local"
RUNTIME_FILE="$LOG_DIRECTORY/managed-processes.tsv"

import_environment_file() {
    local path="$1"
    local line key value

    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*$ ]] && continue
        [[ "$line" =~ ^[[:space:]]*# ]] && continue

        if [[ ! "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]]; then
            echo "Invalid local environment line in $path: $line" >&2
            return 1
        fi

        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then
            value="${BASH_REMATCH[1]}"
        fi
        export "$key=$value"
    done < "$path"
}

load_local_environment() {
    import_environment_file "$EXAMPLE_ENV_FILE"
    if [[ -f "$LOCAL_ENV_FILE" ]]; then
        import_environment_file "$LOCAL_ENV_FILE"
    fi
}

build_compose_environment_arguments() {
    COMPOSE_ENVIRONMENT_ARGUMENTS=(--env-file "$EXAMPLE_ENV_FILE")
    if [[ -f "$LOCAL_ENV_FILE" ]]; then
        COMPOSE_ENVIRONMENT_ARGUMENTS+=(--env-file "$LOCAL_ENV_FILE")
    fi
}

tcp_port_in_use() {
    nc -z -w 1 127.0.0.1 "$1" >/dev/null 2>&1
}

core_api_healthy() {
    local health_uri="$1"
    local response

    response="$(curl --silent --show-error --max-time 2 "$health_uri" 2>/dev/null)" || return 1
    [[ "$response" =~ \"status\"[[:space:]]*:[[:space:]]*\"UP\" ]]
}

managed_process_pid() {
    local key="$1"
    [[ -f "$RUNTIME_FILE" ]] || return 1
    awk -F $'\t' -v expected="$key" '$1 == expected { print $2; exit }' "$RUNTIME_FILE"
}

managed_process_running() {
    local pid
    pid="$(managed_process_pid "$1")" || return 1
    [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

remove_managed_process_record() {
    local key="$1"
    local temporary_file

    [[ -f "$RUNTIME_FILE" ]] || return 0
    temporary_file="$(mktemp "$LOG_DIRECTORY/managed-processes.XXXXXX")"
    awk -F $'\t' -v expected="$key" '$1 != expected' "$RUNTIME_FILE" > "$temporary_file"
    if [[ -s "$temporary_file" ]]; then
        mv "$temporary_file" "$RUNTIME_FILE"
    else
        rm -f "$temporary_file" "$RUNTIME_FILE"
    fi
}

stop_process_tree() {
    local pid="$1"
    local description="$2"
    local child

    if ! [[ "$pid" =~ ^[0-9]+$ ]] || ! kill -0 "$pid" 2>/dev/null; then
        echo "$description is not running (PID $pid)."
        return 0
    fi

    if command -v pgrep >/dev/null 2>&1; then
        for child in $(pgrep -P "$pid" 2>/dev/null || true); do
            stop_process_tree "$child" "$description child process"
        done
    fi

    kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill -KILL "$pid" 2>/dev/null || true
    fi
    echo "Stopped $description (PID $pid)."
}

stop_managed_process() {
    local key="$1"
    local description="$2"
    local pid

    pid="$(managed_process_pid "$key")" || return 1
    stop_process_tree "$pid" "$description"
    remove_managed_process_record "$key"
}

record_managed_process() {
    local key="$1"
    local pid="$2"
    local name="$3"
    local log_path="$4"
    local temporary_file

    mkdir -p "$LOG_DIRECTORY"
    temporary_file="$(mktemp "$LOG_DIRECTORY/managed-processes.XXXXXX")"
    if [[ -f "$RUNTIME_FILE" ]]; then
        awk -F $'\t' -v expected="$key" '$1 != expected' "$RUNTIME_FILE" > "$temporary_file"
    fi
    printf '%s\t%s\t%s\t%s\n' "$key" "$pid" "$name" "$log_path" >> "$temporary_file"
    mv "$temporary_file" "$RUNTIME_FILE"
}

start_managed_process() {
    local key="$1"
    local name="$2"
    local log_path="$3"
    shift 3

    mkdir -p "$LOG_DIRECTORY"
    nohup "$@" > "$log_path" 2> "$log_path.error.log" < /dev/null &
    local pid=$!
    record_managed_process "$key" "$pid" "$name" "$log_path"
    echo "Starting $name (PID $pid). Log: $log_path"
}

require_command() {
    local command_name="$1"
    local explanation="$2"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "$explanation" >&2
        return 1
    fi
}
