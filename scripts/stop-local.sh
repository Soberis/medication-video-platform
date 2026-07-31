#!/usr/bin/env bash

set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=local-common.sh
source "$SCRIPT_DIR/local-common.sh"

keep_infrastructure=false
include_listening_apps=false

usage() {
    cat <<'EOF'
Usage: ./scripts/stop-local.sh [options]

Options:
  --keep-infrastructure    Keep PostgreSQL, RabbitMQ, and S3 running.
  --include-listening-apps Also stop unmanaged processes on the configured API/Web ports.
  -h, --help               Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keep-infrastructure)
            keep_infrastructure=true
            shift
            ;;
        --include-listening-apps)
            include_listening_apps=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -f "$RUNTIME_FILE" ]]; then
    while IFS=$'\t' read -r key pid name log_path; do
        [[ -n "$key" ]] || continue
        stop_process_tree "$pid" "$name"
    done < "$RUNTIME_FILE"
    rm -f "$RUNTIME_FILE"
else
    echo "No managed Core API, Web app, or Worker process record exists."
fi

if $include_listening_apps; then
    load_local_environment || exit 1
    if ! command -v lsof >/dev/null 2>&1; then
        echo "lsof is required for --include-listening-apps." >&2
        exit 1
    fi
    for port in "${PLATFORM_API_PORT:-8080}" "${WEB_PORT:-5173}"; do
        for pid in $(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true); do
            stop_process_tree "$pid" "process listening on $port"
        done
    done
fi

if $keep_infrastructure; then
    echo "Infrastructure remains running."
    exit 0
fi

load_local_environment || exit 1
build_compose_environment_arguments
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI is unavailable; local containers were not stopped." >&2
    exit 1
fi

docker compose "${COMPOSE_ENVIRONMENT_ARGUMENTS[@]}" -f "$COMPOSE_FILE" down
compose_exit_code=$?
if [[ $compose_exit_code -ne 0 ]]; then
    echo "Docker Compose did not stop cleanly." >&2
    exit "$compose_exit_code"
fi

echo "Local applications and infrastructure stopped. Volumes were preserved."
