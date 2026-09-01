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

quality_gate_config="$PROJECT_ROOT/scripts/quality-gate.config.json"
quality_gate_config_validator="$PROJECT_ROOT/scripts/internal/validate-quality-gate-config.mjs"
if ! config_validation_error="$(node "$quality_gate_config_validator" "$quality_gate_config" 2>&1)"; then
    if [[ -n "$config_validation_error" ]]; then
        add_failure "Quality gate config is invalid: $quality_gate_config ($config_validation_error)"
    else
        add_failure "Quality gate config is invalid: $quality_gate_config"
    fi
    exit 1
fi

read_config_array() {
    local property="$1"
    node -e '
const fs = require("node:fs");
const [file, property] = process.argv.slice(1);
const config = JSON.parse(fs.readFileSync(file, "utf8"));
const values = config[property];
process.stdout.write(values.map(String).join("\0") + "\0");
' "$quality_gate_config" "$property"
}

read_config_records() {
    local property="$1"
    shift
    node -e '
const fs = require("node:fs");
const [file, property, ...fields] = process.argv.slice(1);
const config = JSON.parse(fs.readFileSync(file, "utf8"));
const records = config[property];
const output = records
  .map((record) => fields.map((field) => String(record[field] ?? "")).join("\t"))
  .join("\n");
process.stdout.write(output.length > 0 ? `${output}\n` : "");
' "$quality_gate_config" "$property" "$@"
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

required_files=()
while IFS= read -r -d '' relative_path; do
    required_files+=("$relative_path")
done < <(read_config_array requiredFiles)

for relative_path in "${required_files[@]}"; do
    if [[ -f "$PROJECT_ROOT/$relative_path" ]]; then
        add_pass "Required file exists: $relative_path"
    else
        add_failure "Required file missing: $relative_path"
    fi
done

while IFS=$'\t' read -r relative_path success_message failure_prefix; do
    [[ -n "$relative_path" ]] || continue
    validate_json "$relative_path" "$success_message" "$failure_prefix"
done < <(read_config_records jsonContracts path success failure)

while IFS=$'\t' read -r relative_path profile_name expected_digest digest_success_message digest_failure_prefix; do
    [[ -n "$relative_path" ]] || continue
    validate_profile \
        "$relative_path" \
        "$profile_name" \
        "$expected_digest" \
        "$digest_success_message" \
        "$digest_failure_prefix"
done < <(read_config_records profiles path name expectedDigest digestSuccess digestFailure)
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

bruno_manifest="$(node -e '
const fs = require("node:fs");
const [file] = process.argv.slice(1);
process.stdout.write(JSON.parse(fs.readFileSync(file, "utf8")).brunoManifest);
' "$quality_gate_config")"
bruno_collection="$PROJECT_ROOT/$bruno_manifest"
if [[ -f "$bruno_collection" ]]; then
    validate_json \
        "$bruno_manifest" \
        'Bruno collection manifest is valid JSON' \
        'Bruno collection manifest is invalid'

    required_bruno_requests=()
    while IFS= read -r -d '' relative_path; do
        required_bruno_requests+=("$relative_path")
    done < <(read_config_array brunoRequests)
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
    add_skip 'Java verify: no core-api changes'
elif [[ -f "$platform_pom" ]]; then
    maven_wrapper="$PROJECT_ROOT/services/core-api/mvnw"
    if [[ ! -f "$maven_wrapper" ]]; then
        add_failure 'core-api has pom.xml but Maven Wrapper is missing'
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
    add_skip 'Java verify: core-api skeleton has not been generated yet'
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
for python_search_root in \
    "$PROJECT_ROOT/workers" \
    "$PROJECT_ROOT/plugins" \
    "$PROJECT_ROOT/tools/hand-pose-evaluation" \
    "$PROJECT_ROOT/tools/candidate-capacity-review"; do
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

        uv run --locked ruff format --check .
        if (($? != 0)); then
            project_failed=true
            add_failure "Ruff format check failed: $project_dir"
        fi

        uv run --locked ruff check .
        if (($? != 0)); then
            project_failed=true
            add_failure "Ruff failed: $project_dir"
        fi

        uv run --locked python -m mypy .
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
            uv run --locked python -m pytest "--basetemp=$pytest_basetemp"
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
    strict_required_files=()
    while IFS= read -r -d '' relative_path; do
        strict_required_files+=("$relative_path")
    done < <(read_config_array strictRequiredFiles)
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
