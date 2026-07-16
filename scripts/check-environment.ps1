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
        return ((& $Command 2>&1 | Select-Object -First 1) -join '').Trim()
    }
    catch {
        return ''
    }
}

$java = Get-FirstOutputLine { java -version }
Write-CheckResult 'Java runtime' ($java -match 'version "21(?:\.|\")') ($java ? $java : 'not found or not Java 21')

$javac = Get-FirstOutputLine { javac -version }
Write-CheckResult 'Java compiler' ($javac -match '^javac 21(?:\.|$)') ($javac ? $javac : 'not found or not Java 21')

$node = Get-FirstOutputLine { node --version }
Write-CheckResult 'Node.js' ($node -match '^v22\.') ($node ? $node : 'not found or not Node.js 22')

$pnpm = Get-FirstOutputLine { pnpm --version }
Write-CheckResult 'pnpm' ($pnpm -match '^11\.') ($pnpm ? $pnpm : 'not found or not pnpm 11')

$uv = Get-FirstOutputLine { uv --version }
Write-CheckResult 'uv' ($uv -match '^uv\s+') ($uv ? $uv : 'not found')

$pythonPath = Get-FirstOutputLine { uv python find 3.12 }
Write-CheckResult 'Python 3.12' ([bool]$pythonPath -and (Test-Path -LiteralPath $pythonPath)) ($pythonPath ? $pythonPath : 'not installed through uv or not discoverable')

$dockerClient = Get-FirstOutputLine { docker version --format '{{.Client.Version}}' }
Write-CheckResult 'Docker client' ([bool]$dockerClient) ($dockerClient ? $dockerClient : 'not found')

$dockerServer = Get-FirstOutputLine { docker version --format '{{.Server.Version}}' }
Write-CheckResult 'Docker engine' ([bool]$dockerServer) ($dockerServer ? $dockerServer : 'not running or unreachable')

$compose = Get-FirstOutputLine { docker compose version --short }
Write-CheckResult 'Docker Compose' ([bool]$compose) ($compose ? $compose : 'not found')

if ($failures.Count -gt 0) {
    Write-Host "`nEnvironment check failed:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "- $_" -ForegroundColor Red }
    exit 1
}

Write-Host "`nEnvironment baseline is ready." -ForegroundColor Green
