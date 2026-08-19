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

$requiredFiles = @(
    'AGENTS.md',
    'README.md',
    '.gitignore',
    '.gitattributes',
    '.editorconfig',
    '.env.example',
    '.java-version',
    '.node-version',
    '.python-version',
    'package.json',
    'pnpm-workspace.yaml',
    'contracts/schemas/capture-session-metadata-v1.schema.json',
    'contracts/events/media-probe-job-v1.schema.json',
    'contracts/events/media-probe-result-v1.schema.json',
    'contracts/events/media-dead-letter-v1.schema.json',
    'contracts/events/ai-hand-pose-job-v1.schema.json',
    'contracts/events/ai-hand-pose-result-v2.schema.json',
    'contracts/events/ai-hand-pose-dead-letter-v1.schema.json',
    'contracts/profiles/media-probe-baseline-v1.json',
    'contracts/profiles/p0-post-annotation-cleaning-v1.json',
    'tools/synthetic-upload-client/index.mjs',
    'tools/synthetic-upload-client/upload-client.mjs',
    'tools/synthetic-upload-client/synthetic-video.mjs',
    'tools/synthetic-upload-client/upload-client.test.mjs',
    'tools/synthetic-upload-client/large-upload-acceptance.mjs',
    'tools/synthetic-upload-client/large-upload-acceptance.test.mjs',
    'tools/synthetic-upload-client/README.md',
    'scripts/check-environment.ps1',
    'scripts/check-environment.sh',
    'scripts/quality-gate.ps1',
    'scripts/quality-gate.sh',
    'scripts/local-common.sh',
    'scripts/start-local.ps1',
    'scripts/start-local.sh',
    'scripts/start-local.test.mjs',
    'scripts/status-local.ps1',
    'scripts/status-local.sh',
    'scripts/stop-local.ps1',
    'scripts/stop-local.sh',
    'workers/media-worker/pyproject.toml',
    'workers/media-worker/uv.lock',
    'workers/media-worker/README.md',
    'workers/media-worker/src/medication_media_worker/__main__.py',
    'workers/media-worker/src/medication_media_worker/processor.py',
    'workers/media-worker/src/medication_media_worker/probe.py',
    'tools/hand-pose-poc/pyproject.toml',
    'tools/hand-pose-poc/uv.lock',
    'tools/hand-pose-poc/README.md',
    'tools/hand-pose-poc/src/hand_pose_poc/cli.py',
    'tools/golden-capture-g0/pyproject.toml',
    'tools/golden-capture-g0/uv.lock',
    'tools/golden-capture-g0/README.md',
    'tools/golden-capture-g0/fixtures/golden-captures.json',
    'tools/golden-capture-g0/src/golden_capture_g0/experiment.py',
    'docs/requirements/README.md',
    'docs/requirements/baselines/V1.14/配药视频数据管理平台需求文档_V1.14.md',
    'docs/requirements/baselines/V1.13/配药视频数据管理平台需求文档_V1.13.md',
    'docs/requirements/baselines/V1.11/配药视频数据管理平台需求文档_V1.11.md',
    'docs/requirements/baselines/V1.10/配药视频数据管理平台需求文档_V1.10.md',
    'docs/requirements/baselines/V1.2/配药视频数据管理平台需求文档_V1.2.md',
    'docs/requirements/baselines/V1.1.13/配药视频数据管理平台需求文档_V1.1.13.docx',
    'docs/architecture/README.md',
    'docs/architecture/配药平台技术设计.md',
    'docs/architecture/requirements-traceability.md',
    'docs/architecture/domain-model-and-data-ownership.md',
    'docs/architecture/state-machines.md',
    'docs/architecture/asynchronous-job-protocol.md',
    'docs/architecture/timebase-and-multicamera-sync.md',
    'docs/architecture/security-privacy-retention.md',
    'docs/architecture/deployment-capacity-and-dr.md',
    'docs/architecture/web-ux-and-frontend-guidelines.md',
    'docs/runbooks/large-upload-resume-acceptance.md'
)

foreach ($relativePath in $requiredFiles) {
    $path = Join-Path $root $relativePath
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        Add-Pass "Required file exists: $relativePath"
    }
    else {
        Add-Failure "Required file missing: $relativePath"
    }
}

