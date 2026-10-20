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
        $health = Invoke-RestMethod -Uri $HealthUri -Method Get -TimeoutSec 8
        return $health.status -eq 'UP'
    }
    catch {
        return $false
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

function Ensure-RemoteWorkerPortProxy {
    $rules = @(
        @{ ListenPort = 5673; ConnectPort = 5672; Name = "RabbitMQ (4090 Worker)" },
        @{ ListenPort = 19001; ConnectPort = 19000; Name = "S3 (4090 Worker)" }
    )

    $portproxyConfig = (& netsh interface portproxy show all) -join "`n"

    $missingRules = @()
    foreach ($r in $rules) {
        $lp = $r.ListenPort
        $cp = $r.ConnectPort
        if ($portproxyConfig -notmatch "\b$lp\b.*\b$cp\b") {
            $missingRules += $r
        }
    }

    if ($missingRules.Count -eq 0) {
        return
    }

    Write-Host "`n[*] 检测到缺少跨机 4090 Worker 端口转发规则，正在尝试自动补充..." -ForegroundColor Yellow
    $allSucceeded = $true
    foreach ($r in $missingRules) {
        $lp = $r.ListenPort
        $cp = $r.ConnectPort
        $cmd = "netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=$lp connectaddress=127.0.0.1 connectport=$cp"
        try {
            $null = Invoke-Expression $cmd 2>&1
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  [√] 已添加 $($r.Name) 转发: 0.0.0.0:$lp -> 127.0.0.1:$cp" -ForegroundColor Green
            }
            else {
                $allSucceeded = $false
            }
        }
        catch {
            $allSucceeded = $false
        }
    }

    if (-not $allSucceeded) {
        Write-Warning "自动添加端口转发需要管理员权限。如果 Ubuntu 4090 无法连接，请在管理员 PowerShell 中执行以下命令："
        foreach ($r in $missingRules) {
            Write-Host "  netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=$($r.ListenPort) connectaddress=127.0.0.1 connectport=$($r.ConnectPort)" -ForegroundColor Cyan
        }
        Write-Host ""
    }
}

function Wait-PortCompletelyFree {
    param(
        [int]$Port,
        [int]$MaxWaitSeconds = 6
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($MaxWaitSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $conns = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
        if (-not $conns) { return $true }

        $summary = ($conns | Select-Object -ExpandProperty State -Unique) -join ', '
        Write-Host "Port $Port is busy with transient connection ($summary). Waiting for release..." -ForegroundColor Yellow
        Start-Sleep -Milliseconds 800
    }

    return (-not (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue))
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

    $proc = Get-Process -Id $managedProcesses[$Key].processId -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        return $false
    }

    $validNames = @('cmd', 'powershell', 'pwsh', 'node', 'java', 'python', 'uv')
    return $validNames -contains $proc.ProcessName.ToLower()
}

function Restart-ManagedApi {
    if ($managedProcesses.Contains('api')) {
        $processId = [int]$managedProcesses.api.processId
        if ($null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
            & taskkill.exe /PID $processId /T /F | Out-Null
            Write-Host "Stopped managed Core API (PID $processId)." -ForegroundColor Yellow
        }
        $managedProcesses.Remove('api')
        Save-ManagedProcesses
    }
    elseif (Test-TcpPortInUse $apiPort) {
        $stalePid = Get-ListeningProcessId $apiPort
        if ($stalePid) {
            & taskkill.exe /PID $stalePid /T /F | Out-Null
            Write-Host "Stopped Core API on port $apiPort (PID $stalePid)." -ForegroundColor Yellow
        }
    }
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
& docker compose @composeEnvironmentArguments -f $composeFile up -d
if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure did not start.' }

Ensure-RemoteWorkerPortProxy

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
        $savedProcesses = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
        if ($savedProcesses) {
            foreach ($prop in $savedProcesses.PSObject.Properties) {
                $key = $prop.Name
                $entry = $prop.Value
                if ($null -ne (Get-Process -Id $entry.processId -ErrorAction SilentlyContinue)) {
                    $managedProcesses[$key] = [ordered]@{
                        processId = [int]$entry.processId
                        name = [string]$entry.name
                        log = [string]$entry.log
                    }
                }
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

$needStartApi = $false
if (Test-TcpPortInUse $apiPort) {
    if (-not (Test-CoreApiHealthy $apiHealthUri)) {
        Write-Host "Port $apiPort is occupied, but Core API is not healthy (UP). Auto-healing stale listener..." -ForegroundColor Yellow
        $stalePid = Get-ListeningProcessId $apiPort
        if ($stalePid) {
            & taskkill.exe /PID $stalePid /T /F | Out-Null
            Start-Sleep -Seconds 1
        }
        if ($managedProcesses.Contains('api')) {
            $managedProcesses.Remove('api')
            Save-ManagedProcesses
        }
        $needStartApi = $true
    }
    else {
        Write-Host "Core API is already healthy: $apiHealthUri" -ForegroundColor Yellow
    }
}
elseif (Test-ManagedProcessRunning 'api') {
    Write-Host "Core API is already starting (PID $($managedProcesses.api.processId))." -ForegroundColor Yellow
    Wait-CoreApiHealthy $apiHealthUri $ApiStartupTimeoutSeconds $apiLogPath
}
else {
    $needStartApi = $true
}

if ($needStartApi) {
    Wait-PortCompletelyFree $apiPort | Out-Null
    if (-not $env:MAVEN_OPTS) { $env:MAVEN_OPTS = '-Xms128m -Xmx512m' }
    $jvmArgs = if ($env:CORE_API_JVM_ARGS) { $env:CORE_API_JVM_ARGS } else { '-Xms256m -Xmx768m' }
    $s3Ep = if ($env:S3_ENDPOINT) { $env:S3_ENDPOINT } else { 'http://localhost:19000' }
    $projId = if ($env:DEV_PROJECT_ID) { $env:DEV_PROJECT_ID } else { '944a19eb-03b1-4b55-98fb-f8e108e23d92' }
    Start-LocalProcess 'api' 'Core API' 'cmd.exe' "/c services\\core-api\\mvnw.cmd -f services\\core-api\\pom.xml -Dmaven.test.skip=true -Dspring-boot.run.profiles=local -DS3_ENDPOINT=""$s3Ep"" -DDEV_PROJECT_ID=""$projId"" -Dspring-boot.run.jvmArguments=""$jvmArgs"" spring-boot:run" $apiLogPath
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

