#!/usr/bin/env bash

set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

strict=false
changed_only=false
failures=0
skipped=0
repository_paths_file=""

usage() {
    cat <<'EOF'
Usage: ./scripts/quality-gate.sh [options]

Run the repository quality gates natively on macOS/Linux. PowerShell is not required.

Options:
  --strict, -Strict  Require every active component gate; skipped gates fail the run.
  --changed, -Changed  Check only components touched by the current working tree.
  -h, --help         Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict|-Strict)
            strict=true
            shift
            ;;
        --changed|-Changed)
            changed_only=true
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

if $strict && $changed_only; then
    echo '--strict and --changed cannot be used together. Use --strict before merging.' >&2
    exit 2
fi

add_failure() {
    echo "[FAIL] $1"
    failures=$((failures + 1))
}

add_pass() {
    echo "[PASS] $1"
}

add_skip() {
    echo "[SKIP] $1"
    skipped=$((skipped + 1))
}

changed_files=''
if $changed_only; then
    changed_files="$(
        {
            git -C "$PROJECT_ROOT" -c core.quotepath=false diff --name-only --cached
            git -C "$PROJECT_ROOT" -c core.quotepath=false diff --name-only
            git -C "$PROJECT_ROOT" -c core.quotepath=false ls-files --others --exclude-standard
        } | LC_ALL=C sort -u
    )"
    if [[ -z "$changed_files" ]]; then
        echo 'No uncommitted changes; fast quality gate has nothing to verify.'
        exit 0
    fi
fi

has_changed_path() {
    local prefix="$1"
    local changed_path
    if ! $changed_only; then
        return 0
    fi
    while IFS= read -r changed_path; do
        [[ "$changed_path" == "$prefix" || "$changed_path" == "$prefix/"* ]] && return 0
    done <<< "$changed_files"
    return 1
}

