param(
    [string]$LegacyProject = (Join-Path $PSScriptRoot '../../pet-recall'),
    [string]$BaselineJar,
    [string[]]$BaselineVersions = @('1.21.8', '1.21.9', '1.21.10', '1.21.11'),
    [string[]]$TargetVersions = @('26.1', '26.1.1', '26.1.2', '26.2', '26.3'),
    [switch]$SkipCrashTests,
    [switch]$Resume,
    [string]$Java21Home = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot',
    [string]$Java25Home,
    [string]$GradleUserHome,
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'versions.ps1')
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$legacy = [IO.Path]::GetFullPath($LegacyProject)
$Java25Home = Resolve-JavaHome $Java25Home
if (-not $GradleUserHome) { $GradleUserHome = Join-Path $legacy '.gradle-user-home' }
if ($BaselineJar) { $BaselineJar = (Resolve-Path -LiteralPath $BaselineJar).Path; if ($BaselineVersions -ne '1.21.11') { throw '-BaselineJar requires -BaselineVersions 1.21.11' } }
$legacyTargets = @{
    '1.21.8' = @{ yarn='1.21.8+build.1'; loader='0.18.2'; fabric_api='0.136.1+1.21.8' }
    '1.21.9' = @{ yarn='1.21.9+build.1'; loader='0.18.2'; fabric_api='0.134.1+1.21.9' }
    '1.21.10' = @{ yarn='1.21.10+build.3'; loader='0.18.2'; fabric_api='0.138.4+1.21.10' }
    '1.21.11' = @{ yarn='1.21.11+build.4'; loader='0.18.2'; fabric_api='0.141.3+1.21.11' }
}
foreach ($version in $BaselineVersions) { if (-not $legacyTargets.ContainsKey($version)) { throw "Unsupported baseline: $version" } }
foreach ($version in $TargetVersions) { $null = Get-MinecraftTarget $version }
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must be a simple directory name.' }
$BaselineVersions = @($BaselineVersions | Sort-Object {[version]$_} -Unique)
$TargetVersions = @($TargetVersions | Sort-Object {[version]$_} -Unique)
if (-not $BaselineVersions.Count -or -not $TargetVersions.Count) { throw 'At least one baseline and target are required.' }
$root = Join-Path $project "build/world-upgrades/$RunId"
$progressPath = Join-Path $root 'progress.json'
if ($Resume) {
    if (-not (Test-Path -LiteralPath $progressPath)) { throw "No saved progress to resume: $progressPath" }
} else {
    if (Test-Path -LiteralPath $root) { throw "Run directory already exists: $root. Choose a new RunId or use -Resume." }
    New-Item -ItemType Directory -Path $root | Out-Null
}
$cache = Join-Path $project 'build/upgrade-launchers'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$results = [Collections.Generic.List[object]]::new()
$resumeStages = @()
$seedFingerprints = @{}
if ($Resume) {
    $progress = Get-Content -LiteralPath $progressPath -Raw | ConvertFrom-Json
    if (($progress.baselines -join ',') -cne ($BaselineVersions -join ',') -or
        ($progress.targets -join ',') -cne ($TargetVersions -join ',') -or
        $progress.crashTests -ne (-not $SkipCrashTests)) { throw 'Resume requires the original version matrix and crash-test setting.' }
    $resumeStages = @($progress.stages)
    foreach ($property in $progress.seedFingerprints.PSObject.Properties) { $seedFingerprints[$property.Name] = $property.Value }
}

function Save-Progress {
    $progress = [pscustomobject]@{ baselines = $BaselineVersions; targets = $TargetVersions;
        crashTests = -not $SkipCrashTests; seedFingerprints = $seedFingerprints; stages = $results }
    [IO.File]::WriteAllText("$progressPath.tmp", ($progress | ConvertTo-Json -Depth 12), $utf8)
    Move-Item -LiteralPath "$progressPath.tmp" -Destination $progressPath -Force
}

function Invoke-HelperBuild([string]$Directory, [string]$Jdk, [string[]]$Arguments, [string]$Log) {
    $previousJava = $env:JAVA_HOME
    $previousPath = $env:Path
    Push-Location $Directory
    try {
        $env:JAVA_HOME = $Jdk
        $env:Path = "$Jdk/bin;$previousPath"
        $ErrorActionPreference = 'Continue'
        & './gradlew.bat' @Arguments --no-daemon --console=plain -g $GradleUserHome *> $Log
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -ne 0) { throw "Helper build failed ($code). See $Log" }
    } finally {
        Pop-Location
        $env:JAVA_HOME = $previousJava
        $env:Path = $previousPath
    }
}

