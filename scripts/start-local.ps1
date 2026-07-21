[CmdletBinding()]
param(
    [switch]$Worker,
    [switch]$InfrastructureOnly
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

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(500)) {
            return $false
        }
        $client.EndConnect($connect)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Start-LocalProcess {
    param(
        [string]$Key,
        [string]$Name,
        [string]$FilePath,
        [string]$Arguments,
        [string]$LogPath
    )
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $root `
        -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $LogPath -RedirectStandardError "$LogPath.error.log"
    $managedProcesses[$Key] = [ordered]@{
        processId = $process.Id
        name = $Name
        log = $LogPath
    }
    Save-ManagedProcesses
    Write-Host "Started $Name (PID $($process.Id)). Log: $LogPath" -ForegroundColor Green
}

function Save-ManagedProcesses {
    $managedProcesses | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $runtimeFile -Encoding UTF8
}

function Test-ManagedProcessRunning {
    param([string]$Key)

    if (-not $managedProcesses.Contains($Key)) {
        return $false
    }

    return $null -ne (Get-Process -Id $managedProcesses[$Key].processId -ErrorAction SilentlyContinue)
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
$runtimeFile = Join-Path $logDirectory 'managed-processes.json'
$managedProcesses = [ordered]@{}
if (Test-Path -LiteralPath $runtimeFile) {
    try {
        $savedProcesses = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json -AsHashtable
        foreach ($key in $savedProcesses.Keys) {
            if ($null -ne (Get-Process -Id $savedProcesses[$key].processId -ErrorAction SilentlyContinue)) {
                $managedProcesses[$key] = $savedProcesses[$key]
            }
        }
    }
    catch {
        Write-Warning "Ignoring invalid local process record: $runtimeFile"
    }
}

$apiPort = [int]($env:PLATFORM_API_PORT ?? '8080')

if ($InfrastructureOnly) {
    Write-Host "Infrastructure is ready. Run .\scripts\status-local.ps1 to inspect it." -ForegroundColor Cyan
    return
}

if (Test-TcpPortInUse $apiPort) {
    Write-Host "Core API already listens on $apiPort." -ForegroundColor Yellow
}
else {
    Start-LocalProcess 'api' 'Core API' 'cmd.exe' "/c services\\platform-api\\mvnw.cmd -f services\\platform-api\\pom.xml -Dspring-boot.run.profiles=local spring-boot:run" (Join-Path $logDirectory 'api.log')
}

if ($Worker) {
    if (Test-ManagedProcessRunning 'worker') {
        Write-Host "Media Worker is already managed by this script (PID $($managedProcesses.worker.processId))." -ForegroundColor Yellow
    }
    else {
        Start-LocalProcess 'worker' 'Media Worker' 'cmd.exe' "/c uv run --directory workers/media-worker media-worker" (Join-Path $logDirectory 'worker.log')
    }
}

Write-Host "`nInfrastructure: PostgreSQL localhost:$env:POSTGRES_PORT | S3 http://localhost:$env:S3_PORT | RabbitMQ http://localhost:$env:RABBITMQ_MANAGEMENT_PORT" -ForegroundColor Cyan
Write-Host "Core API: http://localhost:$apiPort/actuator/health" -ForegroundColor Cyan
Write-Host 'Start Web separately: pnpm run web:dev' -ForegroundColor Cyan
Write-Host "Follow API log: Get-Content '$logDirectory/api.log' -Wait" -ForegroundColor DarkGray
Write-Host "Status: .\scripts\status-local.ps1 | Stop: .\scripts\stop-local.ps1" -ForegroundColor DarkGray
