param(
    [ValidateNotNullOrEmpty()][string[]]$Versions = @('1.21.8', '1.21.9', '1.21.10', '1.21.11'),
    [ValidateSet('fast', 'regression', 'stress', 'full')][string]$Suite = 'fast',
    [string]$JavaHome,
    [string]$GradleUserHome,
    [ValidateRange(1, 86400)][int]$TimeoutSeconds = 900,
    [switch]$ListTests
)

$ErrorActionPreference = 'Stop'

$matrix = @{
    "1.21.8" = @{
        yarn = "1.21.8+build.1"
        loader = "0.18.2"
        fabric_api = "0.136.1+1.21.8"
        mod_version = "1.1.1"
    }
    "1.21.9" = @{
        yarn = "1.21.9+build.1"
        loader = "0.18.2"
        fabric_api = "0.134.1+1.21.9"
        mod_version = "1.1.1"
    }
    "1.21.10" = @{
        yarn = "1.21.10+build.3"
        loader = "0.18.2"
        fabric_api = "0.138.4+1.21.10"
        mod_version = "1.1.1"
    }
    "1.21.11" = @{
        yarn = "1.21.11+build.4"
        loader = "0.18.2"
        fabric_api = "0.141.3+1.21.11"
        mod_version = "1.1.1"
    }
}

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)
    $candidates = if ($PreferredJavaHome) { @($PreferredJavaHome) } else {
        @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not $candidate) { continue }
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath $release) -and
            ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="21(?:\.|"|-)' )) {
            return $candidate
        }
    }
    throw 'JDK 21 is required. Install it or pass -JavaHome with its directory.'
}

function ConvertTo-SnakeCase {
    param([string]$Name)
    return [regex]::Replace($Name, '([a-z0-9])([A-Z])', '$1_$2').ToLowerInvariant()
}

function Get-TestInventory {
    param([string]$RepoRoot, [string]$Suite)
    $suites = Get-Content -LiteralPath (Join-Path $RepoRoot 'scripts/test-suites.json') -Raw | ConvertFrom-Json
    $discovered = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src/gametest/java') -Recurse -Filter '*.java' | ForEach-Object {
        $text = Get-Content -LiteralPath $_.FullName -Raw
        if ($text -match '@GameTest\b') { [regex]::Match($text, 'package\s+([\w.]+);').Groups[1].Value + '.' + $_.BaseName }
    })
    if (@(Compare-Object $suites.full $discovered).Count -gt 0 -or
        @($suites.full).Count -ne @($suites.full | Select-Object -Unique).Count -or
        @($suites.fast | Where-Object { $suites.regression -notcontains $_ }).Count -gt 0 -or
        @($suites.stress | Where-Object { $suites.regression -contains $_ }).Count -gt 0 -or
        @(Compare-Object $suites.full @($suites.regression + $suites.stress)).Count -gt 0) {
        throw 'Suite manifest omits, duplicates or incorrectly groups a server test class'
    }
    $classes = @($suites.$Suite)
    if ($classes.Count -eq 0 -or @($classes | Select-Object -Unique).Count -ne $classes.Count) { throw "Empty or duplicate entrypoints for '$Suite'" }
    $gameNames = @()
    foreach ($className in $classes) {
        $source = Join-Path $RepoRoot ('src/gametest/java/' + $className.Replace('.', '/') + '.java')
        $text = Get-Content -LiteralPath $source -Raw
        $methods = [regex]::Matches($text, '@GameTest\s*\([^)]*\)\s*public void (\w+)\(')
        if ($methods.Count -eq 0) { throw "No GameTests in entrypoint '$className'" }
        $testClass = ConvertTo-SnakeCase ($className.Split('.')[-1])
        foreach ($method in $methods) { $gameNames += "pet_recall_gametest:${testClass}_$(ConvertTo-SnakeCase $method.Groups[1].Value)" }
    }
    $unitNames = @()
    foreach ($source in Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src/test/java') -Recurse -Filter '*.java') {
        $text = Get-Content -LiteralPath $source.FullName -Raw
        $package = [regex]::Match($text, 'package\s+([\w.]+);').Groups[1].Value
        $methods = [regex]::Matches($text, '@Test\s+(?:public\s+)?void (\w+)\(')
        if ([regex]::Matches($text, '@Test\b').Count -ne $methods.Count -or $text -match '@(?:ParameterizedTest|TestFactory|RepeatedTest)\b') {
            throw "Update inventory discovery for test declarations in '$($source.Name)'"
        }
        foreach ($method in $methods) { $unitNames += "${package}.$($source.BaseName)#$($method.Groups[1].Value)()" }
    }
    if ($unitNames.Count -eq 0 -or @($gameNames | Select-Object -Unique).Count -ne $gameNames.Count -or
        @($unitNames | Select-Object -Unique).Count -ne $unitNames.Count) { throw 'Empty or duplicate test inventory' }
    return [pscustomobject]@{ Game = $gameNames; Unit = $unitNames }
}

