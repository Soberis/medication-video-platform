[CmdletBinding()]
param(
    [switch]$Strict,
    [switch]$Changed
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()
$skipped = [System.Collections.Generic.List[string]]::new()

if ($Strict -and $Changed) {
    throw '-Strict and -Changed cannot be used together. Use -Strict before merging.'
}

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Add-Pass {
    param([string]$Message)
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Add-Skip {
    param([string]$Message)
    $skipped.Add($Message)
    Write-Host "[SKIP] $Message" -ForegroundColor Yellow
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (($algorithm.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-PythonBasetemp {
    param([string]$ProjectDir)

    # The default pytest basetemp (%TEMP%\pytest-of-<user>\pytest-current) is a
    # reparse point on Windows and its session-finish cleanup can fail with
    # PermissionError [WinError 5] Access denied. Give every Python project its
    # own repository-local basetemp so runs never share the system junction.
    $relative = [IO.Path]::GetRelativePath($root, $ProjectDir).Replace('\', '_').Replace('/', '_')
    return Join-Path $root (".runtime/pytest/$relative")
}

function Get-GitRepositoryPaths {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.Arguments = "-C `"$root`" -c core.quotepath=false ls-files -z --cached --others --exclude-standard"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $output = $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "git ls-files failed with exit code $($process.ExitCode)"
    }

    $output -split [char]0 | Where-Object { $_ }
}

$changedFiles = @()
if ($Changed) {
    $changedFiles = @(
        & git -C $root -c core.quotepath=false diff --name-only --cached
        & git -C $root -c core.quotepath=false diff --name-only
        & git -C $root -c core.quotepath=false ls-files --others --exclude-standard
    ) | Where-Object { $_ } | Sort-Object -Unique
    if ($changedFiles.Count -eq 0) {
        Write-Host 'No uncommitted changes; fast quality gate has nothing to verify.' -ForegroundColor Green
        exit 0
    }
}

function Test-ChangedPath {
    param([string]$RelativePath)

    if (-not $Changed) {
        return $true
    }
    $prefix = $RelativePath.TrimEnd('/', '\') + '/'
    return @($changedFiles | Where-Object { $_ -eq $RelativePath -or $_.StartsWith($prefix) }).Count -gt 0
}

$qualityGateConfigPath = Join-Path $root 'scripts/quality-gate.config.json'
$qualityGateConfigValidatorPath = Join-Path $root 'scripts/internal/validate-quality-gate-config.mjs'
try {
    $validationOutput = @(& node $qualityGateConfigValidatorPath $qualityGateConfigPath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $validationMessage = ($validationOutput | ForEach-Object { $_.ToString() }) -join ' '
        if ([string]::IsNullOrWhiteSpace($validationMessage)) {
            $validationMessage = 'shared structure validation failed'
        }
        throw $validationMessage
    }

    $qualityGateConfig = Get-Content -LiteralPath $qualityGateConfigPath -Raw | ConvertFrom-Json
}
catch {
    Add-Failure "Quality gate config is invalid: $($_.Exception.Message)"
    exit 1
}

$hardcodedWindowsPaths = @(
    Get-GitRepositoryPaths |
        Where-Object { [System.IO.Path]::GetExtension($_) -notin @('.lock', '.jar', '.class', '.docx') } |
        ForEach-Object {
            Select-String -LiteralPath (Join-Path $root $_) -Pattern '(?i)\b[A-Z]:[\\/]' -ErrorAction SilentlyContinue
        }
)
foreach ($match in $hardcodedWindowsPaths) {
    $relativePath = $match.Path.Substring($root.Length).TrimStart([char[]]@('\', '/'))
    Add-Failure "Hard-coded local Windows path: ${relativePath}:$($match.LineNumber)"
}

$requiredFiles = @($qualityGateConfig.requiredFiles)

foreach ($relativePath in $requiredFiles) {
    $path = Join-Path $root $relativePath
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Add-Pass "Required file exists: $relativePath"
    }
    else {
        Add-Failure "Required file missing: $relativePath"
    }
}

foreach ($jsonContract in @($qualityGateConfig.jsonContracts)) {
    try {
        Get-Content -LiteralPath (Join-Path $root $jsonContract.path) -Raw |
            ConvertFrom-Json | Out-Null
        Add-Pass $jsonContract.success
    }
    catch {
        Add-Failure "$($jsonContract.failure): $($_.Exception.Message)"
    }
}

foreach ($profile in @($qualityGateConfig.profiles)) {
    try {
        $profileContent = (Get-Content -LiteralPath (Join-Path $root $profile.path) -Raw).TrimEnd("`r", "`n")
        $profileContent | ConvertFrom-Json | Out-Null
        Add-Pass "$($profile.name) is valid JSON"

        $profileBytes = [System.Text.Encoding]::UTF8.GetBytes($profileContent)
        $profileDigest = Get-Sha256Hex $profileBytes
        if ($profileDigest -eq $profile.expectedDigest) {
            Add-Pass $profile.digestSuccess
        }
        else {
            Add-Failure "$($profile.digestFailure): $profileDigest"
        }
    }
    catch {
        Add-Failure "$($profile.name) is invalid: $($_.Exception.Message)"
    }
}

function Get-ProjectConfigFiles {
    param([string]$Directory)

    $excludedDirectories = @('.git', '.pnpm-store', '.venv', 'build', 'dist', 'node_modules', 'target')
    foreach ($item in Get-ChildItem -LiteralPath $Directory -Force -ErrorAction SilentlyContinue) {
        if ($item.PSIsContainer) {
            if ($excludedDirectories -notcontains $item.Name) {
                Get-ProjectConfigFiles -Directory $item.FullName
            }
            continue
        }

        if ($item.Extension -in @('.yml', '.yaml', '.json', '.toml', '.properties')) {
            $item
        }
    }
}

$configFiles = Get-ProjectConfigFiles -Directory $root

$latestMatches = @()
foreach ($file in $configFiles) {
    $matches = Select-String -LiteralPath $file.FullName -Pattern '(?i)(image\s*:\s*\S+:latest\b|["'']latest["''])' -ErrorAction SilentlyContinue
    if ($matches) {
        $latestMatches += $matches
    }
}

if ($latestMatches.Count -eq 0) {
    Add-Pass 'No floating latest image or dependency marker found in configuration files'
}
else {
    foreach ($match in $latestMatches) {
        Add-Failure ("Floating latest reference: {0}:{1}" -f $match.Path, $match.LineNumber)
    }
}

$openApiContract = Join-Path $root 'contracts/openapi/openapi.yaml'
if ($Changed -and -not (Test-ChangedPath 'contracts/openapi')) {
    Add-Skip 'OpenAPI lint: no contract changes'
}
elseif (Test-Path -LiteralPath $openApiContract -PathType Leaf) {
    & pnpm --dir $root run contract:lint
    if ($LASTEXITCODE -eq 0) {
        Add-Pass 'OpenAPI lint'
    }
    else {
        Add-Failure "OpenAPI lint exited with code $LASTEXITCODE"
    }
}
else {
    Add-Skip 'OpenAPI lint: contract skeleton has not been generated yet'
}

$brunoCollection = Join-Path $root $qualityGateConfig.brunoManifest
if (Test-Path -LiteralPath $brunoCollection -PathType Leaf) {
    try {
        Get-Content -LiteralPath $brunoCollection -Raw | ConvertFrom-Json | Out-Null
        Add-Pass 'Bruno collection manifest is valid JSON'
    }
    catch {
        Add-Failure "Bruno collection manifest is invalid: $($_.Exception.Message)"
    }

    $requiredBrunoRequests = @($qualityGateConfig.brunoRequests)
    foreach ($relativePath in $requiredBrunoRequests) {
        if (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf) {
            Add-Pass "Bruno request exists: $relativePath"
        }
        else {
            Add-Failure "Bruno request missing: $relativePath"
        }
    }

    $brunoRunner = Join-Path $root 'scripts/run-bruno.mjs'
    if (Test-Path -LiteralPath $brunoRunner -PathType Leaf) {
        & node --check $brunoRunner
        if ($LASTEXITCODE -eq 0) {
            Add-Pass 'Bruno runner syntax'
        }
        else {
            Add-Failure "Bruno runner syntax check exited with code $LASTEXITCODE"
        }
    }
    else {
        Add-Failure 'Bruno runner is missing: scripts/run-bruno.mjs'
    }

    & pnpm --dir $root exec bru --version
    if ($LASTEXITCODE -eq 0) {
        Add-Pass 'Bruno CLI is available'
    }
    else {
        Add-Failure "Bruno CLI exited with code $LASTEXITCODE"
    }
}
else {
    Add-Skip 'Bruno collection has not been generated yet'
}

$composeFile = Join-Path $root 'infra/docker-compose/compose.yaml'
if (Test-Path -LiteralPath $composeFile -PathType Leaf) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Add-Failure 'Docker CLI is required to validate the Compose file'
    }
    else {
        & docker compose --env-file (Join-Path $root '.env.example') -f $composeFile config --quiet
        if ($LASTEXITCODE -ne 0) {
            Add-Failure "Docker Compose config validation exited with code $LASTEXITCODE"
        }
        else {
            $composeServices = @(& docker compose --env-file (Join-Path $root '.env.example') -f $composeFile config --services)
            $actualComposeServices = @($composeServices | Sort-Object) -join ','
            if ($LASTEXITCODE -ne 0) {
                Add-Failure "Docker Compose service listing exited with code $LASTEXITCODE"
            }
            elseif ($actualComposeServices -ne 'postgres,rabbitmq,s3') {
                Add-Failure "Media stage Compose must contain postgres, rabbitmq and s3; found: $($composeServices -join ', ')"
            }
            else {
                Add-Pass 'Docker Compose config contains PostgreSQL, RabbitMQ and S3'
            }
        }
    }
}
else {
    Add-Skip 'Docker Compose: PostgreSQL and S3 skeleton has not been generated yet'
}

$platformPom = Join-Path $root 'services/core-api/pom.xml'
if (-not (Test-ChangedPath 'services/core-api')) {
    Add-Skip 'Java verify: no core-api changes'
}
elseif (Test-Path -LiteralPath $platformPom) {
    $mavenWrapperName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'mvnw.cmd' } else { 'mvnw' }
    $mavenWrapper = Join-Path $root "services/core-api/$mavenWrapperName"
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        Add-Failure 'core-api has pom.xml but Maven Wrapper is missing'
    }
    else {
        if ($Changed) {
            & $mavenWrapper -f $platformPom spring-javaformat:validate compiler:compile -DskipTests
        }
        else {
            & $mavenWrapper -f $platformPom verify
        }
        if ($LASTEXITCODE -eq 0) {
            Add-Pass $(if ($Changed) { 'Java fast compile and format validation' } else { 'Java verify' })
        }
        else {
            Add-Failure "Java verify exited with code $LASTEXITCODE"
        }
    }
}
else {
    Add-Skip 'Java verify: core-api skeleton has not been generated yet'
}

$webPackage = Join-Path $root 'apps/web/package.json'
if (-not (Test-ChangedPath 'apps/web')) {
    Add-Skip 'Web gates: no apps/web changes'
}
elseif (Test-Path -LiteralPath $webPackage) {
    $webManifest = Get-Content -LiteralPath $webPackage -Raw | ConvertFrom-Json
    $webScripts = if ($Changed) { @('format:check', 'lint', 'type-check') } else {
        @('format:check', 'lint', 'type-check', 'test', 'build')
    }
    foreach ($script in $webScripts) {
        if ($webManifest.scripts.PSObject.Properties.Name -contains $script) {
            & pnpm --dir (Join-Path $root 'apps/web') run $script
            if ($LASTEXITCODE -eq 0) {
                Add-Pass "Web $script"
            }
            else {
                Add-Failure "Web $script exited with code $LASTEXITCODE"
            }
        }
        else {
            Add-Failure "Web script missing: $script"
        }
    }
}
else {
    Add-Skip 'Web gates: React application has not been generated yet'
}

$syntheticUploadClient = Join-Path $root 'tools/synthetic-upload-client/index.mjs'
if (-not (Test-ChangedPath 'tools/synthetic-upload-client')) {
    Add-Skip 'Synthetic upload client unit tests: no related changes'
}
elseif ($Changed) {
    Add-Skip 'Synthetic upload client unit tests: deferred to full verification'
}
elseif (Test-Path -LiteralPath $syntheticUploadClient -PathType Leaf) {
    & pnpm --dir $root run synthetic-upload:test
    if ($LASTEXITCODE -eq 0) {
        Add-Pass 'Synthetic upload client unit tests'
    }
    else {
        Add-Failure "Synthetic upload client unit tests exited with code $LASTEXITCODE"
    }
}
else {
    Add-Skip 'Synthetic upload client has not been generated yet'
}

if (-not (Test-ChangedPath 'scripts')) {
    Add-Skip 'macOS/Linux local script tests: no script changes'
}
elseif ($IsWindows -or $env:OS -eq 'Windows_NT') {
    Add-Pass 'macOS/Linux local script tests are not applicable on Windows'
}
elseif ($null -eq (Get-Command bash -ErrorAction SilentlyContinue)) {
    Add-Failure 'Bash is required to validate the macOS/Linux local scripts'
}
else {
    & pnpm --dir $root run local-scripts:test
    if ($LASTEXITCODE -eq 0) {
        Add-Pass 'macOS/Linux local script tests'
    }
    else {
        Add-Failure "macOS/Linux local script tests exited with code $LASTEXITCODE"
    }
}

$pythonSearchRoots = @(
    (Join-Path $root 'workers'),
    (Join-Path $root 'plugins'),
    (Join-Path $root 'tools/minimal-export'),
    (Join-Path $root 'tools/hand-pose-evaluation'),
    (Join-Path $root 'tools/candidate-capacity-review')
) | Where-Object { Test-Path -LiteralPath $_ -PathType Container }

# Keep generated/ignored verification copies (for example under tmp/) out of
# the production Python project inventory.  The Bash gate already scopes its
# search to these active roots; matching that boundary prevents a stale copy
# from producing a false strict-gate failure.
$pythonProjects = @(
    foreach ($pythonSearchRoot in $pythonSearchRoots) {
        Get-ChildItem -LiteralPath $pythonSearchRoot -Recurse -File -Filter pyproject.toml -ErrorAction SilentlyContinue
    }
)

if ($Changed) {
    $pythonProjects = @($pythonProjects | Where-Object {
            $relativeProjectPath = [IO.Path]::GetRelativePath($root, $_.DirectoryName).Replace('\', '/')
            Test-ChangedPath $relativeProjectPath
        })
}

if ($pythonProjects.Count -eq 0) {
    Add-Skip 'Python gates: no active Python project changes'
}
else {
    foreach ($project in $pythonProjects) {
        $projectDir = Split-Path -Parent $project.FullName
        $projectFailed = $false
        Push-Location $projectDir
        try {
            & uv lock --check
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "uv lock check failed: $projectDir"
            }
            & uv run --locked ruff format --check .
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "Ruff format check failed: $projectDir"
            }
            & uv run --locked ruff check .
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "Ruff failed: $projectDir"
            }
            & uv run --locked python -m mypy .
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "mypy failed: $projectDir"
            }
            if ($Changed) {
                Add-Skip "pytest: deferred to full verification for $projectDir"
            }
            else {
                $basetemp = Get-PythonBasetemp $projectDir
                New-Item -ItemType Directory -Force -Path $basetemp | Out-Null
                & uv run --locked python -m pytest "--basetemp=$basetemp"
                if ($LASTEXITCODE -ne 0) {
                    $projectFailed = $true
                    Add-Failure "pytest failed: $projectDir"
                }
            }
            if (-not $projectFailed) { Add-Pass "Python gates: $projectDir" }
        }
        finally {
            Pop-Location
        }
    }
}

if ($Strict) {
    foreach ($relativePath in @($qualityGateConfig.strictRequiredFiles)) {
        $path = Join-Path $root $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Add-Pass "Strict required file exists: $relativePath"
        }
        else {
            Add-Failure "Strict required file missing: $relativePath"
        }
    }

    if ($skipped.Count -gt 0) {
        Add-Failure "Strict mode does not allow skipped component gates: $($skipped.Count)"
    }
}

Write-Host "`nSkipped gates: $($skipped.Count)" -ForegroundColor Yellow
if ($failures.Count -gt 0) {
    Write-Host "Quality gate failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}

if ($Strict) {
    Write-Host 'Strict quality gate for all active components passed.' -ForegroundColor Green
}
else {
    Write-Host 'Repository foundation and active component gates passed.' -ForegroundColor Green
    Write-Host 'Run with -Strict for complete verification of all active components.' -ForegroundColor Yellow
}
