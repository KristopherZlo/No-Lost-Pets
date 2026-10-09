param(
    [string]$LegacyProject = (Join-Path $PSScriptRoot '../../pet-recall'),
    [string]$BaselineJar,
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
if (-not $BaselineJar) { $BaselineJar = Join-Path $legacy 'dist/1.21x/NoLostPets-fabric-1.21.11.jar' }
$BaselineJar = (Resolve-Path -LiteralPath $BaselineJar).Path
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must be a simple directory name.' }
$root = Join-Path $project "build/world-upgrades/$RunId"
if (Test-Path -LiteralPath $root) { throw "Run directory already exists: $root. Choose a new RunId." }
New-Item -ItemType Directory -Path $root | Out-Null
$cache = Join-Path $project 'build/upgrade-launchers'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$results = [Collections.Generic.List[object]]::new()

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
    $native = $Version -ne '1.21.11'
    $loader = if ($native) { (Get-MinecraftTarget $Version).loader } else { '0.18.2' }
    $api = if ($native) { (Get-MinecraftTarget $Version).fabric_api } else { '0.141.3+1.21.11' }
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
    $label = "$Version-$Phase"
    $report = Join-Path $Directory "$label.json"
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
        if ($process.ExitCode -ne 0) { throw "Server exited with $($process.ExitCode): $label. See its logs." }
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
            modSha256 = (Get-FileHash -LiteralPath $ModJar -Algorithm SHA256).Hash; report = $report; checks = $data.checks })
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

Invoke-HelperBuild $legacy $Java21Home @('remapUpgradeTestJar', '-Pminecraft_version=1.21.11',
    '-Pyarn_mappings=1.21.11+build.4', '-Pfabric_version=0.141.3+1.21.11') (Join-Path $root 'legacy-helper-build.log')
$legacyHelper = Get-ChildItem -LiteralPath (Join-Path $legacy 'build/libs') -Filter '*-upgrade-test.jar' | Select-Object -First 1 -ExpandProperty FullName
$seed = Join-Path $root 'seed'
$seedReport = Invoke-WorldServer $seed '1.21.11' 'seed' $BaselineJar $legacyHelper ''
$seedWorld = Join-Path $seed 'world'
$seedFingerprint = Get-WorldFingerprint $seedWorld

# Copy only the offline world, with no prefilled new index and no replacement entity NBT.
foreach ($route in @('direct', 'sequential')) {
    $directory = Join-Path $root $route
    New-Item -ItemType Directory -Path $directory | Out-Null
    Copy-Item -LiteralPath $seedWorld -Destination (Join-Path $directory 'world') -Recurse
    $expected = $seedReport
    $versions = if ($route -eq 'direct') { @('26.3') } else { $MinecraftVersions }
    foreach ($version in $versions) {
        Invoke-HelperBuild $project $Java25Home (@('upgradeTestJar') + (Get-MinecraftGradleArguments $version)) (Join-Path $root "helper-$route-$version.log")
        $target = Get-MinecraftTarget $version
        $mod = Join-Path $project "build/candidates/$version/NoLostPets-$($target.mod_version)+mc$version.jar"
        $helper = Join-Path $project "build/libs/NoLostPets-$($target.mod_version)+mc$version-upgrade-test.jar"
        $expected = Invoke-WorldServer $directory $version 'verify' $mod $helper $expected
        $null = Invoke-WorldServer $directory $version 'restart' $mod $helper $expected
        if ($version -eq '26.3') {
            $expected = Invoke-WorldServer $directory $version 'recall' $mod $helper $expected
            # A new directory also ensures report names and loader caches cannot mask a missing restart.
            $restart = Join-Path $root "$route-after-recall"
            New-Item -ItemType Directory -Path $restart | Out-Null
            Copy-Item -LiteralPath (Join-Path $directory 'world') -Destination (Join-Path $restart 'world') -Recurse
            $null = Invoke-WorldServer $restart $version 'restart' $mod $helper $expected
        }
    }
}
if ((Get-WorldFingerprint $seedWorld) -cne $seedFingerprint) { throw 'The original saved world changed during upgrade verification.' }
$summary = [pscustomobject]@{ passed = $true; baseline = $BaselineJar; seedWorldUnchanged = $true; stages = $results }
[IO.File]::WriteAllText((Join-Path $root 'results.json'), ($summary | ConvertTo-Json -Depth 12), $utf8)
Write-Host "Saved-world upgrade verification passed. Reports: $root"