validate_json() {
    local relative_path="$1"
    local success_message="$2"
    local failure_prefix="$3"
    local error_message

    if error_message="$(node -e '
const fs = require("node:fs");
try {
  const content = fs.readFileSync(process.argv[1], "utf8").replace(/^\uFEFF/, "");
  JSON.parse(content);
} catch (error) {
  process.stderr.write(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
' "$PROJECT_ROOT/$relative_path" 2>&1)"; then
        add_pass "$success_message"
    else
        add_failure "$failure_prefix: $error_message"
    fi
}

validate_profile() {
    local relative_path="$1"
    local profile_name="$2"
    local expected_digest="$3"
    local digest_success_message="$4"
    local digest_failure_prefix="$5"
    local digest

    if digest="$(node -e '
const crypto = require("node:crypto");
const fs = require("node:fs");
try {
  const content = fs
    .readFileSync(process.argv[1], "utf8")
    .replace(/^\uFEFF/, "")
    .replace(/[\r\n]+$/, "");
  JSON.parse(content);
  process.stdout.write(crypto.createHash("sha256").update(content, "utf8").digest("hex"));
} catch (error) {
  process.stderr.write(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
' "$PROJECT_ROOT/$relative_path" 2>&1)"; then
        add_pass "$profile_name is valid JSON"
        if [[ "$digest" == "$expected_digest" ]]; then
            add_pass "$digest_success_message"
        else
            add_failure "$digest_failure_prefix: $digest"
        fi
    else
        add_failure "$profile_name is invalid: $digest"
    fi
}

repository_paths_file="$(mktemp "${TMPDIR:-/tmp}/medication-quality-gate.XXXXXX")"
trap '[[ -z "${repository_paths_file:-}" ]] || rm -f "$repository_paths_file"' EXIT

if git -C "$PROJECT_ROOT" -c core.quotepath=false \
    ls-files -z --cached --others --exclude-standard > "$repository_paths_file"; then
    while IFS= read -r -d '' relative_path; do
        case "$relative_path" in
            *.[lL][oO][cC][kK]|*.[jJ][aA][rR]|*.[cC][lL][aA][sS][sS]|*.[dD][oO][cC][xX])
                continue
                ;;
        esac

        full_path="$PROJECT_ROOT/$relative_path"
        [[ -f "$full_path" ]] || continue
        while IFS= read -r match; do
            [[ -n "$match" ]] || continue
            line_number="${match%%:*}"
            add_failure "Hard-coded local Windows path: $relative_path:$line_number"
        done < <(LC_ALL=C grep -nE '(^|[^[:alnum:]_])[A-Za-z]:[\\/]' -- "$full_path" 2>/dev/null || true)
    done < "$repository_paths_file"
else
    add_failure "git ls-files failed while scanning repository paths"
fi

required_files=(
    'AGENTS.md'
    'README.md'
    '.gitignore'
    '.gitattributes'
    '.editorconfig'
    '.env.example'
    '.java-version'
    '.node-version'
    '.python-version'
    'package.json'
    'pnpm-workspace.yaml'
    'contracts/schemas/capture-session-metadata-v1.schema.json'
    'contracts/events/media-probe-job-v1.schema.json'
    'contracts/events/media-probe-result-v1.schema.json'
    'contracts/events/media-dead-letter-v1.schema.json'
    'contracts/events/ai-hand-pose-job-v1.schema.json'
    'contracts/events/ai-hand-pose-result-v2.schema.json'
    'contracts/events/ai-hand-pose-dead-letter-v1.schema.json'
    'contracts/profiles/media-probe-baseline-v1.json'
    'contracts/profiles/p0-post-annotation-cleaning-v1.json'
    'tools/synthetic-upload-client/index.mjs'
    'tools/synthetic-upload-client/upload-client.mjs'
    'tools/synthetic-upload-client/synthetic-video.mjs'
    'tools/synthetic-upload-client/upload-client.test.mjs'
    'tools/synthetic-upload-client/large-upload-acceptance.mjs'
    'tools/synthetic-upload-client/large-upload-acceptance.test.mjs'
    'tools/synthetic-upload-client/README.md'
    'scripts/check-environment.ps1'
    'scripts/check-environment.sh'
    'scripts/quality-gate.ps1'
    'scripts/quality-gate.sh'
    'scripts/local-common.sh'
    'scripts/start-local.ps1'
    'scripts/start-local.sh'
    'scripts/start-local.test.mjs'
    'scripts/status-local.ps1'
    'scripts/status-local.sh'
    'scripts/stop-local.ps1'
    'scripts/stop-local.sh'
    'workers/media-worker/pyproject.toml'
    'workers/media-worker/uv.lock'
    'workers/media-worker/README.md'
    'workers/media-worker/src/medication_media_worker/__main__.py'
    'workers/media-worker/src/medication_media_worker/processor.py'
    'workers/media-worker/src/medication_media_worker/probe.py'
    'tools/golden-capture-g0/pyproject.toml'
    'tools/golden-capture-g0/uv.lock'
    'tools/golden-capture-g0/README.md'
    'tools/golden-capture-g0/fixtures/golden-captures.json'
    'tools/golden-capture-g0/src/golden_capture_g0/experiment.py'
    'docs/requirements/README.md'
    'docs/requirements/requirements-manifest.json'
    'docs/requirements/baselines/V1.21/配药视频数据管理平台需求文档_V1.21.md'
    'docs/requirements/baselines/V1.20/配药视频数据管理平台需求文档_V1.20.md'
    'docs/requirements/baselines/V1.18/配药视频数据管理平台需求文档_V1.18.md'
    'docs/requirements/baselines/V1.13/配药视频数据管理平台需求文档_V1.13.md'
    'docs/architecture/README.md'
    'docs/architecture/配药平台技术设计.md'
    'docs/architecture/requirements-traceability.md'
    'docs/architecture/domain-model-and-data-ownership.md'
    'docs/architecture/state-machines.md'
    'docs/architecture/asynchronous-job-protocol.md'
    'docs/architecture/timebase-and-multicamera-sync.md'
    'docs/architecture/security-privacy-retention.md'
    'docs/architecture/deployment-capacity-and-dr.md'
    'docs/architecture/web-ux-and-frontend-guidelines.md'
    'docs/runbooks/large-upload-resume-acceptance.md'
)

for relative_path in "${required_files[@]}"; do
    if [[ -f "$PROJECT_ROOT/$relative_path" ]]; then
        add_pass "Required file exists: $relative_path"
    else
        add_failure "Required file missing: $relative_path"
    fi
done

validate_json \
    'package.json' \
    'package.json is valid JSON' \
    'package.json is invalid'
validate_json \
    'contracts/schemas/capture-session-metadata-v1.schema.json' \
    'Capture session metadata JSON Schema is valid JSON' \
    'Capture session metadata JSON Schema is invalid'
validate_json \
    'contracts/events/media-probe-job-v1.schema.json' \
    'Media probe job JSON Schema is valid JSON' \
    'Media probe job JSON Schema is invalid'
validate_json \
    'contracts/events/media-probe-result-v1.schema.json' \
    'Media probe result JSON Schema is valid JSON' \
    'Media probe result JSON Schema is invalid'
validate_json \
    'contracts/events/media-dead-letter-v1.schema.json' \
    'Media dead-letter JSON Schema is valid JSON' \
    'Media dead-letter JSON Schema is invalid'

ai_event_contracts=(
    'ai-hand-pose-job-v1.schema.json'
    'ai-hand-pose-result-v2.schema.json'
    'ai-hand-pose-dead-letter-v1.schema.json'
)
for contract_name in "${ai_event_contracts[@]}"; do
    validate_json \
        "contracts/events/$contract_name" \
        "AI hand-pose event JSON Schema is valid JSON: $contract_name" \
        "AI hand-pose event JSON Schema is invalid ($contract_name)"
done

validate_profile \
    'contracts/profiles/media-probe-baseline-v1.json' \
    'Media probe baseline profile' \
    '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765' \
    'Media probe baseline profile digest matches the server contract' \
    'Media probe baseline profile digest changed unexpectedly'
validate_profile \
    'contracts/profiles/p0-post-annotation-cleaning-v1.json' \
    'P0 post-annotation cleaning profile' \
    '7c1aa3606467412b84964a3455af8695bd73c85cd9b5d08ce079061ed840fa0a' \
    'P0 post-annotation cleaning profile digest matches the server contract' \
    'P0 post-annotation cleaning profile digest changed unexpectedly'
latest_matches=0
while IFS= read -r -d '' config_file; do
    while IFS= read -r match; do
        [[ -n "$match" ]] || continue
        line_number="${match%%:*}"
        add_failure "Floating latest reference: $config_file:$line_number"
        latest_matches=$((latest_matches + 1))
    done < <(
        LC_ALL=C grep -nEi \
            "(image[[:space:]]*:[[:space:]]*[^[:space:]]+:latest([^[:alnum:]_]|$)|[\"']latest[\"'])" \
            -- "$config_file" 2>/dev/null || true
    )
done < <(
    find "$PROJECT_ROOT" \
        \( -type d \( \
            -name '.git' -o \
            -name '.pnpm-store' -o \
            -name '.venv' -o \
            -name 'build' -o \
            -name 'dist' -o \
            -name 'node_modules' -o \
            -name 'target' \
        \) -prune \) -o \
        \( -type f \( \
            -iname '*.yml' -o \
            -iname '*.yaml' -o \
            -iname '*.json' -o \
            -iname '*.toml' -o \
            -iname '*.properties' \
        \) -print0 \) 2>/dev/null
)

if ((latest_matches == 0)); then
    add_pass 'No floating latest image or dependency marker found in configuration files'
fi

openapi_contract="$PROJECT_ROOT/contracts/openapi/openapi.yaml"
if $changed_only && ! has_changed_path 'contracts/openapi'; then
    add_skip 'OpenAPI lint: no contract changes'
elif [[ -f "$openapi_contract" ]]; then
    pnpm --dir "$PROJECT_ROOT" run contract:lint
    exit_code=$?
    if ((exit_code == 0)); then
        add_pass 'OpenAPI lint'
    else
        add_failure "OpenAPI lint exited with code $exit_code"
    fi
else
    add_skip 'OpenAPI lint: contract skeleton has not been generated yet'
fi

bruno_collection="$PROJECT_ROOT/api-tests/bruno/medication-platform/bruno.json"
if [[ -f "$bruno_collection" ]]; then
    validate_json \
        'api-tests/bruno/medication-platform/bruno.json' \
        'Bruno collection manifest is valid JSON' \
        'Bruno collection manifest is invalid'

    required_bruno_requests=(
        'api-tests/bruno/medication-platform/System/Health Check.bru'
        'api-tests/bruno/medication-platform/Uploads/01 Initialize Upload Batch.bru'
        'api-tests/bruno/medication-platform/Uploads/02 Idempotent Replay.bru'
        'api-tests/bruno/medication-platform/Uploads/03 Idempotency Conflict.bru'
        'api-tests/bruno/medication-platform/Uploads/04 Register Upload File.bru'
        'api-tests/bruno/medication-platform/Uploads/05 Create Multipart Session.bru'
        'api-tests/bruno/medication-platform/Uploads/06 Authorize Upload Part.bru'
        'api-tests/bruno/medication-platform/Uploads/07 Get Upload Part Progress.bru'
        'api-tests/bruno/medication-platform/Uploads/08 Reject Missing Part Confirmation.bru'
        'api-tests/bruno/medication-platform/Uploads/09 Reconcile Upload Parts.bru'
        'api-tests/bruno/medication-platform/Uploads/10 Reject Incomplete File Completion.bru'
        'api-tests/bruno/medication-platform/Uploads/11 Get Upload Batch Progress.bru'
        'api-tests/bruno/medication-platform/Uploads/12 List Upload File Progress.bru'
        'api-tests/bruno/medication-platform/Uploads/13 Reject Incomplete Batch Completion.bru'
        'api-tests/bruno/medication-platform/Uploads/14 Reject Session Ingest Before Batch Completion.bru'
        'api-tests/bruno/medication-platform/Uploads/15 Abort Incomplete Batch.bru'
        'api-tests/bruno/medication-platform/Camera Configuration/01 Provision Camera Channel.bru'
        'api-tests/bruno/medication-platform/Camera Configuration/02 Configure Camera Set.bru'
        'api-tests/bruno/medication-platform/Media Jobs/01 Create Media Probe Job (Missing Clip Asset).bru'
        'api-tests/bruno/medication-platform/Media Jobs/02 Create Media Probe Job (Missing Idempotency-Key).bru'
        'api-tests/bruno/medication-platform/Media Jobs/03 Get Media Probe Job (Unknown).bru'
        'api-tests/bruno/medication-platform/Media Jobs/04 Get Media Report (Unknown).bru'
        'api-tests/bruno/medication-platform/Media Jobs/05 Reject Controlled Replay Without Admin Role.bru'
    )
    for relative_path in "${required_bruno_requests[@]}"; do
        if [[ -f "$PROJECT_ROOT/$relative_path" ]]; then
            add_pass "Bruno request exists: $relative_path"
        else
            add_failure "Bruno request missing: $relative_path"
        fi
    done

    bruno_runner="$PROJECT_ROOT/scripts/run-bruno.mjs"
    if [[ -f "$bruno_runner" ]]; then
        node --check "$bruno_runner"
        exit_code=$?
        if ((exit_code == 0)); then
            add_pass 'Bruno runner syntax'
        else
            add_failure "Bruno runner syntax check exited with code $exit_code"
        fi
    else
        add_failure 'Bruno runner is missing: scripts/run-bruno.mjs'
    fi

    pnpm --dir "$PROJECT_ROOT" exec bru --version
    exit_code=$?
    if ((exit_code == 0)); then
        add_pass 'Bruno CLI is available'
    else
        add_failure "Bruno CLI exited with code $exit_code"
    fi
else
    add_skip 'Bruno collection has not been generated yet'
fi

compose_file="$PROJECT_ROOT/infra/docker-compose/compose.yaml"
if [[ -f "$compose_file" ]]; then
    if ! command -v docker >/dev/null 2>&1; then
        add_failure 'Docker CLI is required to validate the Compose file'
    else
        docker compose --env-file "$PROJECT_ROOT/.env.example" -f "$compose_file" config --quiet
        exit_code=$?
        if ((exit_code != 0)); then
            add_failure "Docker Compose config validation exited with code $exit_code"
        else
            compose_services="$(
                docker compose \
                    --env-file "$PROJECT_ROOT/.env.example" \
                    -f "$compose_file" \
                    config --services
            )"
            exit_code=$?
            if ((exit_code != 0)); then
                add_failure "Docker Compose service listing exited with code $exit_code"
            else
                actual_compose_services="$(printf '%s\n' "$compose_services" | LC_ALL=C sort | paste -sd, -)"
                if [[ "$actual_compose_services" == 'postgres,rabbitmq,s3' ]]; then
                    add_pass 'Docker Compose config contains PostgreSQL, RabbitMQ and S3'
                else
                    display_services="$(printf '%s\n' "$compose_services" | paste -sd', ' -)"
                    add_failure "Media stage Compose must contain postgres, rabbitmq and s3; found: $display_services"
                fi
            fi
        fi
    fi
else
    add_skip 'Docker Compose: PostgreSQL and S3 skeleton has not been generated yet'
fi

platform_pom="$PROJECT_ROOT/services/core-api/pom.xml"
if ! has_changed_path 'services/core-api'; then
    add_skip 'Java verify: no platform-api changes'
elif [[ -f "$platform_pom" ]]; then
    maven_wrapper="$PROJECT_ROOT/services/core-api/mvnw"
    if [[ ! -f "$maven_wrapper" ]]; then
        add_failure 'platform-api has pom.xml but Maven Wrapper is missing'
    else
        if $changed_only; then
            "$maven_wrapper" -f "$platform_pom" spring-javaformat:validate compiler:compile -DskipTests
        else
            "$maven_wrapper" -f "$platform_pom" verify
        fi
        exit_code=$?
        if ((exit_code == 0)); then
            if $changed_only; then
                add_pass 'Java fast compile and format validation'
            else
                add_pass 'Java verify'
            fi
        else
            add_failure "Java verify exited with code $exit_code"
        fi
    fi
else
    add_skip 'Java verify: platform-api skeleton has not been generated yet'
fi

web_package="$PROJECT_ROOT/apps/web/package.json"
if ! has_changed_path 'apps/web'; then
    add_skip 'Web gates: no apps/web changes'
elif [[ -f "$web_package" ]]; then
    if $changed_only; then
        web_scripts=('format:check' 'lint' 'type-check')
    else
        web_scripts=('format:check' 'lint' 'type-check' 'test' 'build')
    fi
    for script_name in "${web_scripts[@]}"; do
        if node -e '
const manifest = require(process.argv[1]);
process.exit(
  manifest.scripts && Object.prototype.hasOwnProperty.call(manifest.scripts, process.argv[2]) ? 0 : 1,
);
' "$web_package" "$script_name" >/dev/null 2>&1; then
            pnpm --dir "$PROJECT_ROOT/apps/web" run "$script_name"
            exit_code=$?
            if ((exit_code == 0)); then
                add_pass "Web $script_name"
            else
                add_failure "Web $script_name exited with code $exit_code"
            fi
        else
            add_failure "Web script missing: $script_name"
        fi
    done
else
    add_skip 'Web gates: React application has not been generated yet'
fi

synthetic_upload_client="$PROJECT_ROOT/tools/synthetic-upload-client/index.mjs"
if ! has_changed_path 'tools/synthetic-upload-client'; then
    add_skip 'Synthetic upload client unit tests: no related changes'
elif $changed_only; then
    add_skip 'Synthetic upload client unit tests: deferred to full verification'
elif [[ -f "$synthetic_upload_client" ]]; then
    pnpm --dir "$PROJECT_ROOT" run synthetic-upload:test
    exit_code=$?
    if ((exit_code == 0)); then
        add_pass 'Synthetic upload client unit tests'
    else
        add_failure "Synthetic upload client unit tests exited with code $exit_code"
    fi
else
    add_skip 'Synthetic upload client has not been generated yet'
fi

if ! has_changed_path 'scripts'; then
    add_skip 'macOS/Linux local script tests: no script changes'
elif ! command -v bash >/dev/null 2>&1; then
    add_failure 'Bash is required to validate the macOS/Linux local scripts'
else
    pnpm --dir "$PROJECT_ROOT" run local-scripts:test
    exit_code=$?
    if ((exit_code == 0)); then
        add_pass 'macOS/Linux local script tests'
    else
        add_failure "macOS/Linux local script tests exited with code $exit_code"
    fi
fi

python_projects=()
for python_search_root in "$PROJECT_ROOT/workers" "$PROJECT_ROOT/plugins" "$PROJECT_ROOT/tools/golden-capture-g0" "$PROJECT_ROOT/tools/minimal-export"; do
    [[ -d "$python_search_root" ]] || continue
    while IFS= read -r -d '' python_project; do
        python_projects[${#python_projects[@]}]="$python_project"
    done < <(
        find "$python_search_root" \
            \( -type d \( \
                -name '.git' -o \
                -name '.pnpm-store' -o \
                -name '.venv' -o \
                -name 'build' -o \
                -name 'dist' -o \
                -name 'node_modules' -o \
                -name 'target' \
            \) -prune \) -o \
            \( -type f -name pyproject.toml -print0 \) 2>/dev/null
    )
done

if ((${#python_projects[@]} == 0)); then
    echo '[DEFER] Python gates: active Python projects are activated in later delivery stages'
else
    for python_project in "${python_projects[@]}"; do
        project_dir="$(dirname "$python_project")"
        relative_project_dir="${project_dir#"$PROJECT_ROOT/"}"
        if ! has_changed_path "$relative_project_dir"; then
            add_skip "Python gates: no changes in $relative_project_dir"
            continue
        fi
        project_failed=false

        if ! pushd "$project_dir" >/dev/null; then
            add_failure "Python project directory is unavailable: $project_dir"
            continue
        fi

        uv lock --check
        if (($? != 0)); then
            project_failed=true
            add_failure "uv lock check failed: $project_dir"
        fi

        uv run ruff format --check .
        if (($? != 0)); then
            project_failed=true
            add_failure "Ruff format check failed: $project_dir"
        fi

        uv run ruff check .
        if (($? != 0)); then
            project_failed=true
            add_failure "Ruff failed: $project_dir"
        fi

        uv run python -m mypy .
        if (($? != 0)); then
            project_failed=true
            add_failure "mypy failed: $project_dir"
        fi

        if $changed_only; then
            add_skip "pytest: deferred to full verification for $project_dir"
        else
            # Keep parity with quality-gate.ps1: a repository-local per-project
            # basetemp avoids the Windows %TEMP% pytest-of-<user>/pytest-current
            # junction whose session-finish cleanup can fail with access denied.
            pytest_basetemp="$PROJECT_ROOT/.runtime/pytest/${relative_project_dir//\//_}"
            mkdir -p "$pytest_basetemp"
            uv run python -m pytest "--basetemp=$pytest_basetemp"
            if (($? != 0)); then
                project_failed=true
                add_failure "pytest failed: $project_dir"
            fi
        fi


        if ! $project_failed; then
            add_pass "Python gates: $project_dir"
        fi
        popd >/dev/null || add_failure "Could not restore repository directory after checking: $project_dir"
    done
fi

if $strict; then
    strict_required_files=(
        'contracts/openapi/openapi.yaml'
        'api-tests/bruno/medication-platform/bruno.json'
        'infra/docker-compose/compose.yaml'
    )
    for relative_path in "${strict_required_files[@]}"; do
        if [[ -f "$PROJECT_ROOT/$relative_path" ]]; then
            add_pass "Strict required file exists: $relative_path"
        else
            add_failure "Strict required file missing: $relative_path"
        fi
    done

    if ((skipped > 0)); then
        add_failure "Strict mode does not allow skipped component gates: $skipped"
    fi
fi

echo
echo "Skipped gates: $skipped"
if ((failures > 0)); then
    echo "Quality gate failed with $failures issue(s)."
    exit 1
fi

if $strict; then
    echo 'Strict quality gate for all active components passed.'
else
    echo 'Repository foundation and active component gates passed.'
    echo 'Run with --strict for complete verification of all active components.'
fi