function Get-FabricApi([string]$Version) {
    $directory = Join-Path $GradleUserHome "caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/$Version"
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter "fabric-api-$Version.jar" | Select-Object -First 1
    if (-not $jar) { throw "Fabric API is missing from Gradle cache: $Version" }
    return $jar.FullName
}

function Invoke-WorldServer([string]$Directory, [string]$Version, [string]$Phase,
        [string]$ModJar, [string]$HelperJar, [string]$Expected) {
    $label = "$Version-$Phase-$($results.Count)"
    $report = Join-Path $Directory "$label.json"
    $modHash = (Get-FileHash -LiteralPath $ModJar -Algorithm SHA256).Hash
    $helperHash = (Get-FileHash -LiteralPath $HelperJar -Algorithm SHA256).Hash
    $expectedHash = if ($Expected) { (Get-FileHash -LiteralPath $Expected -Algorithm SHA256).Hash } else { '' }
    if ($results.Count -lt $resumeStages.Count) {
        $saved = $resumeStages[$results.Count]
        if ($saved.version -cne $Version -or $saved.phase -cne $Phase -or $saved.report -ine $report -or
            $saved.modSha256 -ine $modHash -or $saved.helperSha256 -ine $helperHash -or
            $saved.expectedSha256 -ine $expectedHash -or
            $saved.reportSha256 -ine (Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash) {
            throw "Saved stage or its inputs changed: $label. Start a new run."
        }
        $data = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
        if (-not $data.passed -or $data.phase -cne $Phase -or $data.minecraft -cne $Version -or @($data.records).Count -ne 6) {
            throw "Saved report is invalid: $label"
        }
        $results.Add($saved)
        Write-Host "Reused verified stage $label"
        return $report
    }
    $native = -not $Version.StartsWith('1.21.')
    $target = if ($native) { Get-MinecraftTarget $Version } else { $legacyTargets[$Version] }
    $loader = $target.loader
    $api = $target.fabric_api
    $jdk = if ($native) { $Java25Home } else { $Java21Home }
    $launcher = Join-Path $cache "fabric-server-$Version-$loader.jar"
    if (-not (Test-Path -LiteralPath $launcher)) {
        Invoke-WebRequest "https://meta.fabricmc.net/v2/versions/loader/$Version/$loader/1.1.2/server/jar" -OutFile $launcher
    }
    $serverJar = Join-Path $GradleUserHome "caches/fabric-loom/$Version/minecraft-server.jar"
    if (-not (Test-Path -LiteralPath $serverJar)) { throw "Minecraft server is missing from Gradle cache: $Version" }
    New-Item -ItemType Directory -Force -Path (Join-Path $Directory 'mods') | Out-Null
    Copy-Item -LiteralPath $ModJar -Destination (Join-Path $Directory 'mods/NoLostPets.jar')
    Copy-Item -LiteralPath $HelperJar -Destination (Join-Path $Directory 'mods/UpgradeTest.jar')
    Copy-Item -LiteralPath (Get-FabricApi $api) -Destination (Join-Path $Directory 'mods/FabricAPI.jar')
    [IO.File]::WriteAllText((Join-Path $Directory 'eula.txt'), "eula=true`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $Directory 'server.properties'), @"
level-name=world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=120000
pause-when-empty-seconds=-1
"@, $utf8)
    if (Test-Path -LiteralPath $report) { throw "Refusing to reuse a report: $report" }
    $arguments = @('-Xmx2G', '-Xms512M', "`"-Dfabric.installer.server.gameJar=$serverJar`"",
        "`"-Dnolostpets.upgrade.phase=$Phase`"", "`"-Dnolostpets.upgrade.report=$report`"")
    if ($Expected) { $arguments += "`"-Dnolostpets.upgrade.expected=$Expected`"" }
    $arguments += @('-jar', "`"$launcher`"", 'nogui')
    Write-Host "Starting $label in $Directory"
    $started = [DateTime]::UtcNow
    $process = Start-Process -FilePath (Join-Path $jdk 'bin/java.exe') -ArgumentList $arguments -WorkingDirectory $Directory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Directory "$label.log") `
        -RedirectStandardError (Join-Path $Directory "$label.err.log")
    $null = $process.Handle
    try {
        while (-not $process.WaitForExit(1000)) {
            if (([DateTime]::UtcNow - $started).TotalMinutes -gt 10) {
                throw "Server timed out: $label. See its logs."
            }
        }
        $process.Refresh()
        $expectedExit = if ($Phase.StartsWith("crash-")) { 137 } else { 0 }
        if ($process.ExitCode -ne $expectedExit) { throw "Server exited with $($process.ExitCode): $label. See its logs." }
        if (-not (Test-Path -LiteralPath $report)) { throw "No clean-shutdown report: $label" }
        $data = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
        if (-not $data.passed -or $data.phase -ne $Phase -or $data.minecraft -ne $Version -or @($data.records).Count -ne 6 -or
                (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started) {
            throw "World upgrade verification failed: $label; $($data.failure). See $report"
        }
        $serverLog = Get-Content -LiteralPath (Join-Path $Directory "$label.log") -Raw
        if ($serverLog -match '\[(?:[^\]]*/)?ERROR\]|UUID of added entity already exists|[Dd]uplicat(?:e|ed) (?:entity )?UUID') {
            throw "Server logged an error or duplicate UUID: $label. See its log."
        }
        $results.Add([pscustomobject]@{ version = $Version; phase = $Phase; modVersion = $data.modVersion;
            modSha256 = $modHash; helperSha256 = $helperHash; expectedSha256 = $expectedHash;
            reportSha256 = (Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash; report = $report; checks = $data.checks })
        Save-Progress
        Write-Host "Passed $label"
        return $report
    } finally {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}

function Get-WorldFingerprint([string]$World) {
    $files = Get-ChildItem -LiteralPath $World -Recurse -File | Sort-Object FullName
    return ($files | ForEach-Object { $_.FullName.Substring($World.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n"
}

# Build exactly the production JARs that this run will load; never reuse a stale candidate.
$nativeHelpers = @{}
$nativeMods = @{}
foreach ($version in $TargetVersions) {
    Invoke-HelperBuild $project $Java25Home (@('stageArtifacts', 'upgradeTestJar') + (Get-MinecraftGradleArguments $version)) (Join-Path $root "build-$version.log")
    $target = Get-MinecraftTarget $version
    $nativeMods[$version] = Join-Path $project "build/candidates/$version/NoLostPets-$($target.mod_version)+mc$version.jar"
    $helperCopy = Join-Path $root "helper-$version.jar"
    Copy-Item -LiteralPath (Join-Path $project "build/libs/NoLostPets-$($target.mod_version)+mc$version-upgrade-test.jar") -Destination $helperCopy
    $nativeHelpers[$version] = $helperCopy
}
$legacyHelpers = @{}
$legacyMods = @{}
$legacyBaselines = @{}
$legacyModVersion = ((Get-Content -LiteralPath (Join-Path $legacy 'gradle.properties') |
    Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', '').Trim()
if ($legacyModVersion -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Invalid legacy mod version.' }
foreach ($version in $BaselineVersions) {
    $target = $legacyTargets[$version]
    Invoke-HelperBuild $legacy $Java21Home @('stageArtifacts', 'remapUpgradeTestJar', "-Pminecraft_version=$version",
        "-Pyarn_mappings=$($target.yarn)", "-Pfabric_version=$($target.fabric_api)",
        "-Pmod_version=$legacyModVersion") (Join-Path $root "legacy-build-$version.log")
    $legacyHelpers[$version] = Join-Path $root "helper-$version.jar"
    Copy-Item -LiteralPath (Join-Path $legacy "build/libs/NoLostPets-$legacyModVersion+mc$version-upgrade-test.jar") -Destination $legacyHelpers[$version]
    $archive = Join-Path $legacy "dist/1.21x/NoLostPets-fabric-$version.jar"
    $legacyBaselines[$version] = if ($BaselineJar) { $BaselineJar } else { $archive }
    if (-not (Test-Path -LiteralPath $legacyBaselines[$version])) { throw "Historical baseline JAR missing: $($legacyBaselines[$version])" }
    $legacyMods[$version] = Join-Path $legacy "dist/1.21x/$version/NoLostPets-$legacyModVersion+mc$version.jar"
    if (-not (Test-Path -LiteralPath $legacyMods[$version])) { throw "Fresh legacy candidate JAR missing: $($legacyMods[$version])" }
}

function Copy-TestWorld([string]$Source, [string]$Directory) {
    if (Test-Path -LiteralPath $Directory) {
        if ($Resume -and (Test-Path -LiteralPath (Join-Path $Directory 'world')) -and
            @($resumeStages | Where-Object { (Split-Path -Parent $_.report) -ieq $Directory }).Count -gt 0) { return }
        throw "Fixture directory already exists without verified progress: $Directory"
    }
    New-Item -ItemType Directory -Path $Directory | Out-Null
    Copy-Item -LiteralPath $Source -Destination (Join-Path $Directory 'world') -Recurse
}

foreach ($baseline in $BaselineVersions) {
    $seed = Join-Path $root "seed-$baseline"
    $seedReport = Invoke-WorldServer $seed $baseline 'seed' $legacyBaselines[$baseline] $legacyHelpers[$baseline] ''
    $seedWorld = Join-Path $seed 'world'
    $fingerprint = Get-WorldFingerprint $seedWorld
    if ($seedFingerprints.ContainsKey($seedWorld) -and $seedFingerprints[$seedWorld] -cne $fingerprint) {
        throw "Original seed world changed: $seedWorld"
    }
    $seedFingerprints[$seedWorld] = $fingerprint
    if ($results.Count -ge $resumeStages.Count) { Save-Progress }

    # Updating only the mod must preserve the old save on the same Minecraft version too.
    $modUpdate = Join-Path $root "$baseline-mod-update"
    Copy-TestWorld $seedWorld $modUpdate
    $modExpected = Invoke-WorldServer $modUpdate $baseline 'verify' $legacyMods[$baseline] $legacyHelpers[$baseline] $seedReport
    $null = Invoke-WorldServer $modUpdate $baseline 'restart' $legacyMods[$baseline] $legacyHelpers[$baseline] $modExpected

    # Direct upgrades start with independent copies of each original world.
    foreach ($version in $TargetVersions) {
        $directory = Join-Path $root "$baseline-direct-$version"
        Copy-TestWorld $seedWorld $directory
        $expected = Invoke-WorldServer $directory $version 'verify' $nativeMods[$version] $nativeHelpers[$version] $seedReport
        $null = Invoke-WorldServer $directory $version 'restart' $nativeMods[$version] $nativeHelpers[$version] $expected
        $expected = Invoke-WorldServer $directory $version 'auto-after-upgrade' $nativeMods[$version] $nativeHelpers[$version] $expected
        $null = Invoke-WorldServer $directory $version 'restart' $nativeMods[$version] $nativeHelpers[$version] $expected
        $expected = Invoke-WorldServer $directory $version 'recall' $nativeMods[$version] $nativeHelpers[$version] $expected
        $null = Invoke-WorldServer $directory $version 'restart' $nativeMods[$version] $nativeHelpers[$version] $expected

        if (-not $SkipCrashTests) {
            foreach ($crashPhase in @('crash-before-save', 'crash-after-checkpoint')) {
                $crash = Join-Path $root "$baseline-$version-$crashPhase"
                Copy-TestWorld $seedWorld $crash
                $checkpoint = Invoke-WorldServer $crash $version $crashPhase $nativeMods[$version] $nativeHelpers[$version] $seedReport
                $recovered = Invoke-WorldServer $crash $version 'recover-crash' $nativeMods[$version] $nativeHelpers[$version] $checkpoint
                $null = Invoke-WorldServer $crash $version 'restart' $nativeMods[$version] $nativeHelpers[$version] $recovered
            }
        }
    }

    $directory = Join-Path $root "$baseline-sequential"
    Copy-TestWorld $seedWorld $directory
    $expected = $seedReport
    # Include in-place legacy world upgrades before the switch to the 26.x branch.
    foreach ($version in @($BaselineVersions | Where-Object { [version]$_ -gt [version]$baseline } | Sort-Object {[version]$_})) {
        $expected = Invoke-WorldServer $directory $version 'verify' $legacyMods[$version] $legacyHelpers[$version] $expected
        $null = Invoke-WorldServer $directory $version 'restart' $legacyMods[$version] $legacyHelpers[$version] $expected
    }
    foreach ($version in $TargetVersions) {
        $expected = Invoke-WorldServer $directory $version 'verify' $nativeMods[$version] $nativeHelpers[$version] $expected
        $null = Invoke-WorldServer $directory $version 'restart' $nativeMods[$version] $nativeHelpers[$version] $expected
    }
    $final = $TargetVersions[-1]
    $expected = Invoke-WorldServer $directory $final 'recall' $nativeMods[$final] $nativeHelpers[$final] $expected
    $null = Invoke-WorldServer $directory $final 'restart' $nativeMods[$final] $nativeHelpers[$final] $expected
}
foreach ($seedWorld in $seedFingerprints.Keys) {
    if ((Get-WorldFingerprint $seedWorld) -cne $seedFingerprints[$seedWorld]) { throw "Original seed world changed: $seedWorld" }
}
$summary = [pscustomobject]@{ passed = $true; baselines = $BaselineVersions; targets = $TargetVersions;
    crashTests = -not $SkipCrashTests; seedWorldUnchanged = $true; stages = $results }
[IO.File]::WriteAllText((Join-Path $root 'results.json'), ($summary | ConvertTo-Json -Depth 12), $utf8)
Write-Host "Saved-world upgrade verification passed. Reports: $root"