try {
    Get-Content -LiteralPath (Join-Path $root 'package.json') -Raw | ConvertFrom-Json | Out-Null
    Add-Pass 'package.json is valid JSON'
}
catch {
    Add-Failure "package.json is invalid: $($_.Exception.Message)"
}

try {
    Get-Content -LiteralPath (Join-Path $root 'contracts/schemas/capture-session-metadata-v1.schema.json') -Raw |
        ConvertFrom-Json | Out-Null
    Add-Pass 'Capture session metadata JSON Schema is valid JSON'
}
catch {
    Add-Failure "Capture session metadata JSON Schema is invalid: $($_.Exception.Message)"
}

try {
    Get-Content -LiteralPath (Join-Path $root 'contracts/events/media-probe-job-v1.schema.json') -Raw |
        ConvertFrom-Json | Out-Null
    Add-Pass 'Media probe job JSON Schema is valid JSON'
}
catch {
    Add-Failure "Media probe job JSON Schema is invalid: $($_.Exception.Message)"
}

try {
    Get-Content -LiteralPath (Join-Path $root 'contracts/events/media-probe-result-v1.schema.json') -Raw |
        ConvertFrom-Json | Out-Null
    Add-Pass 'Media probe result JSON Schema is valid JSON'
}
catch {
    Add-Failure "Media probe result JSON Schema is invalid: $($_.Exception.Message)"
}

try {
    Get-Content -LiteralPath (Join-Path $root 'contracts/events/media-dead-letter-v1.schema.json') -Raw |
        ConvertFrom-Json | Out-Null
    Add-Pass 'Media dead-letter JSON Schema is valid JSON'
}
catch {
    Add-Failure "Media dead-letter JSON Schema is invalid: $($_.Exception.Message)"
}

foreach ($aiEventContract in @(
    'ai-hand-pose-job-v1.schema.json',
    'ai-hand-pose-result-v2.schema.json',
    'ai-hand-pose-dead-letter-v1.schema.json'
)) {
    try {
        Get-Content -LiteralPath (Join-Path $root "contracts/events/$aiEventContract") -Raw |
            ConvertFrom-Json | Out-Null
        Add-Pass "AI hand-pose event JSON Schema is valid JSON: $aiEventContract"
    }
    catch {
        Add-Failure "AI hand-pose event JSON Schema is invalid ($aiEventContract): $($_.Exception.Message)"
    }
}

try {
    $mediaProbeProfileContent = (Get-Content -LiteralPath (Join-Path $root 'contracts/profiles/media-probe-baseline-v1.json') -Raw).TrimEnd("`r", "`n")
    $mediaProbeProfileContent | ConvertFrom-Json | Out-Null
    Add-Pass 'Media probe baseline profile is valid JSON'

    $profileBytes = [System.Text.Encoding]::UTF8.GetBytes($mediaProbeProfileContent)
    $profileDigest = Get-Sha256Hex $profileBytes
    if ($profileDigest -eq '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765') {
        Add-Pass 'Media probe baseline profile digest matches the server contract'
    }
    else {
        Add-Failure "Media probe baseline profile digest changed unexpectedly: $profileDigest"
    }
}
catch {
    Add-Failure "Media probe baseline profile is invalid: $($_.Exception.Message)"
}

