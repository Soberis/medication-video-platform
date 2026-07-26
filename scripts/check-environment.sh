#!/usr/bin/env bash

set -u -o pipefail

failures=0

pass() {
    echo "[PASS] $1: $2"
}

fail() {
    echo "[FAIL] $1: $2"
    failures=$((failures + 1))
}

first_line() {
    if ! command -v "$1" >/dev/null 2>&1; then
        return 0
    fi
    "$@" 2>&1 | head -n 1
}

java_version="$(first_line java -version)"
if [[ "$java_version" =~ version\ \"21(\.|\\\") ]]; then
    pass "Java runtime" "$java_version"
else
    fail "Java runtime" "${java_version:-not found or not Java 21}"
fi

javac_version="$(first_line javac -version)"
if [[ "$javac_version" =~ ^javac\ 21(\.|$) ]]; then
    pass "Java compiler" "$javac_version"
else
    fail "Java compiler" "${javac_version:-not found or not Java 21}"
fi

node_version="$(first_line node --version)"
if [[ "$node_version" =~ ^v22\. ]]; then
    pass "Node.js" "$node_version"
else
    fail "Node.js" "${node_version:-not found or not Node.js 22}"
fi

pnpm_version="$(first_line pnpm --version)"
if [[ "$pnpm_version" =~ ^11\. ]]; then
    pass "pnpm" "$pnpm_version"
else
    fail "pnpm" "${pnpm_version:-not found or not pnpm 11}"
fi

uv_version="$(first_line uv --version)"
if [[ "$uv_version" =~ ^uv[[:space:]] ]]; then
    pass "uv" "$uv_version"
else
    fail "uv" "${uv_version:-not found}"
fi

python_path="$(uv python find 3.12 2>/dev/null | head -n 1)"
if [[ -n "$python_path" && -f "$python_path" ]]; then
    pass "Python 3.12" "$python_path"
else
    fail "Python 3.12" "not installed through uv or not discoverable"
fi

docker_client="$(docker version --format '{{.Client.Version}}' 2>/dev/null | head -n 1)"
if [[ -n "$docker_client" ]]; then
    pass "Docker client" "$docker_client"
else
    fail "Docker client" "not found"
fi

docker_server="$(docker version --format '{{.Server.Version}}' 2>/dev/null | head -n 1)"
if [[ -n "$docker_server" ]]; then
    pass "Docker engine" "$docker_server"
else
    fail "Docker engine" "not running or unreachable"
fi

compose_version="$(docker compose version --short 2>/dev/null | head -n 1)"
if [[ -n "$compose_version" ]]; then
    pass "Docker Compose" "$compose_version"
else
    fail "Docker Compose" "not found"
fi

ffmpeg_version="$(first_line ffmpeg -version)"
if [[ "$ffmpeg_version" =~ ^ffmpeg[[:space:]]version[[:space:]] ]]; then
    pass "FFmpeg" "$ffmpeg_version"
else
    fail "FFmpeg" "${ffmpeg_version:-not found}"
fi

ffprobe_version="$(first_line ffprobe -version)"
if [[ "$ffprobe_version" =~ ^ffprobe[[:space:]]version[[:space:]] ]]; then
    pass "FFprobe" "$ffprobe_version"
else
    fail "FFprobe" "${ffprobe_version:-not found}"
fi

echo
if ((failures > 0)); then
    echo "Environment check failed with $failures issue(s)."
    exit 1
fi

echo "Environment baseline is ready."
