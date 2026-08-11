#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=local-common.sh
source "$SCRIPT_DIR/local-common.sh"

start_worker=false
start_ai_worker=false
infrastructure_only=false
restart_api=false
restart_export_worker=false
export_worker_synthetic_fail_first_attempt=false
export_worker_synthetic_lease_loss_first_attempt=false
api_startup_timeout_seconds=180

usage() {
    cat <<'EOF'
Usage: ./scripts/start-local.sh [options]

Options:
  --worker                                      Legacy compatibility flag; Workers start by default.
  --ai-worker                                   Start only the AI Worker after Core API.
  --infrastructure-only                         Start only PostgreSQL, RabbitMQ, and S3.
  --restart-api                                 Restart the Core API managed by this script.
  --restart-export-worker                       Restart the Export Worker managed by this script.
  --export-worker-synthetic-fail-first-attempt  Inject one retryable first-attempt export failure.
  --export-worker-synthetic-lease-loss-first-attempt
                                               Stop renewing the first attempt after its package upload.
  --api-timeout SECONDS                         Core API health timeout, from 30 to 600 (default: 180).
  -h, --help                                    Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --worker)
            start_worker=true
            shift
            ;;
        --ai-worker)
            start_ai_worker=true
            shift
            ;;
        --infrastructure-only)
            infrastructure_only=true
            shift
            ;;
        --restart-api)
            restart_api=true
            shift
            ;;
        --restart-export-worker)
            restart_export_worker=true
            shift
            ;;
        --export-worker-synthetic-fail-first-attempt)
            export_worker_synthetic_fail_first_attempt=true
            shift
            ;;
        --export-worker-synthetic-lease-loss-first-attempt)
            export_worker_synthetic_lease_loss_first_attempt=true
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

# Default local startup is detection-ready. The AI Worker is started below when the approved
# AI configuration is enabled, while --infrastructure-only remains the explicit opt-out.
if ! $infrastructure_only; then
    start_worker=true
fi

if $restart_export_worker && ! $start_worker; then
    echo "Restarting the Export Worker requires --worker." >&2
    exit 2
fi
if $export_worker_synthetic_fail_first_attempt && ! $start_worker; then
    echo "Synthetic Export Worker failure injection requires --worker." >&2
    exit 2
fi
if $export_worker_synthetic_lease_loss_first_attempt && ! $start_worker; then
    echo "Synthetic Export Worker lease-loss injection requires --worker." >&2
    exit 2
fi
if $infrastructure_only && { $start_worker || $start_ai_worker || $restart_api || $restart_export_worker || $export_worker_synthetic_fail_first_attempt || $export_worker_synthetic_lease_loss_first_attempt; }; then
    echo "Infrastructure-only startup cannot start or restart applications." >&2
    exit 2
fi
if $export_worker_synthetic_fail_first_attempt && ! $restart_export_worker; then
    echo "Synthetic Export Worker failure injection requires --restart-export-worker." >&2
    exit 2
fi
if $export_worker_synthetic_lease_loss_first_attempt && ! $restart_export_worker; then
    echo "Synthetic Export Worker lease-loss injection requires --restart-export-worker." >&2
    exit 2
fi
if $export_worker_synthetic_fail_first_attempt && $export_worker_synthetic_lease_loss_first_attempt; then
    echo "Synthetic Export Worker failure and lease-loss injections are mutually exclusive." >&2
    exit 2
fi
if $export_worker_synthetic_lease_loss_first_attempt && ! $restart_api; then
    echo "Synthetic Export Worker lease-loss injection requires --restart-api so local lease timings can be shortened." >&2
    exit 2
fi

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

if $export_worker_synthetic_fail_first_attempt && [[ "${DATA_PROFILE:-}" != "synthetic" ]]; then
    echo "Synthetic Export Worker failure injection requires DATA_PROFILE=synthetic." >&2
    exit 2
fi
if $export_worker_synthetic_lease_loss_first_attempt && [[ "${DATA_PROFILE:-}" != "synthetic" ]]; then
    echo "Synthetic Export Worker lease-loss injection requires DATA_PROFILE=synthetic." >&2
    exit 2
fi

export RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE=false
export EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT=false
export RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=false
export EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT=false
if $export_worker_synthetic_fail_first_attempt; then
    export RUN_DATASET_EXPORT_ACCEPTANCE=true
    export RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE=true
    export EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT=true
    echo "Export Worker will inject one synthetic first-attempt failure for recovery acceptance."
fi
if $export_worker_synthetic_lease_loss_first_attempt; then
    export RUN_DATASET_EXPORT_ACCEPTANCE=true
    export RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=true
    export EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT=true
    export DATASET_EXPORT_LEASE_DURATION=PT10S
    export DATASET_EXPORT_HEARTBEAT_INTERVAL=PT2S
    export DATASET_EXPORT_LEASE_GRACE=PT2S
    export DATASET_EXPORT_RECOVERY_POLL_DELAY=PT1S
    echo "Export Worker will inject one synthetic lease loss with short local recovery timings."
