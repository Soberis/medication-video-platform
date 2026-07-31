#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=local-common.sh
source "$SCRIPT_DIR/local-common.sh"

start_worker=false
infrastructure_only=false
api_startup_timeout_seconds=180

usage() {
    cat <<'EOF'
Usage: ./scripts/start-local.sh [options]

Options:
  --worker               Start Media Worker and Export Worker after Core API.
  --infrastructure-only Start only PostgreSQL, RabbitMQ, and S3.
  --api-timeout SECONDS Core API health timeout, from 30 to 600 (default: 180).
  -h, --help             Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --worker)
            start_worker=true
            shift
            ;;
        --infrastructure-only)
            infrastructure_only=true
            shift
            ;;
        --api-timeout)
            [[ $# -ge 2 ]] || { echo "--api-timeout requires a value." >&2; exit 2; }
            api_startup_timeout_seconds="$2"
            shift 2
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

if [[ ! "$api_startup_timeout_seconds" =~ ^[0-9]+$ ]] ||
   ((api_startup_timeout_seconds < 30 || api_startup_timeout_seconds > 600)); then
    echo "--api-timeout must be an integer from 30 to 600." >&2
    exit 2
fi

for command_name in docker nc curl; do
    require_command "$command_name" "Full local startup requires $command_name." || exit 1
done

load_local_environment
build_compose_environment_arguments
cd "$PROJECT_ROOT"

if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    echo "Docker Desktop is not running or unreachable. Start Docker Desktop, then run this script again." >&2
    exit 1
fi

docker compose "${COMPOSE_ENVIRONMENT_ARGUMENTS[@]}" -f "$COMPOSE_FILE" up -d --wait

rabbitmq_port="${RABBITMQ_PORT:-5672}"
if ! tcp_port_in_use "$rabbitmq_port"; then
    echo "RabbitMQ is healthy in Docker but AMQP port $rabbitmq_port is not published to the host." >&2
    exit 1
fi

mkdir -p "$LOG_DIRECTORY"
api_port="${PLATFORM_API_PORT:-8080}"
api_health_uri="http://127.0.0.1:$api_port/actuator/health"
api_log_path="$LOG_DIRECTORY/api.log"

if $infrastructure_only; then
    echo "Infrastructure is ready. Run ./scripts/status-local.sh to inspect it."
    exit 0
fi

require_command java "Core API startup requires Java 21." || exit 1

wait_for_core_api() {
    local deadline=$((SECONDS + api_startup_timeout_seconds))
    echo "Waiting for Core API health (timeout: ${api_startup_timeout_seconds}s)..."
    while ((SECONDS < deadline)); do
        if core_api_healthy "$api_health_uri"; then
            echo "Core API is healthy: $api_health_uri"
            return 0
        fi
        if managed_process_pid api >/dev/null 2>&1 && ! managed_process_running api; then
            echo "Core API process exited before becoming healthy. Inspect: $api_log_path and $api_log_path.error.log" >&2
            return 1
        fi
        sleep 1
    done
    echo "Core API did not become healthy within ${api_startup_timeout_seconds}s. Inspect: $api_log_path" >&2
    return 1
}

if tcp_port_in_use "$api_port"; then
    if ! core_api_healthy "$api_health_uri"; then
        echo "Port $api_port is occupied, but Core API health is not UP: $api_health_uri" >&2
        exit 1
    fi
    echo "Core API is already healthy: $api_health_uri"
elif managed_process_running api; then
    echo "Core API is already starting (PID $(managed_process_pid api))."
    wait_for_core_api
else
    start_managed_process \
        api "Core API" "$api_log_path" \
        "$PROJECT_ROOT/services/platform-api/mvnw" \
        -f "$PROJECT_ROOT/services/platform-api/pom.xml" \
        -Dspring-boot.run.profiles=local \
        spring-boot:run
    wait_for_core_api
fi

if $start_worker; then
    for command_name in uv ffmpeg ffprobe; do
        require_command "$command_name" \
            "Worker startup requires $command_name for media processing." || exit 1
    done

    if managed_process_running worker; then
        echo "Media Worker is already managed by this script (PID $(managed_process_pid worker))."
    else
        start_managed_process \
            worker "Media Worker" "$LOG_DIRECTORY/worker.log" \
            uv run --directory "$PROJECT_ROOT/workers/media-worker" media-worker
    fi

    if managed_process_running export-worker; then
        echo "Export Worker is already managed by this script (PID $(managed_process_pid export-worker))."
    else
        start_managed_process \
            export-worker "Export Worker" "$LOG_DIRECTORY/export-worker.log" \
            uv run --directory "$PROJECT_ROOT/workers/export-worker" export-worker
    fi
fi

echo
echo "Infrastructure: PostgreSQL localhost:${POSTGRES_PORT:-15432} | S3 http://localhost:${S3_PORT:-9000} | RabbitMQ http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}"
echo "Core API: $api_health_uri"
echo "Start Web separately: pnpm run web:dev"
if ! $start_worker; then
    echo "Start Workers when needed: ./scripts/start-local.sh --worker"
fi
echo "Follow API log: tail -f '$api_log_path'"
echo "Status: ./scripts/status-local.sh | Stop: ./scripts/stop-local.sh"
