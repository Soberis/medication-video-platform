[CmdletBinding()]
param(
    [switch]$Worker
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $envFile = Join-Path $root '.env.example'
}

function Import-LocalEnvironment {
    param([string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2 -or -not $parts[0]) {
            throw "Invalid local environment line: $line"
        }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
    }
}

function Test-TcpPortInUse {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Start-LocalProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string]$Arguments,
        [string]$LogPath
    )
    Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $root `
        -WindowStyle Hidden -RedirectStandardOutput $LogPath -RedirectStandardError "$LogPath.error.log"
    Write-Host "Started $Name. Log: $LogPath" -ForegroundColor Green
}

Import-LocalEnvironment $envFile

if (-not (Get-Command docker -ErrorAction SilentlyContinue) -or -not (docker version --format '{{.Server.Version}}' 2>$null)) {
    throw 'Docker Desktop is not running or unreachable. Start Docker Desktop, then run this script again.'
}

$composeFile = Join-Path $root 'infra/docker-compose/compose.yaml'
& docker compose --env-file $envFile -f $composeFile up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure did not become healthy.' }

$logDirectory = Join-Path $root 'logs/local'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$apiPort = [int]($env:PLATFORM_API_PORT ?? '8080')
$webPort = [int]($env:WEB_PORT ?? '5173')

if (Test-TcpPortInUse $apiPort) {
    Write-Host "Core API already listens on $apiPort." -ForegroundColor Yellow
}
else {
    Start-LocalProcess 'Core API' 'cmd.exe' "/c services\\platform-api\\mvnw.cmd -f services\\platform-api\\pom.xml spring-boot:run" (Join-Path $logDirectory 'api.log')
}

if (Test-TcpPortInUse $webPort) {
    Write-Host "Web app already listens on $webPort." -ForegroundColor Yellow
}
elseif (-not (Test-Path -LiteralPath (Join-Path $root 'apps/web/node_modules'))) {
    Write-Warning 'Web dependencies are not installed. Run: pnpm install --frozen-lockfile'
}
else {
    Start-LocalProcess 'Web app' 'cmd.exe' "/c pnpm --dir apps/web dev -- --port $webPort" (Join-Path $logDirectory 'web.log')
}

if ($Worker) {
    Start-LocalProcess 'Media Worker' 'cmd.exe' "/c uv run --directory workers/media-worker media-worker" (Join-Path $logDirectory 'worker.log')
}

Write-Host "`nInfrastructure: PostgreSQL localhost:$env:POSTGRES_PORT | S3 http://localhost:$env:S3_PORT | RabbitMQ http://localhost:$env:RABBITMQ_MANAGEMENT_PORT" -ForegroundColor Cyan
Write-Host "Core API: http://localhost:$apiPort/actuator/health" -ForegroundColor Cyan
Write-Host "Web: http://localhost:$webPort/projects" -ForegroundColor Cyan
Write-Host "Follow API log: Get-Content '$logDirectory/api.log' -Wait" -ForegroundColor DarkGray
