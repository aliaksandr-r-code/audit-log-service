param(
    [ValidateSet("snapshot", "stop")]
    [string]$Mode = "stop"
)

$ErrorActionPreference = "Stop"

function Write-HookJson {
    param(
        [hashtable]$Payload
    )

    $Payload | ConvertTo-Json -Depth 8 -Compress
}

function Get-RepoRoot {
    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $scriptDir "..\..")).Path
}

function Get-BaselinePath {
    $scriptDir = Split-Path -Parent $PSCommandPath
    return Join-Path $scriptDir ".spec-self-eval-baseline.json"
}

function Convert-ToRepoPath {
    param(
        [string]$Path,
        [string]$RepoRoot
    )

    $fullPath = (Resolve-Path $Path).Path
    $relative = $fullPath.Substring($RepoRoot.Length).TrimStart("\", "/")
    return ($relative -replace "\\", "/")
}

function Get-FeatureFromSpecPath {
    param(
        [string]$Path
    )

    $repoPath = ($Path -replace "\\", "/").Trim()
    if ($repoPath -notmatch "^\.specs/([^/]+)/(.+)$") {
        return $null
    }

    $feature = $Matches[1]
    $leaf = Split-Path $repoPath -Leaf

    if ($feature.StartsWith("_")) {
        return $null
    }

    if ($leaf -match "^eval-report-\d{4}-\d{2}-\d{2}(?:-\d+)?\.md$") {
        return $null
    }

    return $feature
}

function Get-SpecSnapshot {
    param(
        [string]$RepoRoot
    )

    $snapshot = @{}
    $specRoot = Join-Path $RepoRoot ".specs"
    if (-not (Test-Path $specRoot)) {
        return $snapshot
    }

    foreach ($file in (Get-ChildItem -Path $specRoot -Recurse -File -Force)) {
        $repoPath = Convert-ToRepoPath $file.FullName $RepoRoot
        if ($null -eq (Get-FeatureFromSpecPath $repoPath)) {
            continue
        }

        $snapshot[$repoPath] = (Get-FileHash -Algorithm SHA256 -Path $file.FullName).Hash
    }

    return $snapshot
}

function Save-Baseline {
    param(
        [string]$RepoRoot
    )

    $payload = @{
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        files = Get-SpecSnapshot $RepoRoot
    }

    $payload | ConvertTo-Json -Depth 8 | Set-Content -Path (Get-BaselinePath) -Encoding UTF8
}

function Get-ChangedSpecPaths {
    param(
        [string]$RepoRoot
    )

    $baselinePath = Get-BaselinePath
    if (-not (Test-Path $baselinePath)) {
        Save-Baseline $RepoRoot
        return @()
    }

    $baseline = Get-Content $baselinePath -Raw | ConvertFrom-Json
    $before = @{}
    if ($null -ne $baseline.files) {
        foreach ($property in $baseline.files.PSObject.Properties) {
            $before[$property.Name] = [string]$property.Value
        }
    }

    $after = Get-SpecSnapshot $RepoRoot
    $allPaths = New-Object System.Collections.Generic.HashSet[string]
    foreach ($path in $before.Keys) {
        [void]$allPaths.Add($path)
    }
    foreach ($path in $after.Keys) {
        [void]$allPaths.Add($path)
    }

    $changed = New-Object System.Collections.Generic.List[string]
    foreach ($path in $allPaths) {
        $beforeHash = $before[$path]
        $afterHash = $after[$path]
        if ($beforeHash -ne $afterHash) {
            $changed.Add($path)
        }
    }

    return @($changed | Sort-Object)
}

function Get-TouchedFeatures {
    param(
        [string]$RepoRoot
    )

    $features = New-Object System.Collections.Generic.HashSet[string]
    foreach ($path in (Get-ChangedSpecPaths $RepoRoot)) {
        $feature = Get-FeatureFromSpecPath $path
        if ($null -ne $feature) {
            [void]$features.Add($feature)
        }
    }

    return @($features | Sort-Object)
}

function Get-LatestReport {
    param(
        [string]$RepoRoot,
        [string]$Feature
    )

    $featureDir = Join-Path $RepoRoot ".specs\$Feature"
    if (-not (Test-Path $featureDir)) {
        return $null
    }

    return Get-ChildItem -Path $featureDir -Filter "eval-report-*.md" -File |
        Sort-Object LastWriteTimeUtc, Name -Descending |
        Select-Object -First 1
}

function Get-FailItems {
    param(
        [string]$ReportPath
    )

    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($line in (Get-Content $ReportPath)) {
        if ($line -match "\[FAIL\]") {
            $failures.Add($line.Trim())
            continue
        }

        if ($line -match "^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*FAIL\s*\|\s*([^|]+?)\s*\|") {
            $id = $Matches[1].Trim()
            $item = $Matches[2].Trim()
            $evidence = $Matches[3].Trim()
            $failures.Add("${id}: ${item} - ${evidence}")
        }
    }

    return @($failures)
}

function Invoke-SpecSelfEval {
    param(
        [string]$RepoRoot,
        [string]$Feature
    )

    $prompt = @"
Run the repo-local spec-self-eval skill for feature '$Feature'.

Follow .codex/skills/spec-self-eval/SKILL.md exactly:
- read .specs/_eval-checklist.MD and .specs/$Feature/requirements.MD, design.MD, and tasks.MD
- write the dated eval report into .specs/$Feature/
- do not edit requirements.MD, design.MD, tasks.MD, or source files
- reply only with the report path, PASS / WEAK / FAIL counts, and overall verdict
"@

    & codex exec --cd $RepoRoot --disable codex_hooks --sandbox workspace-write $prompt | Out-Null
    return $LASTEXITCODE
}

try {
    [void][Console]::In.ReadToEnd()

    $repoRoot = Get-RepoRoot
    if ($Mode -eq "snapshot") {
        Save-Baseline $repoRoot
        exit 0
    }

    $features = Get-TouchedFeatures $repoRoot
    if ($features.Count -eq 0) {
        Save-Baseline $repoRoot
        exit 0
    }

    $blocked = New-Object System.Collections.Generic.List[string]

    foreach ($feature in $features) {
        $exitCode = Invoke-SpecSelfEval $repoRoot $feature
        if ($exitCode -ne 0) {
            $blocked.Add(".specs/$feature/: spec-self-eval command failed; rerun /spec-self-eval $feature and fix the report before stopping.")
            continue
        }

        $report = Get-LatestReport $repoRoot $feature
        if ($null -eq $report) {
            $blocked.Add(".specs/$feature/: spec-self-eval did not produce an eval-report-*.md file.")
            continue
        }

        $failures = Get-FailItems $report.FullName
        foreach ($failure in $failures) {
            $blocked.Add(".specs/$feature/ ($($report.Name)): $failure")
        }
    }

    if ($blocked.Count -gt 0) {
        $reason = @"
spec-self-eval found FAIL items in touched spec folders. Fix these before ending the turn:

- $($blocked -join "`n- ")
"@
        Write-HookJson @{ decision = "block"; reason = $reason.Trim() }
        exit 0
    }

    Save-Baseline $repoRoot
    exit 0
}
catch {
    $message = "spec-self-eval Stop hook failed: $($_.Exception.Message)"
    Write-HookJson @{ decision = "block"; reason = $message }
    exit 0
}
