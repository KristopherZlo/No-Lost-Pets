param(
    [string[]]$Versions = @("1.21.8", "1.21.9", "1.21.10", "1.21.11"),
    [string]$JavaHome,
    [switch]$SkipSmoke,
    [switch]$RunLanSmoke,
    [string]$LanSmokeWorldName = "Testing",
    [int]$ClientSmokeTimeoutSeconds = 180,
    [int]$GameTestTimeoutSeconds = 900,
    [int]$LanSmokeTimeoutSeconds = 420
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

$repoRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $repoRoot "dist\1.21x"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$resolvedJavaHome = Resolve-JavaHome -PreferredJavaHome $JavaHome
$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$resolvedJavaHome\bin;$env:Path"

Get-ChildItem -LiteralPath $outputDir -Filter "*.jar" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

foreach ($version in $Versions) {
    if (-not $matrix.ContainsKey($version)) {
        throw "Unsupported target '$version'. Supported targets: $($matrix.Keys -join ', ')"
    }

    $target = $matrix[$version]
    if (-not $SkipSmoke) {
        Write-Host "Running client smoke for Fabric target $version mod $($target.mod_version)"
        & (Join-Path $PSScriptRoot "smoke-client.ps1") `
            -Version $version `
            -JavaHome $resolvedJavaHome `
            -ModVersion $target.mod_version `
            -TimeoutSeconds $ClientSmokeTimeoutSeconds `
            -NoDaemon

        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }

        Write-Host "Running GameTest smoke for Fabric target $version mod $($target.mod_version)"
        & (Join-Path $PSScriptRoot "verify-all.ps1") `
            -Versions @($version) `
            -JavaHome $resolvedJavaHome `
            -TimeoutSeconds $GameTestTimeoutSeconds

        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }

        if ($RunLanSmoke) {
            Write-Host "Running LAN smoke for Fabric target $version mod $($target.mod_version)"
            & (Join-Path $PSScriptRoot "smoke-lan.ps1") `
                -Version $version `
                -JavaHome $resolvedJavaHome `
                -ModVersion $target.mod_version `
                -WorldName $LanSmokeWorldName `
                -TimeoutSeconds $LanSmokeTimeoutSeconds `
                -NoDaemon

            if ($LASTEXITCODE -ne 0) {
                exit $LASTEXITCODE
            }
        }
    } else {
        Write-Host "Skipping smoke tests for Fabric target $version"
    }

    Write-Host "Building Fabric target $version mod $($target.mod_version)"

    & (Join-Path $repoRoot "gradlew.bat") `
        clean `
        remapJar `
        sourcesJar `
        "-Pminecraft_version=$version" `
        "-Psupported_minecraft_min=$version" `
        "-Psupported_minecraft_max=$version" `
        "-Pyarn_mappings=$($target.yarn)" `
        "-Ploader_version=$($target.loader)" `
        "-Pfabric_version=$($target.fabric_api)" `
        "-Pmod_version=$($target.mod_version)"

    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $jar = Get-ChildItem (Join-Path $repoRoot "build\libs\*.jar") |
        Where-Object { $_.Name -notlike "*-sources.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "No remapped jar produced for $version"
    }

    Copy-Item $jar.FullName (Join-Path $outputDir "NoLostPets-fabric-$version.jar") -Force
}

Write-Host "Artifacts copied to $outputDir"
