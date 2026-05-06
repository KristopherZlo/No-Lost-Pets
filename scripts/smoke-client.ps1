param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$JavaHome,
    [string]$ModVersion,
    [string]$Username = "NoLostPetsSmoke",
    [int]$TimeoutSeconds = 180,
    [switch]$NoDaemon
)

$ErrorActionPreference = "Stop"

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

function Get-StaleClientSmokeProcesses {
    param(
        [string]$RepoRoot,
        [int]$CurrentPid
    )

    $repoPattern = [regex]::Escape($RepoRoot)
    $targetNames = @("powershell.exe", "cmd.exe", "java.exe")

    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessId -ne $CurrentPid -and
        $targetNames -contains $_.Name -and
        $_.CommandLine -and
        $_.CommandLine -match $repoPattern -and
        $_.CommandLine -match "runClient|run-client.ps1|gradlew|gradle-wrapper"
    }

    return @($processes | Select-Object -ExpandProperty ProcessId -Unique)
}

function Read-FileIfExists {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path) {
        return Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
    }
    return ""
}

if (-not $matrix.ContainsKey($Version)) {
    throw "Unsupported version '$Version'. Supported versions: $($matrix.Keys -join ', ')"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$target = $matrix[$Version]
$effectiveModVersion = if ($ModVersion) { $ModVersion } else { $target.mod_version }
$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$runClientScript = Join-Path $PSScriptRoot "run-client.ps1"
$runLatestLog = Join-Path $repoRoot "run\$Version\logs\latest.log"
$logRoot = Join-Path $repoRoot "build\tmp\smoke-client\$Version"
$stdoutLog = Join-Path $logRoot "stdout.log"
$stderrLog = Join-Path $logRoot "stderr.log"
$runnerScript = Join-Path $logRoot "run.ps1"

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
Remove-Item -LiteralPath $stdoutLog, $stderrLog, $runnerScript -Force -ErrorAction SilentlyContinue

$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"

$staleIds = Get-StaleClientSmokeProcesses -RepoRoot $repoRoot -CurrentPid $PID
if ($staleIds.Count -gt 0) {
    Write-Host ("Cleaning stale runClient smoke processes: " + ($staleIds -join ", "))
    Stop-ProcessTree -RootIds $staleIds
}

$scriptLines = @(
    '$ErrorActionPreference = ''Stop'''
    ('& ''{0}'' -Version ''{1}'' -ModVersion ''{2}'' -Username ''{3}'' {4}' -f $runClientScript, $Version, $effectiveModVersion, $Username, $(if ($NoDaemon) { "-NoDaemon" } else { "" }))
    'exit $LASTEXITCODE'
)
[System.IO.File]::WriteAllLines($runnerScript, $scriptLines)

$startedAt = Get-Date
$process = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", $runnerScript) `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru `
    -WindowStyle Hidden

$successPatterns = @(
    "NoLostPets initialized",
    "Backend library: LWJGL",
    "minecraft:textures/atlas/blocks.png-atlas"
)
$failurePatterns = @(
    "BUILD FAILED",
    "Minecraft has crashed",
    "Reported exception thrown",
    "Failed to start Minecraft"
)

try {
    while (((Get-Date) - $startedAt).TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 1

        $stdoutText = Read-FileIfExists -Path $stdoutLog
        $stderrText = Read-FileIfExists -Path $stderrLog
        $latestText = ""
        if (Test-Path -LiteralPath $runLatestLog) {
            $latestItem = Get-Item -LiteralPath $runLatestLog
            if ($latestItem.LastWriteTime -ge $startedAt.AddSeconds(-2)) {
                $latestText = Read-FileIfExists -Path $runLatestLog
            }
        }
        $combinedText = $stdoutText + "`n" + $stderrText + "`n" + $latestText

        foreach ($pattern in $failurePatterns) {
            if ($combinedText -match [regex]::Escape($pattern)) {
                throw "Client smoke failed for ${Version}: found '$pattern'. See $stdoutLog and $runLatestLog."
            }
        }

        $allSuccessPatternsFound = $true
        foreach ($pattern in $successPatterns) {
            if ($combinedText -notmatch [regex]::Escape($pattern)) {
                $allSuccessPatternsFound = $false
                break
            }
        }

        if ($allSuccessPatternsFound) {
            Write-Host ("[PASS] client smoke {0} mod={1}" -f $Version, $effectiveModVersion)
            Stop-ProcessTree -RootIds @($process.Id)
            exit 0
        }

        if ($process.HasExited) {
            $process.WaitForExit()
            throw "Client smoke exited before success for $Version with code $($process.ExitCode). See $stdoutLog and $runLatestLog."
        }
    }

    throw "Client smoke timed out for $Version after ${TimeoutSeconds}s. See $stdoutLog and $runLatestLog."
} finally {
    Stop-ProcessTree -RootIds @($process.Id)
}
