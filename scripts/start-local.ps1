[CmdletBinding()]
param(
    [switch]$Worker,
    [switch]$AiWorker,
    [switch]$InfrastructureOnly,
    [switch]$RestartApi,
    [switch]$RestartExportWorker,
    [switch]$ExportWorkerSyntheticFailFirstAttempt,
    [switch]$ExportWorkerSyntheticLeaseLossFirstAttempt,
    [ValidateRange(30, 600)]
    [int]$ApiStartupTimeoutSeconds = 180
)

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
        if ($parts.Count -ne 2 -or -not $parts[0]) {
            throw "Invalid local environment line: $line"
        }
        Set-Item -LiteralPath ("Env:" + $parts[0].Trim()) -Value $parts[1]
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

function Test-CoreApiHealthy {
    param([string]$HealthUri)

    try {
        $health = Invoke-RestMethod -Uri $HealthUri -Method Get -TimeoutSec 2
        return $health.status -eq 'UP'
    }
    catch {
        return $false
    }
}

function Wait-CoreApiHealthy {
    param(
        [string]$HealthUri,
        [int]$TimeoutSeconds,
        [string]$LogPath
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    Write-Host "Waiting for Core API health (timeout: ${TimeoutSeconds}s)..." -ForegroundColor Cyan
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-CoreApiHealthy $HealthUri) {
            Write-Host "Core API is healthy: $HealthUri" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 1
    }

    throw "Core API did not become healthy within ${TimeoutSeconds}s. Inspect log: $LogPath"
}

function Start-LocalProcess {
    param(
        [string]$Key,
        [string]$Name,
        [string]$FilePath,
        [string]$Arguments,
        [string]$LogPath
    )
    $childEnvironment = @{}
    Get-ChildItem Env: | ForEach-Object { $childEnvironment[$_.Name] = $_.Value }
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $root `
        -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $LogPath -RedirectStandardError "$LogPath.error.log" `
        -Environment $childEnvironment
    $managedProcesses[$Key] = [ordered]@{
        processId = $process.Id
        name = $Name
        log = $LogPath
    }
    Save-ManagedProcesses
    Write-Host "Starting $Name (PID $($process.Id)). Log: $LogPath" -ForegroundColor Cyan
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

function Restart-ManagedApi {
    if (-not $managedProcesses.Contains('api')) {
        if (Test-TcpPortInUse $apiPort) {
            throw "Core API port $apiPort is occupied by an unmanaged process. Use status-local.ps1 to inspect it; this script will not terminate it."
        }
        return
    }

    $processId = [int]$managedProcesses.api.processId
    if ($null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        & taskkill.exe /PID $processId /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not stop the managed Core API process (PID $processId)."
        }
        Write-Host "Stopped managed Core API (PID $processId)." -ForegroundColor Yellow
    }
    $managedProcesses.Remove('api')
    Save-ManagedProcesses
}

function Restart-ManagedExportWorker {
    if (-not $managedProcesses.Contains('export-worker')) {
        return
    }

    $processId = [int]$managedProcesses['export-worker'].processId
    if ($null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        & taskkill.exe /PID $processId /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not stop the managed Export Worker process (PID $processId)."
        }
        Write-Host "Stopped managed Export Worker (PID $processId)." -ForegroundColor Yellow
    }
    $managedProcesses.Remove('export-worker')
    Save-ManagedProcesses
}

Import-LocalEnvironment $exampleEnvFile
if (Test-Path -LiteralPath $localEnvFile) {
    Import-LocalEnvironment $localEnvFile
}

$composeEnvironmentArguments = @('--env-file', $exampleEnvFile)
if (Test-Path -LiteralPath $localEnvFile) {
    $composeEnvironmentArguments += @('--env-file', $localEnvFile)
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue) -or -not (docker version --format '{{.Server.Version}}' 2>$null)) {
    throw 'Docker Desktop is not running or unreachable. Start Docker Desktop, then run this script again.'
}

$composeFile = Join-Path $root 'infra/docker-compose/compose.yaml'
& docker compose @composeEnvironmentArguments -f $composeFile up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure did not become healthy.' }