try {
    $postCleaningProfileContent = (Get-Content -LiteralPath (Join-Path $root 'contracts/profiles/p0-post-annotation-cleaning-v1.json') -Raw).TrimEnd("`r", "`n")
    $postCleaningProfileContent | ConvertFrom-Json | Out-Null
    Add-Pass 'P0 post-annotation cleaning profile is valid JSON'

    $postCleaningProfileBytes = [System.Text.Encoding]::UTF8.GetBytes($postCleaningProfileContent)
    $postCleaningProfileDigest = Get-Sha256Hex $postCleaningProfileBytes
    if ($postCleaningProfileDigest -eq '7c1aa3606467412b84964a3455af8695bd73c85cd9b5d08ce079061ed840fa0a') {
        Add-Pass 'P0 post-annotation cleaning profile digest matches the server contract'
    }
    else {
        Add-Failure "P0 post-annotation cleaning profile digest changed unexpectedly: $postCleaningProfileDigest"
    }
}
catch {
    Add-Failure "P0 post-annotation cleaning profile is invalid: $($_.Exception.Message)"
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

$brunoCollection = Join-Path $root 'api-tests/bruno/medication-platform/bruno.json'
if (Test-Path -LiteralPath $brunoCollection -PathType Leaf) {
    try {
        Get-Content -LiteralPath $brunoCollection -Raw | ConvertFrom-Json | Out-Null
        Add-Pass 'Bruno collection manifest is valid JSON'
    }
    catch {
        Add-Failure "Bruno collection manifest is invalid: $($_.Exception.Message)"
    }

    $requiredBrunoRequests = @(
        'api-tests/bruno/medication-platform/System/Health Check.bru',
        'api-tests/bruno/medication-platform/Uploads/01 Initialize Upload Batch.bru',
        'api-tests/bruno/medication-platform/Uploads/02 Idempotent Replay.bru',
        'api-tests/bruno/medication-platform/Uploads/03 Idempotency Conflict.bru',
        'api-tests/bruno/medication-platform/Uploads/04 Register Upload File.bru',
        'api-tests/bruno/medication-platform/Uploads/05 Create Multipart Session.bru',
        'api-tests/bruno/medication-platform/Uploads/06 Authorize Upload Part.bru',
        'api-tests/bruno/medication-platform/Uploads/07 Get Upload Part Progress.bru',
        'api-tests/bruno/medication-platform/Uploads/08 Reject Missing Part Confirmation.bru',
        'api-tests/bruno/medication-platform/Uploads/09 Reconcile Upload Parts.bru',
        'api-tests/bruno/medication-platform/Uploads/10 Reject Incomplete File Completion.bru',
        'api-tests/bruno/medication-platform/Uploads/11 Get Upload Batch Progress.bru',
        'api-tests/bruno/medication-platform/Uploads/12 List Upload File Progress.bru',
        'api-tests/bruno/medication-platform/Uploads/13 Reject Incomplete Batch Completion.bru',
        'api-tests/bruno/medication-platform/Uploads/14 Reject Session Ingest Before Batch Completion.bru',
        'api-tests/bruno/medication-platform/Uploads/15 Abort Incomplete Batch.bru',
        'api-tests/bruno/medication-platform/Camera Configuration/01 Provision Camera Channel.bru',
        'api-tests/bruno/medication-platform/Camera Configuration/02 Configure Camera Set.bru',
        'api-tests/bruno/medication-platform/Asset Catalog/03 List Capture Event Logs.bru',
        'api-tests/bruno/medication-platform/Media Jobs/01 Create Media Probe Job (Missing Clip Asset).bru',
        'api-tests/bruno/medication-platform/Media Jobs/02 Create Media Probe Job (Missing Idempotency-Key).bru',
        'api-tests/bruno/medication-platform/Media Jobs/03 Get Media Probe Job (Unknown).bru',
        'api-tests/bruno/medication-platform/Media Jobs/04 Get Media Report (Unknown).bru',
        'api-tests/bruno/medication-platform/Media Jobs/05 Reject Controlled Replay Without Admin Role.bru'
    )
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

$platformPom = Join-Path $root 'services/platform-api/pom.xml'
if (-not (Test-ChangedPath 'services/platform-api')) {
    Add-Skip 'Java verify: no platform-api changes'
}
elseif (Test-Path -LiteralPath $platformPom) {
    $mavenWrapperName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'mvnw.cmd' } else { 'mvnw' }
    $mavenWrapper = Join-Path $root "services/platform-api/$mavenWrapperName"
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        Add-Failure 'platform-api has pom.xml but Maven Wrapper is missing'
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
    Add-Skip 'Java verify: platform-api skeleton has not been generated yet'
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

$pythonProjects = Get-ChildItem -LiteralPath $root -Recurse -File -Filter pyproject.toml -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '[\\/](workers|plugins|tools[\\/](hand-pose-poc|golden-capture-g0|minimal-export))[\\/]' }

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
            & uv run ruff format --check .
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "Ruff format check failed: $projectDir"
            }
            & uv run ruff check .
            if ($LASTEXITCODE -ne 0) {
                $projectFailed = $true
                Add-Failure "Ruff failed: $projectDir"
            }
            & uv run python -m mypy .
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
                & uv run python -m pytest "--basetemp=$basetemp"
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
    foreach ($relativePath in @(
        'contracts/openapi/openapi.yaml',
        'api-tests/bruno/medication-platform/bruno.json',
        'infra/docker-compose/compose.yaml'
    )) {
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
