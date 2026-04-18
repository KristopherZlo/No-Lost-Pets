param(
    [string]$JavaHome,
    [switch]$NoDaemon,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
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

$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"

$env:JAVA_HOME = $resolvedJavaHome
$commandArgs = @("clean", "build")

if ($NoDaemon) {
    $commandArgs += "--no-daemon"
}

if ($GradleArgs) {
    $commandArgs += $GradleArgs
}

& $gradle @commandArgs
exit $LASTEXITCODE