$requiredHostPorts = @(
    @{ Service = 'postgres'; Name = 'PostgreSQL'; Port = [int]($env:POSTGRES_PORT ?? '15432') },
    @{ Service = 's3'; Name = 'S3'; Port = [int]($env:S3_PORT ?? '9000') },
    @{ Service = 'rabbitmq'; Name = 'RabbitMQ'; Port = [int]($env:RABBITMQ_PORT ?? '5672') }
)
foreach ($requiredHostPort in $requiredHostPorts) {
    if (Test-TcpPortInUse $requiredHostPort.Port) { continue }

    Write-Host "$($requiredHostPort.Name) is healthy in Docker but port $($requiredHostPort.Port) is not published. Recreating only $($requiredHostPort.Service)..." -ForegroundColor Yellow
    & docker compose @composeEnvironmentArguments -f $composeFile up -d --wait --force-recreate $requiredHostPort.Service
    if ($LASTEXITCODE -ne 0 -or -not (Test-TcpPortInUse $requiredHostPort.Port)) {
        throw "$($requiredHostPort.Name) port $($requiredHostPort.Port) is still unavailable after recreating $($requiredHostPort.Service)."
    }
}

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
$apiHealthUri = "http://127.0.0.1:$apiPort/actuator/health"
$apiLogPath = Join-Path $logDirectory 'api.log'

# Local startup is intentionally detection-ready: media ingestion needs the Media Worker,
# and the AI Worker starts automatically below whenever the approved AI configuration is enabled.
if (-not $InfrastructureOnly) {
    $Worker = $true
}

if ($RestartExportWorker -and -not $Worker) {
    throw 'Restarting the Export Worker requires workers to be enabled.'
}
if ($ExportWorkerSyntheticFailFirstAttempt -and -not $Worker) {
    throw 'Synthetic Export Worker failure injection requires workers to be enabled.'
}
if ($ExportWorkerSyntheticLeaseLossFirstAttempt -and -not $Worker) {
    throw 'Synthetic Export Worker lease-loss injection requires workers to be enabled.'
}
if ($InfrastructureOnly -and ($Worker -or $AiWorker -or $RestartApi -or $RestartExportWorker -or $ExportWorkerSyntheticFailFirstAttempt -or $ExportWorkerSyntheticLeaseLossFirstAttempt)) {
    throw 'Infrastructure-only startup cannot start or restart applications.'
}
if ($ExportWorkerSyntheticFailFirstAttempt -and -not $RestartExportWorker) {
    throw 'Synthetic Export Worker failure injection requires -RestartExportWorker.'
}
if ($ExportWorkerSyntheticLeaseLossFirstAttempt -and -not $RestartExportWorker) {
    throw 'Synthetic Export Worker lease-loss injection requires -RestartExportWorker.'
}
if ($ExportWorkerSyntheticFailFirstAttempt -and $ExportWorkerSyntheticLeaseLossFirstAttempt) {
    throw 'Synthetic Export Worker failure and lease-loss injections are mutually exclusive.'
}
if ($ExportWorkerSyntheticFailFirstAttempt -and $env:DATA_PROFILE -ne 'synthetic') {
    throw 'Synthetic Export Worker failure injection requires DATA_PROFILE=synthetic.'
}
if ($ExportWorkerSyntheticLeaseLossFirstAttempt -and $env:DATA_PROFILE -ne 'synthetic') {
    throw 'Synthetic Export Worker lease-loss injection requires DATA_PROFILE=synthetic.'
}
if ($ExportWorkerSyntheticLeaseLossFirstAttempt -and -not $RestartApi) {
    throw 'Synthetic Export Worker lease-loss injection requires -RestartApi so local lease timings can be shortened.'
}

$env:RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE = 'false'
$env:EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT = 'false'
$env:RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE = 'false'
$env:EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT = 'false'
if ($ExportWorkerSyntheticFailFirstAttempt) {
    $env:RUN_DATASET_EXPORT_ACCEPTANCE = 'true'
    $env:RUN_DATASET_EXPORT_RECOVERY_ACCEPTANCE = 'true'
    $env:EXPORT_WORKER_SYNTHETIC_FAIL_FIRST_ATTEMPT = 'true'
    Write-Host 'Export Worker will inject one synthetic first-attempt failure for recovery acceptance.' -ForegroundColor Yellow
}
if ($ExportWorkerSyntheticLeaseLossFirstAttempt) {
    $env:RUN_DATASET_EXPORT_ACCEPTANCE = 'true'
    $env:RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE = 'true'
    $env:EXPORT_WORKER_SYNTHETIC_LEASE_LOSS_FIRST_ATTEMPT = 'true'
    $env:DATASET_EXPORT_LEASE_DURATION = 'PT10S'
    $env:DATASET_EXPORT_HEARTBEAT_INTERVAL = 'PT2S'
    $env:DATASET_EXPORT_LEASE_GRACE = 'PT2S'
    $env:DATASET_EXPORT_RECOVERY_POLL_DELAY = 'PT1S'
    Write-Host 'Export Worker will inject one synthetic lease loss with short local recovery timings.' -ForegroundColor Yellow
}

if ($RestartApi) {
    Restart-ManagedApi
}
if ($RestartExportWorker) {
    Restart-ManagedExportWorker
}

if ($InfrastructureOnly) {
    Write-Host "Infrastructure is ready. Run .\scripts\status-local.ps1 to inspect it." -ForegroundColor Cyan
    return
}

