[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'restart', '')]
    [string]$Action = '',

    [Alias('Restart')]
    [switch]$RestartApi,

    [switch]$AiWorker,
    [switch]$InfrastructureOnly,
    [ValidateRange(1, 8)]
    [int]$MediaWorkerConcurrency = 2,
    [ValidateRange(1, 8)]
    [int]$AiWorkerConcurrency = 2,
    [ValidateRange(30, 600)]
    [int]$ApiStartupTimeoutSeconds = 180
)

if ($Action -eq 'restart') {
    $RestartApi = [switch]::Present
}

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
    param(
        [int]$Port,
        [int]$TimeoutMs = 1000
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne($TimeoutMs)) {
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

function Wait-TcpPortInUse {
    param(
        [int]$Port,
        [int]$MaxWaitSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
    do {
        if (Test-TcpPortInUse -Port $Port -TimeoutMs 1000) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
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
        if (-not (Test-ManagedProcessRunning 'api')) {
            throw "Core API process exited before becoming healthy. Inspect log: $LogPath and $LogPath.error.log"
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
    $startParams = @{
        FilePath = $FilePath
        ArgumentList = $Arguments
        WorkingDirectory = $root
        PassThru = $true
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $LogPath
        RedirectStandardError = "$LogPath.error.log"
    }
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $childEnvironment = @{}
        Get-ChildItem Env: | ForEach-Object { $childEnvironment[$_.Name] = $_.Value }
        $startParams['Environment'] = $childEnvironment
    }
    $process = Start-Process @startParams
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

$pgPort = if ($env:POSTGRES_PORT) { [int]$env:POSTGRES_PORT } else { 15432 }
$s3Port = if ($env:S3_PORT) { [int]$env:S3_PORT } else { 9000 }
$rmqPort = if ($env:RABBITMQ_PORT) { [int]$env:RABBITMQ_PORT } else { 5672 }
$requiredHostPorts = @(
    @{ Service = 'postgres'; Name = 'PostgreSQL'; Port = $pgPort },
    @{ Service = 's3'; Name = 'S3'; Port = $s3Port },
    @{ Service = 'rabbitmq'; Name = 'RabbitMQ'; Port = $rmqPort }
)
foreach ($requiredHostPort in $requiredHostPorts) {
    if (Wait-TcpPortInUse -Port $requiredHostPort.Port -MaxWaitSeconds 8) { continue }

    Write-Host "$($requiredHostPort.Name) is healthy in Docker but port $($requiredHostPort.Port) is not published. Recreating only $($requiredHostPort.Service)..." -ForegroundColor Yellow
    & docker compose @composeEnvironmentArguments -f $composeFile up -d --wait --force-recreate $requiredHostPort.Service
    if ($LASTEXITCODE -ne 0 -or -not (Wait-TcpPortInUse -Port $requiredHostPort.Port -MaxWaitSeconds 20)) {
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

$apiPort = if ($env:PLATFORM_API_PORT) { [int]$env:PLATFORM_API_PORT } else { 8080 }
$apiHealthUri = "http://127.0.0.1:$apiPort/actuator/health"
$apiLogPath = Join-Path $logDirectory 'api.log'

if ($InfrastructureOnly -and ($AiWorker -or $RestartApi)) {
    throw 'Infrastructure-only startup cannot start or restart applications.'
}
if ($RestartApi) {
    Restart-ManagedApi
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
    Start-LocalProcess 'api' 'Core API' 'cmd.exe' "/c services\\core-api\\mvnw.cmd -f services\\core-api\\pom.xml -Dspring-boot.run.profiles=local spring-boot:run" $apiLogPath
    Wait-CoreApiHealthy $apiHealthUri $ApiStartupTimeoutSeconds $apiLogPath
}

if (-not $InfrastructureOnly) {
    foreach ($requiredCommand in @('uv', 'ffmpeg', 'ffprobe')) {
        if (-not (Get-Command $requiredCommand -ErrorAction SilentlyContinue)) {
            throw "Worker startup requires $requiredCommand for media processing."
        }
    }

    for ($workerIndex = 1; $workerIndex -le $MediaWorkerConcurrency; $workerIndex++) {
        $workerKey = if ($workerIndex -eq 1) { 'worker' } else { "worker-$workerIndex" }
        $workerName = "Media Worker $workerIndex"
        $workerLogPath = if ($workerIndex -eq 1) {
            Join-Path $logDirectory 'worker.log'
        }
        else {
            Join-Path $logDirectory "worker-$workerIndex.log"
        }

        if (Test-ManagedProcessRunning $workerKey) {
            Write-Host "$workerName is already managed by this script (PID $($managedProcesses[$workerKey].processId))." -ForegroundColor Yellow
        }
        else {
            Start-LocalProcess $workerKey $workerName 'cmd.exe' "/c uv run --directory workers/media-worker media-worker" $workerLogPath
        }
    }
}

if ($AiWorker -and $env:AI_HAND_POSE_ENABLED -ne 'true') {
    throw 'AI Worker startup requires AI_HAND_POSE_ENABLED=true.'
}
if ($AiWorker -or ((-not $InfrastructureOnly) -and $env:AI_HAND_POSE_ENABLED -eq 'true')) {
    foreach ($requiredVariable in @('AI_HAND_POSE_MODEL_PATH', 'AI_HAND_POSE_MODEL_SHA256')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredVariable, 'Process'))) {
            throw "AI Worker startup requires $requiredVariable when AI_HAND_POSE_ENABLED=true."
        }
    }
    $aiWorkerBearerToken = [Environment]::GetEnvironmentVariable('AI_WORKER_BEARER_TOKEN', 'Process')
    $aiWorkerBearerTokenFile = [Environment]::GetEnvironmentVariable('AI_WORKER_BEARER_TOKEN_FILE', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($aiWorkerBearerToken) -and -not [string]::IsNullOrWhiteSpace($aiWorkerBearerTokenFile)) {
        throw 'Configure exactly one of AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE.'
    }
    if ([string]::IsNullOrWhiteSpace($aiWorkerBearerToken) -and [string]::IsNullOrWhiteSpace($aiWorkerBearerTokenFile)) {
        throw 'AI Worker startup requires AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE when AI_HAND_POSE_ENABLED=true.'
    }
    if (-not [string]::IsNullOrWhiteSpace($aiWorkerBearerTokenFile)) {
        if (-not [IO.Path]::IsPathRooted($aiWorkerBearerTokenFile)) {
            throw 'AI_WORKER_BEARER_TOKEN_FILE must be an absolute path.'
        }
        if (-not (Test-Path -LiteralPath $aiWorkerBearerTokenFile -PathType Leaf)) {
            throw 'AI_WORKER_BEARER_TOKEN_FILE does not point to a readable token file.'
        }
    }
    if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
        throw 'AI Worker startup requires uv.'
    }
    for ($workerIndex = 1; $workerIndex -le $AiWorkerConcurrency; $workerIndex++) {
        $workerKey = if ($workerIndex -eq 1) { 'ai-worker' } else { "ai-worker-$workerIndex" }
        $workerName = "AI Worker $workerIndex"
        $workerLogPath = if ($workerIndex -eq 1) {
            Join-Path $logDirectory 'ai-worker.log'
        }
        else {
            Join-Path $logDirectory "ai-worker-$workerIndex.log"
        }

        if (Test-ManagedProcessRunning $workerKey) {
            Write-Host "$workerName is already managed by this script (PID $($managedProcesses[$workerKey].processId))." -ForegroundColor Yellow
        }
        else {
            Start-LocalProcess $workerKey $workerName 'cmd.exe' "/c uv run --directory workers/ai-worker ai-worker" $workerLogPath
        }
    }
}

Write-Host "`nInfrastructure: PostgreSQL localhost:$env:POSTGRES_PORT | S3 http://localhost:$env:S3_PORT | RabbitMQ http://localhost:$env:RABBITMQ_MANAGEMENT_PORT" -ForegroundColor Cyan
Write-Host "Core API: $apiHealthUri" -ForegroundColor Cyan
Write-Host 'Start Web separately: pnpm run web:dev' -ForegroundColor Cyan
Write-Host "Workers: $MediaWorkerConcurrency Media Worker replica(s); $AiWorkerConcurrency AI Worker replica(s) when AI_HAND_POSE_ENABLED=true." -ForegroundColor Cyan
Write-Host "Follow API log: Get-Content '$logDirectory/api.log' -Wait" -ForegroundColor DarkGray
Write-Host "Status: .\scripts\status-local.ps1 | Stop: .\scripts\stop-local.ps1" -ForegroundColor DarkGray
