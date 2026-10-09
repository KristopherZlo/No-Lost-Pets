param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$JavaHome,
    [string]$ModVersion,
    [string]$RunName,
    [string]$Username = "NoLostPetsTest",
    [string]$Uuid,
    [string]$LanSmokeRole,
    [string]$LanSmokeDir,
    [string]$LanSmokeWorld,
    [int]$LanSmokePort = 0,
    [string]$LanSmokeHostName,
    [string]$LanSmokePeerName,
    [switch]$NoDaemon,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "versions.ps1")

if (-not $matrix.ContainsKey($Version)) {
    throw "Unsupported version '$Version'. Supported versions: $($matrix.Keys -join ', ')"
}

$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$target = $matrix[$Version]
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$effectiveRunName = if ($RunName) { $RunName } else { "client" }
if ($effectiveRunName -notmatch "^[A-Za-z0-9][A-Za-z0-9_.-]*$") { throw "Invalid run name" }
$runDir = "run\$Version\$effectiveRunName"
$effectiveUuid = if ($Uuid) { $Uuid } else { $null }
$effectiveModVersion = if ($ModVersion) { $ModVersion } else { $target.mod_version }

New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot $runDir) | Out-Null

$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"
$commandArgs = @(
    "runClient"
    "-Pminecraft_version=$Version"
    "-Ploader_version=$($target.loader)"
    "-Pfabric_version=$($target.fabric_api)"
    "-Pmod_version=$effectiveModVersion"
    "-Ploom_run_dir=$runDir"
    "-Ploom_test_username=$Username"
)

if ($effectiveUuid) {
    $commandArgs += "-Ploom_test_uuid=$effectiveUuid"
}

if ($LanSmokeRole) {
    $commandArgs += "-Pnolostpets_lan_smoke_role=$LanSmokeRole"
}
if ($LanSmokeDir) {
    $commandArgs += "-Pnolostpets_lan_smoke_dir=$LanSmokeDir"
}
if ($LanSmokeWorld) {
    $commandArgs += "-Pnolostpets_lan_smoke_world=$LanSmokeWorld"
}
if ($LanSmokePort -gt 0) {
    $commandArgs += "-Pnolostpets_lan_smoke_port=$LanSmokePort"
}
if ($LanSmokeHostName) {
    $commandArgs += "-Pnolostpets_lan_smoke_host_name=$LanSmokeHostName"
}
if ($LanSmokePeerName) {
    $commandArgs += "-Pnolostpets_lan_smoke_peer_name=$LanSmokePeerName"
}

if ($NoDaemon) {
    $commandArgs += "--no-daemon"
}

if ($GradleArgs) {
    $commandArgs += $GradleArgs
}

Push-Location $repoRoot
try {
    $gradleErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle @commandArgs
    } finally { $ErrorActionPreference = $gradleErrorAction }
    $runExit = $LASTEXITCODE
} finally { Pop-Location }
exit $runExit
