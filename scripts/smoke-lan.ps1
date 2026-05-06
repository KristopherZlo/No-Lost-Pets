param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$JavaHome,
    [string]$ModVersion,
    [string]$WorldName = "Testing",
    [string]$HostUsername = "NLPHost",
    [string]$ClientUsername = "NLPClient",
    [int]$TimeoutSeconds = 420,
    [switch]$NoDaemon
)

$ErrorActionPreference = "Stop"

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

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
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

function Read-Text {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path) {
        return (Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue).Trim()
    }
    return ""
}

function Start-LanClientProcess {
    param(
        [string]$RunnerScript,
        [string]$RepoRoot,
        [string]$Role,
        [string]$RunName,
        [string]$Username,
        [string]$SmokeDir,
        [string]$WorldName,
        [int]$Port,
        [string]$HostUsername,
        [string]$ClientUsername,
        [string]$JavaHome,
        [string]$ModVersion,
        [bool]$NoDaemon,
        [string]$StdoutLog,
        [string]$StderrLog
    )

    $noDaemonArg = if ($NoDaemon) { " -NoDaemon" } else { "" }
    $commandLine = "& {0} -Version {1} -RunName {2} -Username {3} -JavaHome {4} -ModVersion {5} -LanSmokeRole {6} -LanSmokeDir {7} -LanSmokeWorld {8} -LanSmokePort {9} -LanSmokeHostName {10} -LanSmokePeerName {11}{12}" -f `
        (Quote-PowerShellString $RunnerScript),
        (Quote-PowerShellString $Version),
        (Quote-PowerShellString $RunName),
        (Quote-PowerShellString $Username),
        (Quote-PowerShellString $JavaHome),
        (Quote-PowerShellString $ModVersion),
        (Quote-PowerShellString $Role),
        (Quote-PowerShellString $SmokeDir),
        (Quote-PowerShellString $WorldName),
        $Port,
        (Quote-PowerShellString $HostUsername),
        (Quote-PowerShellString $ClientUsername),
        $noDaemonArg

    $scriptLines = @(
        '$ErrorActionPreference = ''Stop'''
        $commandLine
        'exit $LASTEXITCODE'
    )

    $processScript = Join-Path (Split-Path -Parent $StdoutLog) "$Role.run.ps1"
    [System.IO.File]::WriteAllLines($processScript, $scriptLines)

    return Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", $processScript) `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog `
        -PassThru `
        -WindowStyle Hidden
}

function Quote-PowerShellString {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$runClient = Join-Path $PSScriptRoot "run-client.ps1"
$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$effectiveModVersion = if ($ModVersion) { $ModVersion } else { "1.1.1" }
$port = Get-FreeTcpPort
$smokeDir = Join-Path $repoRoot "build\tmp\smoke-lan\$Version"
$worldPath = Join-Path $repoRoot ("run\shared\saves\" + $WorldName)

if (-not (Test-Path -LiteralPath (Join-Path $worldPath "level.dat"))) {
    throw "LAN smoke needs an existing local world at '$worldPath'. Create it once or pass -WorldName."
}

New-Item -ItemType Directory -Force -Path $smokeDir | Out-Null
Remove-Item -LiteralPath (Join-Path $smokeDir "port.txt"), (Join-Path $smokeDir "host.result"), (Join-Path $smokeDir "client.result") -Force -ErrorAction SilentlyContinue

$hostStdout = Join-Path $smokeDir "host.out.log"
$hostStderr = Join-Path $smokeDir "host.err.log"
$clientStdout = Join-Path $smokeDir "client.out.log"
$clientStderr = Join-Path $smokeDir "client.err.log"
Remove-Item -LiteralPath $hostStdout, $hostStderr, $clientStdout, $clientStderr -Force -ErrorAction SilentlyContinue

$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"

$hostProcess = $null
$clientProcess = $null
$startedAt = Get-Date
$clientStarted = $false

try {
    Write-Host ("Starting LAN smoke host {0} on requested port {1}" -f $Version, $port)
    $hostProcess = Start-LanClientProcess `
        -RunnerScript $runClient `
        -RepoRoot $repoRoot `
        -Role "host" `
        -RunName "$Version-lan-host" `
        -Username $HostUsername `
        -SmokeDir $smokeDir `
        -WorldName $WorldName `
        -Port $port `
        -HostUsername $HostUsername `
        -ClientUsername $ClientUsername `
        -JavaHome $resolvedJavaHome `
        -ModVersion $effectiveModVersion `
        -NoDaemon:$NoDaemon.IsPresent `
        -StdoutLog $hostStdout `
        -StderrLog $hostStderr

    while (((Get-Date) - $startedAt).TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 1

        $hostResult = Read-Text -Path (Join-Path $smokeDir "host.result")
        $clientResult = Read-Text -Path (Join-Path $smokeDir "client.result")
        $portText = Read-Text -Path (Join-Path $smokeDir "port.txt")

        if ($hostResult.StartsWith("FAIL")) {
            throw "LAN smoke host failed: $hostResult"
        }
        if ($clientResult.StartsWith("FAIL")) {
            throw "LAN smoke client failed: $clientResult"
        }

        if (-not $clientStarted -and $portText) {
            Write-Host ("Starting LAN smoke client {0} -> 127.0.0.1:{1}" -f $Version, $portText)
            $clientProcess = Start-LanClientProcess `
                -RunnerScript $runClient `
                -RepoRoot $repoRoot `
                -Role "client" `
                -RunName "$Version-lan-client" `
                -Username $ClientUsername `
                -SmokeDir $smokeDir `
                -WorldName $WorldName `
                -Port $port `
                -HostUsername $HostUsername `
                -ClientUsername $ClientUsername `
                -JavaHome $resolvedJavaHome `
                -ModVersion $effectiveModVersion `
                -NoDaemon:$NoDaemon.IsPresent `
                -StdoutLog $clientStdout `
                -StderrLog $clientStderr
            $clientStarted = $true
        }

        if ($hostResult.StartsWith("PASS")) {
            if (-not $clientResult.StartsWith("PASS")) {
                throw "LAN smoke host passed before client join was confirmed. client.result='$clientResult'"
            }
            Write-Host ("[PASS] LAN smoke {0} mod={1}" -f $Version, $effectiveModVersion)
            exit 0
        }

        if ($hostProcess -and $hostProcess.HasExited -and -not $hostResult) {
            throw "LAN smoke host exited before writing a result. See $hostStdout"
        }
        if ($clientStarted -and $clientProcess -and $clientProcess.HasExited -and -not $clientResult) {
            throw "LAN smoke client exited before writing a result. See $clientStdout"
        }
    }

    throw "LAN smoke timed out for $Version after ${TimeoutSeconds}s. See $hostStdout and $clientStdout."
} finally {
    if ($hostProcess) {
        Stop-ProcessTree -RootIds @($hostProcess.Id)
    }
    if ($clientProcess) {
        Stop-ProcessTree -RootIds @($clientProcess.Id)
    }
}
