#!/usr/bin/env bash

set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=local-common.sh
source "$SCRIPT_DIR/local-common.sh"

load_local_environment || exit 1
build_compose_environment_arguments

show_listener() {
    local name="$1"
    local port="$2"
    if tcp_port_in_use "$port"; then
        echo "$name: listening on $port"
    else
        echo "$name: not listening on $port"
    fi
}

echo "Infrastructure:"
if command -v docker >/dev/null 2>&1; then
    docker compose "${COMPOSE_ENVIRONMENT_ARGUMENTS[@]}" -f "$COMPOSE_FILE" ps || true
else
    echo "Docker CLI is unavailable."
fi

echo
echo "Applications:"
api_port="${PLATFORM_API_PORT:-8080}"
api_health_uri="http://127.0.0.1:$api_port/actuator/health"
if core_api_healthy "$api_health_uri"; then
    echo "Core API: healthy on $api_port"
elif tcp_port_in_use "$api_port"; then
    echo "Core API: listening on $api_port, but health is not UP"
else
    echo "Core API: not listening on $api_port"
fi
show_listener "Web app" "${WEB_PORT:-5173}"

if [[ -f "$RUNTIME_FILE" ]]; then
    echo
    echo "Processes started by start-local.sh:"
    while IFS=$'\t' read -r key pid name log_path; do
        [[ -n "$key" ]] || continue
        if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
            state="running"
        else
            state="stale record"
        fi
        echo "- $name: $state (PID $pid); log: $log_path"
    done < "$RUNTIME_FILE"
else
    echo
    echo "No managed background process record exists."
fi

echo
echo "Logs: $LOG_DIRECTORY"
