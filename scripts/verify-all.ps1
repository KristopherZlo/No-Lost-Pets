param(
    [string]$JavaHome,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"

$matrix = @(
    @{
        version = "1.21.8"
        yarn = "1.21.8+build.1"
        loader = "0.18.2"
        fabric_api = "0.136.1+1.21.8"
    }
    @{
        version = "1.21.9"
        yarn = "1.21.9+build.1"
        loader = "0.18.2"
        fabric_api = "0.134.1+1.21.9"
    }
    @{
        version = "1.21.10"
        yarn = "1.21.10+build.3"
        loader = "0.18.2"
        fabric_api = "0.138.4+1.21.10"
    }
    @{
        version = "1.21.11"
        yarn = "1.21.11+build.4"
        loader = "0.18.2"
        fabric_api = "0.141.3+1.21.11"
    }
)

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)

    $candidates = @()
    if ($PreferredJavaHome) {
        $candidates += $PreferredJavaHome
    }
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-21*" } |
        Sort-Object Name -Descending |
        Select-Object -ExpandProperty FullName
    $candidates += Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-21*" } |
        Sort-Object Name -Descending |
        Select-Object -ExpandProperty FullName
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            return $candidate
        }
    }

    throw "JDK 21 was not found. Pass -JavaHome or install JDK 21."
}

function Get-ProcessTreeIds {
    param([int[]]$RootIds)

    $seen = [System.Collections.Generic.HashSet[int]]::new()
    $queue = [System.Collections.Generic.Queue[int]]::new()

    foreach ($rootId in $RootIds) {
        if ($rootId -gt 0 -and $seen.Add($rootId)) {
            $queue.Enqueue($rootId)
        }
    }

    while ($queue.Count -gt 0) {
        $currentId = $queue.Dequeue()
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $currentId" -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            $childId = [int]$child.ProcessId
            if ($seen.Add($childId)) {
                $queue.Enqueue($childId)
            }
        }
    }

    return @($seen)
}

function Stop-ProcessTree {
    param([int[]]$RootIds)

    $treeIds = Get-ProcessTreeIds -RootIds $RootIds | Sort-Object -Descending
    foreach ($processId in $treeIds) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
}

function Get-StaleRunGameTestProcesses {
    param(
        [string]$RepoRoot,
        [int]$CurrentPid
    )

    $repoPattern = [regex]::Escape($RepoRoot)
    $targetNames = @("cmd.exe", "java.exe")

    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessId -ne $CurrentPid -and
        $targetNames -contains $_.Name -and
        $_.CommandLine -and
        $_.CommandLine -match $repoPattern -and
        $_.CommandLine -match "runGameTest|gradlew|gradle-wrapper"
    }

    return @($processes | Select-Object -ExpandProperty ProcessId -Unique)
}