function Test-FreshReports {
    param([string]$GameReport, [string]$UnitReports, [datetime]$StartedAt, $Inventory)
    if (-not (Test-Path -LiteralPath $GameReport) -or (Get-Item -LiteralPath $GameReport).LastWriteTime -lt $StartedAt) { return $false }
    [xml]$gameXml = Get-Content -LiteralPath $GameReport -Raw
    $gameNames = @($gameXml.SelectNodes('//testcase') | Where-Object { $_.name -like 'pet_recall_gametest:*' } | ForEach-Object { [string]$_.name })
    if (@($gameXml.SelectNodes('//failure|//error|//skipped')).Count -gt 0 -or
        $gameNames.Count -ne $Inventory.Game.Count -or @(Compare-Object $Inventory.Game $gameNames).Count -gt 0) { return $false }
    $unitNames = @()
    foreach ($report in Get-ChildItem -LiteralPath $UnitReports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
        if ($report.LastWriteTime -lt $StartedAt) { continue }
        [xml]$unitXml = Get-Content -LiteralPath $report.FullName -Raw
        if (@($unitXml.SelectNodes('//failure|//error|//skipped')).Count -gt 0) { return $false }
        $unitNames += @($unitXml.SelectNodes('//testcase') | ForEach-Object { "$($_.classname)#$($_.name)" })
    }
    return $unitNames.Count -eq $Inventory.Unit.Count -and @(Compare-Object $Inventory.Unit $unitNames).Count -eq 0
}

function Stop-OwnedProcessTree {
    param([int]$RootId)
    # Only this invocation's wrapper and descendants may be terminated.
    $tree = [System.Collections.Generic.List[int]]::new()
    $tree.Add($RootId)
    for ($i = 0; $i -lt $tree.Count; $i++) {
        foreach ($child in Get-CimInstance Win32_Process -Filter "ParentProcessId = $($tree[$i])" -ErrorAction SilentlyContinue) {
            if (-not $tree.Contains([int]$child.ProcessId)) { $tree.Add([int]$child.ProcessId) }
        }
    }
    for ($i = $tree.Count - 1; $i -ge 0; $i--) { Stop-Process -Id $tree[$i] -Force -ErrorAction SilentlyContinue }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$Suite = $Suite.ToLowerInvariant()
$inventory = Get-TestInventory -RepoRoot $repoRoot -Suite $Suite
foreach ($version in $Versions) {
    if (-not $matrix.ContainsKey($version)) { throw "Unsupported Minecraft '$version'. Targets: $($matrix.Keys -join ', ')" }
}
if ($ListTests) {
    Write-Host "Suite=$Suite GameTests=$($inventory.Game.Count) UnitTests=$($inventory.Unit.Count)"
    $inventory.Game
    $inventory.Unit
    return
}

$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
if (-not $GradleUserHome) {
    $sharedCache = Join-Path (Split-Path -Parent $repoRoot) 'pet-recall/.gradle-user-home'
    $GradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME }
        elseif (Test-Path -LiteralPath $sharedCache) { $sharedCache } else { Join-Path $repoRoot '.gradle-user-home' }
}
$GradleUserHome = [System.IO.Path]::GetFullPath($GradleUserHome)
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$logRoot = Join-Path $repoRoot "build/tmp/verify-all/$runId"
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$results = @()