if (Test-TcpPortInUse $apiPort) {
    if (-not (Test-CoreApiHealthy $apiHealthUri)) {
        throw "Port $apiPort is occupied, but Core API health is not UP: $apiHealthUri"
    }
    Write-Host "Core API is already healthy: $apiHealthUri" -ForegroundColor Yellow
}
elseif (Test-ManagedProcessRunning 'api') {
    Write-Host "Core API is already starting (PID $($managedProcesses.api.processId))." -ForegroundColor Yellow
    Wait-CoreApiHealthy $apiHealthUri $ApiStartupTimeoutSeconds $apiLogPath
}
else {
    Start-LocalProcess 'api' 'Core API' 'cmd.exe' "/c services\\platform-api\\mvnw.cmd -f services\\platform-api\\pom.xml -Dspring-boot.run.profiles=local spring-boot:run" $apiLogPath
    Wait-CoreApiHealthy $apiHealthUri $ApiStartupTimeoutSeconds $apiLogPath
}

if ($Worker) {
    foreach ($requiredCommand in @('uv', 'ffmpeg', 'ffprobe')) {
        if (-not (Get-Command $requiredCommand -ErrorAction SilentlyContinue)) {
            throw "Worker startup requires $requiredCommand for media processing."
        }
    }
    if (Test-ManagedProcessRunning 'worker') {
        Write-Host "Media Worker is already managed by this script (PID $($managedProcesses.worker.processId))." -ForegroundColor Yellow
    }
    else {
        Start-LocalProcess 'worker' 'Media Worker' 'cmd.exe' "/c uv run --directory workers/media-worker media-worker" (Join-Path $logDirectory 'worker.log')
    }
    if (Test-ManagedProcessRunning 'export-worker') {
        Write-Host "Export Worker is already managed by this script (PID $($managedProcesses['export-worker'].processId))." -ForegroundColor Yellow
    }
    else {
        Start-LocalProcess 'export-worker' 'Export Worker' 'cmd.exe' "/c uv run --directory workers/export-worker export-worker" (Join-Path $logDirectory 'export-worker.log')
    }
}

if ($AiWorker -and $env:AI_HAND_POSE_ENABLED -ne 'true') {
    throw 'AI Worker startup requires AI_HAND_POSE_ENABLED=true.'
}
if ($AiWorker -or ($Worker -and $env:AI_HAND_POSE_ENABLED -eq 'true')) {
    foreach ($requiredVariable in @('AI_HAND_POSE_MODEL_PATH', 'AI_HAND_POSE_MODEL_SHA256')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredVariable, 'Process'))) {
            throw "AI Worker startup requires $requiredVariable when AI_HAND_POSE_ENABLED=true."
        }
    }
    $aiWorkerBearerToken = [Environment]::GetEnvironmentVariable('AI_WORKER_BEARER_TOKEN', 'Process')
    $aiWorkerBearerTokenFile = [Environment]::GetEnvironmentVariable('AI_WORKER_BEARER_TOKEN_FILE', 'Process')
    if ([string]::IsNullOrWhiteSpace($aiWorkerBearerToken) -and [string]::IsNullOrWhiteSpace($aiWorkerBearerTokenFile)) {
        throw 'AI Worker startup requires AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE when AI_HAND_POSE_ENABLED=true.'
    }
    if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
        throw 'AI Worker startup requires uv.'
    }
    if (Test-ManagedProcessRunning 'ai-worker') {
        Write-Host "AI Worker is already managed by this script (PID $($managedProcesses['ai-worker'].processId))." -ForegroundColor Yellow
    }
    else {
        Start-LocalProcess 'ai-worker' 'AI Worker' 'cmd.exe' "/c uv run --directory workers/ai-worker ai-worker" (Join-Path $logDirectory 'ai-worker.log')
    }
}

Write-Host "`nInfrastructure: PostgreSQL localhost:$env:POSTGRES_PORT | S3 http://localhost:$env:S3_PORT | RabbitMQ http://localhost:$env:RABBITMQ_MANAGEMENT_PORT" -ForegroundColor Cyan
Write-Host "Core API: $apiHealthUri" -ForegroundColor Cyan
Write-Host 'Start Web separately: pnpm run web:dev' -ForegroundColor Cyan
Write-Host 'Workers: Media and Export started by default; AI Worker starts when AI_HAND_POSE_ENABLED=true.' -ForegroundColor Cyan
Write-Host "Follow API log: Get-Content '$logDirectory/api.log' -Wait" -ForegroundColor DarkGray
Write-Host "Status: .\scripts\status-local.ps1 | Stop: .\scripts\stop-local.ps1" -ForegroundColor DarkGray