function Invoke-VersionVerify {
    param(
        [hashtable]$Target,
        [string]$GradlePath,
        [string]$RepoRoot,
        [string]$LogRoot,
        [int]$TimeoutSeconds
    )

    $version = $Target.version
    $stdoutLog = Join-Path $LogRoot "$version.out.log"
    $stderrLog = Join-Path $LogRoot "$version.err.log"
    $runnerScript = Join-Path $LogRoot "$version.run.ps1"
    if (Test-Path -LiteralPath $stdoutLog) {
        Remove-Item -LiteralPath $stdoutLog -Force
    }
    if (Test-Path -LiteralPath $stderrLog) {
        Remove-Item -LiteralPath $stderrLog -Force
    }
    if (Test-Path -LiteralPath $runnerScript) {
        Remove-Item -LiteralPath $runnerScript -Force
    }

    $scriptLines = @(
        '$ErrorActionPreference = ''Stop'''
        ('& ''{0}'' ''runGameTest'' ''--no-daemon'' ''-Pminecraft_version={1}'' ''-Pyarn_mappings={2}'' ''-Ploader_version={3}'' ''-Pfabric_version={4}''' -f $GradlePath, $Target.version, $Target.yarn, $Target.loader, $Target.fabric_api)
        'exit $LASTEXITCODE'
    )
    [System.IO.File]::WriteAllLines($runnerScript, $scriptLines)

    $args = @(
        "-NoProfile"
        "-NonInteractive"
        "-ExecutionPolicy"
        "Bypass"
        "-File"
        $runnerScript
    )

    $startedAt = Get-Date
    $process = Start-Process -FilePath "powershell.exe" `
        -ArgumentList $args `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru `
        -WindowStyle Hidden

    $status = "passed"
    $exitCode = 0

    try {
        $completed = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $completed) {
            $status = "timeout"
            $exitCode = -1
            Stop-ProcessTree -RootIds @($process.Id)
        } else {
            $process.WaitForExit()
            $exitCode = $process.ExitCode
            $stdoutText = if (Test-Path -LiteralPath $stdoutLog) { Get-Content -LiteralPath $stdoutLog -Raw } else { "" }
            $stderrText = if (Test-Path -LiteralPath $stderrLog) { Get-Content -LiteralPath $stderrLog -Raw } else { "" }
            $hasBuildSuccess = $stdoutText -match "BUILD SUCCESSFUL"
            $hasBuildFailure = $stdoutText -match "BUILD FAILED" -or $stderrText -match "BUILD FAILED"

            if ($hasBuildSuccess -and -not $hasBuildFailure) {
                $status = "passed"
                if ([string]::IsNullOrWhiteSpace([string]$exitCode)) {
                    $exitCode = 0
                }
            } elseif ($hasBuildFailure) {
                $status = "failed"
                if ([string]::IsNullOrWhiteSpace([string]$exitCode)) {
                    $exitCode = 1
                }
            } elseif ($exitCode -ne 0) {
                $status = "failed"
            }
        }
    } catch {
        $status = "timeout"
        $exitCode = -1
        Stop-ProcessTree -RootIds @($process.Id)
    } finally {
        Stop-ProcessTree -RootIds @($process.Id)
    }

    $duration = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
    return [pscustomobject]@{
        Version = $version
        Status = $status
        ExitCode = $exitCode
        DurationSeconds = $duration
        StdoutLog = $stdoutLog
        StderrLog = $stderrLog
    }
}

$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$logRoot = Join-Path $repoRoot "build\tmp\verify-all"

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"

$staleIds = Get-StaleRunGameTestProcesses -RepoRoot $repoRoot -CurrentPid $PID
if ($staleIds.Count -gt 0) {
    Write-Host ("Cleaning stale runGameTest processes: " + ($staleIds -join ", "))
    Stop-ProcessTree -RootIds $staleIds
}

$results = @()

try {
    foreach ($target in $matrix) {
        Write-Host ("Running verify suites on " + $target.version + "...")
        $result = Invoke-VersionVerify `
            -Target $target `
            -GradlePath $gradle `
            -RepoRoot $repoRoot `
            -LogRoot $logRoot `
            -TimeoutSeconds $TimeoutSeconds
        $results += $result

        switch ($result.Status) {
            "passed" {
                Write-Host ("[PASS] {0} ({1}s)" -f $result.Version, $result.DurationSeconds)
            }
            "failed" {
                Write-Host ("[FAIL] {0} exit={1} log={2}" -f $result.Version, $result.ExitCode, $result.StdoutLog)
                break
            }
            "timeout" {
                Write-Host ("[TIMEOUT] {0} after {1}s log={2}" -f $result.Version, $result.DurationSeconds, $result.StdoutLog)
                break
            }
        }

        if ($result.Status -ne "passed") {
            break
        }
    }
} finally {
    $leftovers = Get-StaleRunGameTestProcesses -RepoRoot $repoRoot -CurrentPid $PID
    if ($leftovers.Count -gt 0) {
        Stop-ProcessTree -RootIds $leftovers
    }
}

Write-Host ""
$results | Format-Table Version, Status, DurationSeconds, ExitCode -AutoSize

$failedResult = $results | Where-Object { $_.Status -ne "passed" } | Select-Object -First 1
if ($failedResult) {
    Write-Host ""
    Write-Host ("Last stdout lines from " + $failedResult.StdoutLog + ":")
    if (Test-Path -LiteralPath $failedResult.StdoutLog) {
        Get-Content -LiteralPath $failedResult.StdoutLog -Tail 20
    }
    if (Test-Path -LiteralPath $failedResult.StderrLog) {
        $stderrContent = Get-Content -LiteralPath $failedResult.StderrLog -Tail 20
        if ($stderrContent) {
            Write-Host ""
            Write-Host ("Last stderr lines from " + $failedResult.StderrLog + ":")
            $stderrContent
        }
    }
    exit 1
}

exit 0
