param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$JavaHome,
    [string]$Username = "NoLostPetsTest",
    [string]$Uuid,
    [switch]$NoDaemon,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

$matrix = @{
    "1.21.8" = @{
        yarn = "1.21.8+build.1"
        loader = "0.19.1"
        fabric_api = "0.136.1+1.21.8"
    }
    "1.21.9" = @{
        yarn = "1.21.9+build.1"
        loader = "0.19.1"
        fabric_api = "0.134.1+1.21.9"
    }
    "1.21.10" = @{
        yarn = "1.21.10+build.3"
        loader = "0.19.1"
        fabric_api = "0.138.4+1.21.10"
    }
    "1.21.11" = @{
        yarn = "1.21.11+build.4"
        loader = "0.19.1"
        fabric_api = "0.141.3+1.21.11"
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

function Merge-MissingDirectoryContents {
    param(
        [string]$Source,
        [string]$Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        return
    }

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $Source -Force) {
        $targetPath = Join-Path $Destination $item.Name
        if ($item.PSIsContainer) {
            Merge-MissingDirectoryContents -Source $item.FullName -Destination $targetPath
            continue
        }

        if (-not (Test-Path -LiteralPath $targetPath)) {
            Copy-Item -LiteralPath $item.FullName -Destination $targetPath
        }
    }
}

function Ensure-SharedDirectoryLink {
    param(
        [string]$VersionPath,
        [string]$SharedPath,
        [string]$LegacySourcePath
    )

    New-Item -ItemType Directory -Force -Path $SharedPath | Out-Null
    Merge-MissingDirectoryContents -Source $LegacySourcePath -Destination $SharedPath

    if (Test-Path -LiteralPath $VersionPath) {
        $versionItem = Get-Item -LiteralPath $VersionPath -Force
        $isReparsePoint = ($versionItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
        if (-not $isReparsePoint) {
            Merge-MissingDirectoryContents -Source $VersionPath -Destination $SharedPath
            Remove-Item -LiteralPath $VersionPath -Recurse -Force
        } else {
            return
        }
    }

    New-Item -ItemType Junction -Path $VersionPath -Target $SharedPath | Out-Null
}

function Ensure-SharedFileLink {
    param(
        [string]$VersionPath,
        [string]$SharedPath,
        [string]$LegacySourcePath
    )

    $sharedParent = Split-Path -Parent $SharedPath
    New-Item -ItemType Directory -Force -Path $sharedParent | Out-Null

    if (-not (Test-Path -LiteralPath $SharedPath)) {
        if (Test-Path -LiteralPath $LegacySourcePath) {
            Copy-Item -LiteralPath $LegacySourcePath -Destination $SharedPath
        } elseif (Test-Path -LiteralPath $VersionPath) {
            Copy-Item -LiteralPath $VersionPath -Destination $SharedPath
        } else {
            New-Item -ItemType File -Path $SharedPath | Out-Null
        }
    }

    if (Test-Path -LiteralPath $VersionPath) {
        Remove-Item -LiteralPath $VersionPath -Force
    }

    New-Item -ItemType HardLink -Path $VersionPath -Target $SharedPath | Out-Null
}

function Initialize-SharedRuntime {
    param(
        [string]$RepoRoot,
        [string]$Version
    )

    $runRoot = Join-Path $RepoRoot "run"
    $sharedRoot = Join-Path $runRoot "shared"
    $versionRoot = Join-Path $runRoot $Version

    New-Item -ItemType Directory -Force -Path $sharedRoot, $versionRoot | Out-Null

    foreach ($directoryName in @("config", "saves", "resourcepacks", "resources")) {
        Ensure-SharedDirectoryLink `
            -VersionPath (Join-Path $versionRoot $directoryName) `
            -SharedPath (Join-Path $sharedRoot $directoryName) `
            -LegacySourcePath (Join-Path $runRoot $directoryName)
    }

    foreach ($fileName in @("options.txt", "servers.dat", "command_history.txt")) {
        Ensure-SharedFileLink `
            -VersionPath (Join-Path $versionRoot $fileName) `
            -SharedPath (Join-Path $sharedRoot $fileName) `
            -LegacySourcePath (Join-Path $runRoot $fileName)
    }
}

if (-not $matrix.ContainsKey($Version)) {
    throw "Unsupported version '$Version'. Supported versions: $($matrix.Keys -join ', ')"
}

$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$target = $matrix[$Version]
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$runDir = "run\$Version"
$effectiveUuid = if ($Uuid) { $Uuid } else { $null }

Initialize-SharedRuntime -RepoRoot $repoRoot -Version $Version

$env:JAVA_HOME = $resolvedJavaHome
$commandArgs = @(
    "runClient"
    "-Pminecraft_version=$Version"
    "-Pyarn_mappings=$($target.yarn)"
    "-Ploader_version=$($target.loader)"
    "-Pfabric_version=$($target.fabric_api)"
    "-Ploom_run_dir=$runDir"
    "-Ploom_test_username=$Username"
)

if ($effectiveUuid) {
    $commandArgs += "-Ploom_test_uuid=$effectiveUuid"
}

if ($NoDaemon) {
    $commandArgs += "--no-daemon"
}

if ($GradleArgs) {
    $commandArgs += $GradleArgs
}

& $gradle @commandArgs
exit $LASTEXITCODE
