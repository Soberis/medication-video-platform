[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$exampleEnvFile = Join-Path $root '.env.example'
$localEnvFile = Join-Path $root '.env'

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

function Show-Listener {
    param([string]$Name, [int]$Port)

    if (-not (Test-TcpPortInUse $Port)) {
        Write-Host "${Name}: not listening on $Port" -ForegroundColor Yellow
        return
    }

    Write-Host "${Name}: listening on $Port" -ForegroundColor Green
}

function Show-CoreApiHealth {
    param([int]$Port)

    $healthUri = "http://127.0.0.1:$Port/actuator/health"
    if (-not (Test-TcpPortInUse $Port)) {
        Write-Host "Core API: not listening on $Port" -ForegroundColor Yellow
        return
    }

    try {
        $health = Invoke-RestMethod -Uri $healthUri -Method Get -TimeoutSec 2
        if ($health.status -eq 'UP') {
            Write-Host "Core API: healthy on $Port" -ForegroundColor Green
            return
        }
    }
    catch {
        # The listener exists but did not return the expected health response.
    }

    Write-Host "Core API: listening on $Port, but health is not UP" -ForegroundColor Yellow
}

Import-LocalEnvironment $exampleEnvFile
if (Test-Path -LiteralPath $localEnvFile) {
    Import-LocalEnvironment $localEnvFile
}

$composeEnvironmentArguments = @('--env-file', $exampleEnvFile)
if (Test-Path -LiteralPath $localEnvFile) {
    $composeEnvironmentArguments += @('--env-file', $localEnvFile)
}

$composeFile = Join-Path $root 'infra/docker-compose/compose.yaml'
$logDirectory = Join-Path $root 'logs/local'
$runtimeFile = Join-Path $logDirectory 'managed-processes.json'

Write-Host 'Infrastructure:' -ForegroundColor Cyan
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker compose @composeEnvironmentArguments -f $composeFile ps
}
else {
    Write-Host 'Docker CLI is unavailable.' -ForegroundColor Yellow
}

Write-Host "`nApplications:" -ForegroundColor Cyan
Show-CoreApiHealth ([int]($env:PLATFORM_API_PORT ?? '8080'))
Show-Listener 'Web app' ([int]($env:WEB_PORT ?? '5173'))

if (Test-Path -LiteralPath $runtimeFile) {
    Write-Host "`nProcesses started by start-local.ps1:" -ForegroundColor Cyan
    try {
        $managedProcesses = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json -AsHashtable
        foreach ($key in $managedProcesses.Keys) {
            $entry = $managedProcesses[$key]
            $running = $null -ne (Get-Process -Id $entry.processId -ErrorAction SilentlyContinue)
            $state = if ($running) { 'running' } else { 'stale record' }
            Write-Host "- $($entry.name): $state (PID $($entry.processId)); log: $($entry.log)"
        }
    }
    catch {
        Write-Warning "Cannot read local process record: $runtimeFile"
    }
}
else {
    Write-Host "`nNo managed background process record exists." -ForegroundColor Yellow
}

Write-Host "`nLogs: $logDirectory" -ForegroundColor DarkGray
