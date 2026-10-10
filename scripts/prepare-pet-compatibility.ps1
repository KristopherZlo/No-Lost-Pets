param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$JavaHome,
    [string]$GradleUserHome,
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($RunTests -and ($Version.StartsWith('26.') -ne (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'versions.ps1')))) {
    throw 'Run 1.21.x profiles from the main checkout and 26.x profiles from the mc-26x checkout.'
}
$catalog = Get-Content (Join-Path $PSScriptRoot 'pet-compatibility-matrix.json') -Raw | ConvertFrom-Json
$profile = $catalog.projects | Where-Object slug -EQ $Project | Select-Object -First 1
if (-not $profile) { throw "Unknown companion project: $Project" }
$versionId = $profile.versions.PSObject.Properties[$Version].Value
if (-not $versionId) { throw "No pinned Fabric release of $Project supports Minecraft $Version; this combination cannot be counted as tested." }
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must be a simple directory name.' }
$run = Join-Path $projectRoot "build/compatibility/$Version/$Project-$RunId"
if (Test-Path -LiteralPath $run) { throw "Profile already exists: $run. Use a new RunId." }
$mods = Join-Path $run 'mods'
New-Item -ItemType Directory -Path $mods | Out-Null
$headers = @{'User-Agent'='NoLostPets-compatibility-tests/1.0'}
$installed = @{}
$modIds = [Collections.Generic.List[string]]::new()
$lock = [Collections.Generic.List[object]]::new()
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Install-Version([string]$Id) {
    if ($Id -notmatch '^[a-zA-Z0-9]{8}$') { throw "Invalid Modrinth version ID: $Id" }
    $data = Invoke-RestMethod "https://api.modrinth.com/v2/version/$Id" -Headers $headers
    if ($data.loaders -notcontains 'fabric' -or $data.game_versions -notcontains $Version) {
        throw "Dependency $Id is not a Fabric release for Minecraft $Version."
    }
    if ($installed.ContainsKey($data.project_id)) {
        if ($installed[$data.project_id] -ne $Id) { throw "Conflicting dependency versions for $($data.project_id)." }
        return
    }
    $installed[$data.project_id] = $Id
    foreach ($dependency in $data.dependencies | Where-Object dependency_type -EQ 'required') {
        # The Gradle run already supplies the exact Fabric API from the Minecraft target matrix.
        if ($dependency.project_id -eq 'P7dR8mSH') { continue }
        $dependencyId = $dependency.version_id
        if (-not $dependencyId) {
            if (-not $dependency.project_id) { throw "Unresolvable dependency in $Id." }
            $releases = Invoke-RestMethod "https://api.modrinth.com/v2/project/$($dependency.project_id)/version?loaders=%5B%22fabric%22%5D" -Headers $headers
            $chosen = $releases | Where-Object {$_.game_versions -contains $Version -and $_.version_type -eq 'release'} |
                Sort-Object date_published -Descending | Select-Object -First 1
            if (-not $chosen) { throw "No compatible required dependency for $($dependency.project_id)." }
            $dependencyId = $chosen.id
        }
        Install-Version $dependencyId
    }
    $artifact = $data.files | Where-Object primary | Select-Object -First 1
    if (-not $artifact) { $artifact = $data.files | Select-Object -First 1 }
    $filename = [IO.Path]::GetFileName($artifact.filename)
    $uri = [Uri]$artifact.url
    if ($filename -ne $artifact.filename -or -not $filename.EndsWith('.jar') -or
            $uri.Scheme -ne 'https' -or $uri.Host -ne 'cdn.modrinth.com' -or -not $artifact.hashes.sha512) {
        throw "Unexpected Modrinth artifact for $Id."
    }
    $localFilename = "$Id.jar"
    $destination = Join-Path $mods $localFilename
    if (Test-Path -LiteralPath $destination) { throw "Duplicate artifact filename: $localFilename" }
    Invoke-WebRequest $uri.AbsoluteUri -Headers $headers -OutFile $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash -ine $artifact.hashes.sha512) {
        throw "Checksum mismatch for $filename."
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($destination)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        if (-not $entry) { throw "Fabric metadata missing in $filename." }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $modIds.Add($metadata.id)
    } finally { $zip.Dispose() }
    $lock.Add([pscustomobject]@{projectId=$data.project_id; versionId=$Id; version=$data.version_number;
        file=$localFilename; originalFile=$filename; sha512=$artifact.hashes.sha512; url=$artifact.url; modId=$metadata.id})
}

