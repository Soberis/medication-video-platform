[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Write-CheckResult {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Detail
    )

    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $color = if ($Passed) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1}: {2}" -f $status, $Name, $Detail) -ForegroundColor $color
    if (-not $Passed) {
        $failures.Add("${Name}: ${Detail}")
    }
}

function Get-FirstOutputLine {
    param([scriptblock]$Command)

    try {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $lines = & $Command 2>&1
        $ErrorActionPreference = $prev
        return (($lines | ForEach-Object { "$_" } | Select-Object -First 1) -join '').Trim()
    }
    catch {
        return ''
    }
}

function Get-ValueOrDefault {
    param([string]$Value, [string]$DefaultValue)
    if ($Value) { return $Value }
    return $DefaultValue
}

$java = Get-FirstOutputLine { java -version }
Write-CheckResult 'Java runtime' ($java -match 'version "21(?:\.|\")') (Get-ValueOrDefault $java 'not found or not Java 21')

$javac = Get-FirstOutputLine { javac -version }
Write-CheckResult 'Java compiler' ($javac -match '^javac 21(?:\.|$)') (Get-ValueOrDefault $javac 'not found or not Java 21')

$node = Get-FirstOutputLine { node --version }
Write-CheckResult 'Node.js' ($node -match '^v22\.') (Get-ValueOrDefault $node 'not found or not Node.js 22')

$pnpm = Get-FirstOutputLine { pnpm --version }
Write-CheckResult 'pnpm' ($pnpm -match '^11\.') (Get-ValueOrDefault $pnpm 'not found or not pnpm 11')

$uv = Get-FirstOutputLine { uv --version }
Write-CheckResult 'uv' ($uv -match '^uv\s+') (Get-ValueOrDefault $uv 'not found')

$pythonPath = Get-FirstOutputLine { uv python find 3.12 }
Write-CheckResult 'Python 3.12' ([bool]$pythonPath -and (Test-Path -LiteralPath $pythonPath)) (Get-ValueOrDefault $pythonPath 'not installed through uv or not discoverable')

$dockerClient = Get-FirstOutputLine { docker version --format '{{.Client.Version}}' }
Write-CheckResult 'Docker client' ([bool]$dockerClient) (Get-ValueOrDefault $dockerClient 'not found')

$dockerServer = Get-FirstOutputLine { docker version --format '{{.Server.Version}}' }
Write-CheckResult 'Docker engine' ([bool]$dockerServer) (Get-ValueOrDefault $dockerServer 'not running or unreachable')

$compose = Get-FirstOutputLine { docker compose version --short }
Write-CheckResult 'Docker Compose' ([bool]$compose) (Get-ValueOrDefault $compose 'not found')

$ffmpeg = Get-FirstOutputLine { ffmpeg -version }
Write-CheckResult 'FFmpeg' ($ffmpeg -match '^ffmpeg version\s+') (Get-ValueOrDefault $ffmpeg 'not found')

$ffprobe = Get-FirstOutputLine { ffprobe -version }
Write-CheckResult 'FFprobe' ($ffprobe -match '^ffprobe version\s+') (Get-ValueOrDefault $ffprobe 'not found')

if ($failures.Count -gt 0) {
    Write-Host "`nEnvironment check failed:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "- $_" -ForegroundColor Red }
    exit 1
}

Write-Host "`nEnvironment baseline is ready." -ForegroundColor Green