fi

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

restart_managed_api() {
    if ! managed_process_pid api >/dev/null 2>&1; then
        if tcp_port_in_use "$api_port"; then
            echo "Core API port $api_port is occupied by an unmanaged process. Use status-local.sh to inspect it; this script will not terminate it." >&2
            return 1
        fi
        return 0
    fi
    stop_managed_process api "managed Core API"
}

restart_managed_export_worker_process() {
    if ! managed_process_pid export-worker >/dev/null 2>&1; then
        if $export_worker_synthetic_fail_first_attempt; then
            echo "Synthetic failure injection requires a running Export Worker managed by this script." >&2
            return 1
        fi
        return 0
    fi
    if $export_worker_synthetic_fail_first_attempt && ! managed_process_running export-worker; then
        echo "Synthetic failure injection refuses a stale Export Worker process record." >&2
        return 1
    fi
    stop_managed_process export-worker "managed Export Worker"
}

if $restart_api; then
    restart_managed_api
fi
if $restart_export_worker; then
    restart_managed_export_worker_process
fi

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
        sleep 1
        if ! managed_process_running worker; then
            echo "Media Worker exited during startup. Inspect: $LOG_DIRECTORY/worker.log and $LOG_DIRECTORY/worker.log.error.log" >&2
            exit 1
        fi
    fi

    if managed_process_running export-worker; then
        echo "Export Worker is already managed by this script (PID $(managed_process_pid export-worker))."
    else
        start_managed_process \
            export-worker "Export Worker" "$LOG_DIRECTORY/export-worker.log" \
            uv run --directory "$PROJECT_ROOT/workers/export-worker" export-worker
        sleep 1
        if ! managed_process_running export-worker; then
            echo "Export Worker exited during startup. Inspect: $LOG_DIRECTORY/export-worker.log and $LOG_DIRECTORY/export-worker.log.error.log" >&2
            exit 1
        fi
    fi

fi

if $start_ai_worker && [[ "${AI_HAND_POSE_ENABLED:-false}" != "true" ]]; then
    echo "AI Worker startup requires AI_HAND_POSE_ENABLED=true." >&2
    exit 1
fi
if $start_ai_worker || { $start_worker && [[ "${AI_HAND_POSE_ENABLED:-false}" == "true" ]]; }; then
    for required_variable in AI_HAND_POSE_MODEL_PATH AI_HAND_POSE_MODEL_SHA256; do
        if [[ -z "${!required_variable:-}" ]]; then
            echo "AI Worker startup requires ${required_variable} when AI_HAND_POSE_ENABLED=true." >&2
            exit 1
        fi
    done
    if [[ -n "${AI_WORKER_BEARER_TOKEN:-}" && -n "${AI_WORKER_BEARER_TOKEN_FILE:-}" ]]; then
        echo "Configure exactly one of AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE." >&2
        exit 1
    fi
    if [[ -z "${AI_WORKER_BEARER_TOKEN:-}" && -z "${AI_WORKER_BEARER_TOKEN_FILE:-}" ]]; then
        echo "AI Worker startup requires AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE when AI_HAND_POSE_ENABLED=true." >&2
        exit 1
    fi
    if [[ -n "${AI_WORKER_BEARER_TOKEN_FILE:-}" ]]; then
        if [[ "${AI_WORKER_BEARER_TOKEN_FILE}" != /* ]]; then
            echo "AI_WORKER_BEARER_TOKEN_FILE must be an absolute path." >&2
            exit 1
        fi
        if [[ ! -f "${AI_WORKER_BEARER_TOKEN_FILE}" ]]; then
            echo "AI_WORKER_BEARER_TOKEN_FILE does not point to a readable token file." >&2
            exit 1
        fi
    fi
    require_command uv "AI Worker startup requires uv." || exit 1
    if managed_process_running ai-worker; then
        echo "AI Worker is already managed by this script (PID $(managed_process_pid ai-worker))."
    else
        start_managed_process \
            ai-worker "AI Worker" "$LOG_DIRECTORY/ai-worker.log" \
            uv run --directory "$PROJECT_ROOT/workers/ai-worker" ai-worker
        sleep 1
        if ! managed_process_running ai-worker; then
            echo "AI Worker exited during startup. Inspect: $LOG_DIRECTORY/ai-worker.log and $LOG_DIRECTORY/ai-worker.log.error.log" >&2
            exit 1
        fi
    fi
fi

echo
echo "Infrastructure: PostgreSQL localhost:${POSTGRES_PORT:-15432} | S3 http://localhost:${S3_PORT:-9000} | RabbitMQ http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}"
echo "Core API: $api_health_uri"
echo "Start Web separately: pnpm run web:dev"
echo "Workers: Media and Export started by default; AI Worker starts when AI_HAND_POSE_ENABLED=true."
echo "Follow API log: tail -f '$api_log_path'"
echo "Status: ./scripts/status-local.sh | Stop: ./scripts/stop-local.sh"
