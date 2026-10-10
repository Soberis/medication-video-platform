[CmdletBinding()]
param(
    [switch]$KeepInfrastructure,
    [switch]$IncludeListeningApps,
    [switch]$ResetVolumes
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$exampleEnvFile = Join-Path $root '.env.example'
$localEnvFile = Join-Path $root '.env'
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

    $proc = Get-Process -Id $ManagedProcessId -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        Write-Host "$Description is not running (PID $ManagedProcessId)." -ForegroundColor Yellow
        return
    }

    $validNames = @('cmd', 'powershell', 'pwsh', 'node', 'java', 'python', 'uv')
    if (-not ($validNames -contains $proc.ProcessName.ToLower())) {
        Write-Host "$Description PID $ManagedProcessId was recycled by unrelated process '$($proc.ProcessName)'; skipping." -ForegroundColor Yellow
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
        $json = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
        if ($json) {
            foreach ($prop in $json.PSObject.Properties) {
                $entry = $prop.Value
                Stop-ProcessTree ([int]$entry.processId) $entry.name
            }
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

Import-LocalEnvironment $exampleEnvFile
if (Test-Path -LiteralPath $localEnvFile) {
    Import-LocalEnvironment $localEnvFile
}
$apiPort = if ($env:PLATFORM_API_PORT) { [int]$env:PLATFORM_API_PORT } else { 8080 }
$webPort = if ($env:WEB_PORT) { [int]$env:WEB_PORT } else { 5173 }
$targetPorts = if ($IncludeListeningApps) { @($apiPort, $webPort) } else { @($apiPort) }
foreach ($port in $targetPorts) {
    $listenerProcessId = Get-ListeningProcessId $port
    if ($null -ne $listenerProcessId) {
        Stop-ProcessTree $listenerProcessId "process listening on $port"
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
$composeEnvironmentArguments = @('--env-file', $exampleEnvFile)
if (Test-Path -LiteralPath $localEnvFile) {
    $composeEnvironmentArguments += @('--env-file', $localEnvFile)
}
$composeArguments = @('compose') + $composeEnvironmentArguments + @('-f', $composeFile, 'down')
if ($ResetVolumes) {
    $composeArguments += @('-v')
}
& docker @composeArguments --timeout 10
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Docker Compose graceful shutdown timed out. Forcing container kill and cleanup...'
    $killArguments = @('compose') + $composeEnvironmentArguments + @('-f', $composeFile, 'kill')
    & docker @killArguments 2>$null
    & docker @composeArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose did not stop cleanly even after force killing.'
    }
}

if ($ResetVolumes) {
    Write-Host 'Local applications and infrastructure stopped. Volumes were REMOVED and reset.' -ForegroundColor Yellow
}
else {
    Write-Host 'Local applications and infrastructure stopped. Volumes were preserved.' -ForegroundColor Green
}