foreach ($version in $Versions) {
    $target = $matrix[$version]
    $stdoutLog = Join-Path $logRoot "$version-$Suite.out.log"
    $stderrLog = Join-Path $logRoot "$version-$Suite.err.log"
    $runner = Join-Path $logRoot "$version-$Suite.run.ps1"
    # Rerun tests only; unchanged compilation and resource tasks keep their cache.
    $gradleArguments = @('test', '--rerun', 'runGameTest', '--no-daemon', '--console=plain', '-g', $GradleUserHome,
        "-Pminecraft_version=$version", "-Ploader_version=$($target.loader)", "-Pfabric_version=$($target.fabric_api)",
        "-Pmod_version=$($target.mod_version)", "-Ppet_test_suite=$Suite", "-Ploom_run_dir=build/run/gameTest/$version/$Suite/$runId")
    if ($target.yarn) { $gradleArguments += "-Pyarn_mappings=$($target.yarn)" }
    $quotedArguments = ($gradleArguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ', '
    $gradlePath = (Join-Path $repoRoot 'gradlew.bat').Replace("'", "''")
    $jdk = $resolvedJavaHome.Replace("'", "''")
    [System.IO.File]::WriteAllLines($runner, @(
        "`$env:JAVA_HOME = '$jdk'",
        "`$env:Path = '$jdk\bin;' + `$env:Path",
        "`$gradleArguments = @($quotedArguments)",
        "& '$gradlePath' @gradleArguments",
        'exit $LASTEXITCODE'
    ), [System.Text.UTF8Encoding]::new($false))
    $gameReport = Join-Path $repoRoot "build/gametest-results/$version-$Suite.xml"
    if (Test-Path -LiteralPath $gameReport) { Remove-Item -LiteralPath $gameReport -Force }
    Write-Host "Running $Suite on $version ($($inventory.Game.Count) GameTests, $($inventory.Unit.Count) unit tests)..."
    $startedAt = Get-Date
    $process = Start-Process powershell.exe -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $runner + '"')) `
        -WorkingDirectory $repoRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru -WindowStyle Hidden
    $null = $process.Handle # Keep the native handle so Windows PowerShell retains the exit code.
    $status = 'failed'
    $exitCode = -1
    $detail = ''
    try {
        while (-not $process.HasExited -and ((Get-Date) - $startedAt).TotalSeconds -lt $TimeoutSeconds) { Start-Sleep -Seconds 1 }
        if (-not $process.HasExited) {
            $status = 'timeout'
            $detail = "Exceeded $TimeoutSeconds seconds"
        } else {
            $process.WaitForExit()
            $exitCode = $process.ExitCode
            $stdout = Get-Content -LiteralPath $stdoutLog -Raw
            $stderr = Get-Content -LiteralPath $stderrLog -Raw
            $reportsPassed = Test-FreshReports -GameReport $gameReport -UnitReports (Join-Path $repoRoot 'build/test-results/test') -StartedAt $startedAt -Inventory $inventory
            if ($exitCode -eq 0 -and $stdout -match 'BUILD SUCCESSFUL' -and "$stdout`n$stderr" -notmatch 'BUILD FAILED' -and $reportsPassed) { $status = 'passed' }
            else { $detail = 'Nonzero exit, failed build, or incomplete/failed/stale test reports' }
        }
    } catch {
        $detail = $_.Exception.Message
    } finally {
        if (-not $process.HasExited) { Stop-OwnedProcessTree -RootId $process.Id }
    }
    $results += [pscustomobject]@{
        Version = $version; Suite = $Suite; Status = $status; ExitCode = $exitCode
        DurationSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
        Detail = $detail; GameReport = $gameReport; StdoutLog = $stdoutLog; StderrLog = $stderrLog
    }
    Write-Host "[$($status.ToUpperInvariant())] $version/${Suite}: $detail"
    if ($status -ne 'passed') { break }
}

$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logRoot 'results.json') -Encoding UTF8
$results | Format-Table Version, Suite, Status, DurationSeconds, ExitCode -AutoSize
Write-Host "Logs: $logRoot"
if (@($results | Where-Object Status -NE 'passed').Count -gt 0) { exit 1 }
exit 0