Install-Version $versionId
$utf8 = [Text.UTF8Encoding]::new($false)
$specFile = Join-Path $run 'fixture.json'
$spec = [pscustomobject]@{slug=$profile.slug; minecraft=$Version; requiredMods=@($modIds);
    entities=@($profile.entities); preservedKeys=@($profile.preservedKeys)}
[IO.File]::WriteAllText($specFile, ($spec | ConvertTo-Json -Depth 8), $utf8)
[IO.File]::WriteAllText((Join-Path $run 'mods-lock.json'), ($lock.ToArray() | ConvertTo-Json -Depth 8), $utf8)
Write-Host "Pinned compatibility profile prepared: $run"
if (-not $RunTests) { return }

$native = $Version.StartsWith('26.')
if ($native) {
    . (Join-Path $PSScriptRoot 'versions.ps1')
    $JavaHome = Resolve-JavaHome $JavaHome
    $targetArgs = Get-MinecraftGradleArguments $Version
} else {
    $targets = @{
        '1.21.8'=@{yarn='1.21.8+build.1';api='0.136.1+1.21.8'}
        '1.21.9'=@{yarn='1.21.9+build.1';api='0.134.1+1.21.9'}
        '1.21.10'=@{yarn='1.21.10+build.3';api='0.138.4+1.21.10'}
        '1.21.11'=@{yarn='1.21.11+build.4';api='0.141.3+1.21.11'}
    }
    if (-not $targets.ContainsKey($Version)) { throw "Unsupported legacy target: $Version" }
    if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome pointing to JDK 21.' }
    $target = $targets[$Version]
    $targetArgs = @("-Pminecraft_version=$Version", "-Pyarn_mappings=$($target.yarn)", "-Pfabric_version=$($target.api)")
}
if (-not $GradleUserHome) { $GradleUserHome = if ($native) { Join-Path $projectRoot '../pet-recall/.gradle-user-home' } else { Join-Path $projectRoot '.gradle-user-home' } }
$previousJava = $env:JAVA_HOME
$previousPath = $env:Path
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome/bin;$previousPath"
    $started = [DateTime]::UtcNow
    & './gradlew.bat' runGameTest @targetArgs "-Ppet_compat_mods=$mods" "-Ppet_compat_spec=$specFile" `
        "-Ploom_run_dir=$run/server" --no-daemon --console=plain -g $GradleUserHome
    if ($LASTEXITCODE -ne 0) { throw "Compatibility tests failed ($LASTEXITCODE). Profile: $run" }
    $report = Join-Path $run 'gametest-results.xml'
    if (-not (Test-Path -LiteralPath $report) -or (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started) {
        throw 'A fresh compatibility test report is required; process exit zero alone is not sufficient.'
    }
    [xml]$xml = Get-Content -LiteralPath $report -Raw
    $cases = @($xml.SelectNodes("//testcase[@name='pet_recall_compat_test:popular_pet_compatibility_game_tests_installed_companions_keep_ownership_inventory_and_custom_state_across_disk_loads']"))
    if ($cases.Count -ne 1 -or $cases[0].SelectSingleNode('failure|error|skipped')) {
        throw "The real-mod contract must actually execute and pass without skips. Report: $report"
    }
    Write-Host "Real-mod compatibility contract passed. Report: $run/gametest-results.xml"

} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:Path = $previousPath
}
