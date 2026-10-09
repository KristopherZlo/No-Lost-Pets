param(
    [ValidateNotNullOrEmpty()][string[]]$Versions = @("26.1", "26.1.1", "26.1.2", "26.2", "26.3"),
    [string]$JavaHome,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "versions.ps1")

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

function Invoke-VersionVerify {
    param(
        [hashtable]$Target,
        [string]$GradlePath,
        [string]$RepoRoot,
        [string]$LogRoot,
        [int]$TimeoutSeconds,
        [string[]]$ExpectedGameTests,
        [int]$ExpectedUnitTests
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
        '$ErrorActionPreference = ''Continue'''
        ('& ''{0}'' ''test'' ''runGameTest'' ''--rerun-tasks'' ''--no-daemon'' ''--console=plain'' ''-Pminecraft_version={1}'' ''-Ploader_version={2}'' ''-Pfabric_version={3}'' ''-Pmod_version={4}'' ''-Ploom_run_dir=run/{1}/gametest'' ''-g'' ''{5}''' -f $GradlePath.Replace("'", "''"), $Target.version, $Target.loader, $Target.fabric_api, $Target.mod_version, (Join-Path $RepoRoot '.gradle-user-home').Replace("'", "''"))
        'exit $LASTEXITCODE'
    )
    [System.IO.File]::WriteAllLines($runnerScript, $scriptLines)

    $processArguments = @(
        "-NoProfile"
        "-NonInteractive"
        "-ExecutionPolicy"
        "Bypass"
        "-File"
        ('"' + $runnerScript + '"')
    )

    $startedAt = Get-Date
    $process = Start-Process -FilePath "powershell.exe" `
        -ArgumentList $processArguments `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru `
        -WindowStyle Hidden

    $status = "passed"
    $exitCode = 0

    try {
        $finished = $false
        while (-not $finished) {
            if (((Get-Date) - $startedAt).TotalSeconds -ge $TimeoutSeconds) {
                $status = "timeout"
                $exitCode = -1
                Stop-ProcessTree -RootIds @($process.Id)
                break
            }

            $stdoutText = if (Test-Path -LiteralPath $stdoutLog) { Get-Content -LiteralPath $stdoutLog -Raw } else { "" }
            $stderrText = if (Test-Path -LiteralPath $stderrLog) { Get-Content -LiteralPath $stderrLog -Raw } else { "" }
            $hasBuildFailure = $stdoutText -match "BUILD FAILED" -or $stderrText -match "BUILD FAILED"
            $hasGameTestFailure = $stdoutText -match "\d+ required tests failed"

            if ($hasGameTestFailure -or $hasBuildFailure) {
                $status = "failed"
                $exitCode = 1
                Stop-ProcessTree -RootIds @($process.Id)
                break
            }

            if ($process.HasExited) {
                $process.WaitForExit()
                $exitCode = $process.ExitCode
                $finalStdout = Get-Content -LiteralPath $stdoutLog -Raw -Encoding UTF8
                $finalStderr = if (Test-Path -LiteralPath $stderrLog) { Get-Content -LiteralPath $stderrLog -Raw -Encoding UTF8 } else { "" }
                $testsRan = $finalStdout -match "All [1-9][0-9]* required tests passed"
                $gameReport = Join-Path $RepoRoot "build/gametest-results/$version.xml"
                if ((Test-Path -LiteralPath $gameReport) -and (Get-Item -LiteralPath $gameReport).LastWriteTime -ge $startedAt.AddSeconds(-2)) {
                    [xml]$gameXml = Get-Content -LiteralPath $gameReport -Raw
                    $gameCases = @($gameXml.SelectNodes('//testcase') | Where-Object { $_.name -like 'pet_recall_gametest:*' })
                    $gameNames = @($gameCases | ForEach-Object { [string]$_.name })
                    $testsRan = $testsRan -and $gameCases.Count -eq $ExpectedGameTests.Count -and
                        @($gameXml.SelectNodes('//failure|//error|//skipped')).Count -eq 0 -and
                        @(Compare-Object $ExpectedGameTests $gameNames).Count -eq 0
                } else { $testsRan = $false }
                $unitRan = 0
                $unitFailed = 0
                foreach ($report in Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'build/test-results/test') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
                    if ($report.LastWriteTime -lt $startedAt.AddSeconds(-2)) { continue }
                    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                    $unitRan += [int]$xml.testsuite.tests
                    $unitFailed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors + [int]$xml.testsuite.skipped
                }
                $testsRan = $testsRan -and $unitRan -eq $ExpectedUnitTests -and $unitFailed -eq 0
                $buildPassed = $finalStdout -match "BUILD SUCCESSFUL"
                $testsFailed = $finalStdout -match "[1-9][0-9]* required tests failed"
                $buildFailed = $finalStdout -match "BUILD FAILED" -or $finalStderr -match "BUILD FAILED"
                if ($exitCode -eq 0 -and $testsRan -and $buildPassed -and -not $testsFailed -and -not $buildFailed) {
                    $status = "passed"
                } else {
                    $status = "failed"
                }
                break
            }

            Start-Sleep -Seconds 1
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

$expectedGameTests = @()
$expectedUnitTests = 0
foreach ($file in Get-ChildItem (Join-Path $repoRoot 'src/gametest/java') -Recurse -Filter '*.java') {
    $testClass = [regex]::Replace($file.BaseName, '([a-z0-9])([A-Z])', '$1_$2').ToLowerInvariant()
    foreach ($method in [regex]::Matches((Get-Content $file.FullName -Raw), '@GameTest\s*\([^)]*\)\s*public void (\w+)\(')) {
        $testMethod = [regex]::Replace($method.Groups[1].Value, '([a-z0-9])([A-Z])', '$1_$2').ToLowerInvariant()
        $expectedGameTests += "pet_recall_gametest:${testClass}_$testMethod"
    }
}
foreach ($file in Get-ChildItem (Join-Path $repoRoot 'src/test/java') -Recurse -Filter '*.java') {
    $expectedUnitTests += [regex]::Matches((Get-Content $file.FullName -Raw), '@Test\b').Count
}
if ($expectedGameTests.Count -eq 0 -or $expectedUnitTests -eq 0) { throw 'No tests discovered in source sets' }

$results = @()
$targets = @()
foreach ($version in $Versions) {
    if (-not $matrix.ContainsKey($version)) {
        throw "Unsupported target '$version'. Supported targets: $($matrix.Keys -join ', ')"
    }
    $target = Get-MinecraftTarget $version
    $targets += $target
}

foreach ($target in $targets) {
        Write-Host ("Running verify suites on " + $target.version + "...")
        $result = Invoke-VersionVerify `
            -Target $target `
            -GradlePath $gradle `
            -RepoRoot $repoRoot `
            -LogRoot $logRoot `
            -TimeoutSeconds $TimeoutSeconds `
            -ExpectedGameTests $expectedGameTests `
            -ExpectedUnitTests $expectedUnitTests
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
