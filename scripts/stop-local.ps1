[CmdletBinding()]
param(
    [switch]$KeepInfrastructure,
    [switch]$IncludeListeningApps
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $envFile = Join-Path $root '.env.example'
}
$logDirectory = Join-Path $root 'logs/local'
$runtimeFile = Join-Path $logDirectory 'managed-processes.json'

function Import-LocalEnvironment {
    param([string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2 -and $parts[0]) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
        }
    }
}

function Stop-ProcessTree {
    param([int]$ManagedProcessId, [string]$Description)

    if ($null -eq (Get-Process -Id $ManagedProcessId -ErrorAction SilentlyContinue)) {
        Write-Host "$Description is not running (PID $ManagedProcessId)." -ForegroundColor Yellow
        return
    }

    & taskkill.exe /PID $ManagedProcessId /T /F | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Stopped $Description (PID $ManagedProcessId)." -ForegroundColor Green
    }
    else {
        Write-Warning "Could not stop $Description (PID $ManagedProcessId)."
    }
}

function Get-ListeningProcessId {
    param([int]$Port)

    $pattern = "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$"
    foreach ($line in (& netstat.exe -ano -p tcp)) {
        if ($line -match $pattern) {
            return [int]$Matches[1]
        }
    }

    return $null
}

if (Test-Path -LiteralPath $runtimeFile) {
    try {
        $managedProcesses = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json -AsHashtable
        foreach ($key in $managedProcesses.Keys) {
            $entry = $managedProcesses[$key]
            Stop-ProcessTree ([int]$entry.processId) $entry.name
        }
    }
    catch {
        Write-Warning "Cannot read local process record: $runtimeFile"
    }
    Remove-Item -LiteralPath $runtimeFile -Force -ErrorAction SilentlyContinue
}
else {
    Write-Host 'No managed Core API, Web app, or Worker process record exists.' -ForegroundColor Yellow
}

if ($IncludeListeningApps) {
    Import-LocalEnvironment $envFile
    foreach ($port in @([int]($env:PLATFORM_API_PORT ?? '8080'), [int]($env:WEB_PORT ?? '5173'))) {
        $listenerProcessId = Get-ListeningProcessId $port
        if ($null -ne $listenerProcessId) {
            Stop-ProcessTree $listenerProcessId "process listening on $port"
        }
    }
}

if ($KeepInfrastructure) {
    Write-Host 'Infrastructure remains running.' -ForegroundColor Cyan
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is unavailable; local containers were not stopped.'
}

$composeFile = Join-Path $root 'infra/docker-compose/compose.yaml'
& docker compose --env-file $envFile -f $composeFile down
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose did not stop cleanly.'
}

Write-Host 'Local applications and infrastructure stopped. Volumes were preserved.' -ForegroundColor Green
