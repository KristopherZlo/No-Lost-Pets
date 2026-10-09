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
$runLatestLog = Join-Path $repoRoot "run\$Version\smoke\logs\latest.log"
$logRoot = Join-Path $repoRoot "build\tmp\smoke-client\$Version"
$stdoutLog = Join-Path $logRoot "stdout.log"
$stderrLog = Join-Path $logRoot "stderr.log"
$runnerScript = Join-Path $logRoot "run.ps1"

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
Remove-Item -LiteralPath $stdoutLog, $stderrLog, $runnerScript -Force -ErrorAction SilentlyContinue

$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"

$scriptLines = @(
    '$ErrorActionPreference = ''Stop'''
    ('& ''{0}'' -Version ''{1}'' -ModVersion ''{2}'' -Username ''{3}'' -RunName ''smoke'' {4}' -f $runClientScript.Replace("'", "''"), $Version, $effectiveModVersion.Replace("'", "''"), $Username.Replace("'", "''"), $(if ($NoDaemon) { "-NoDaemon" } else { "" }))
    'exit $LASTEXITCODE'
)
[System.IO.File]::WriteAllLines($runnerScript, $scriptLines)

$startedAt = Get-Date
$process = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ('"' + $runnerScript + '"')) `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru `
    -WindowStyle Hidden

$successPatterns = @(
    "NoLostPets initialized",
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
