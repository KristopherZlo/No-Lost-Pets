param(
    [string[]]$Versions = @("1.21.8", "1.21.9", "1.21.10", "1.21.11"),
    [string]$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
)

$ErrorActionPreference = "Stop"

$matrix = @{
    "1.21.8" = @{
        yarn = "1.21.8+build.1"
        loader = "0.18.2"
        fabric_api = "0.136.1+1.21.8"
    }
    "1.21.9" = @{
        yarn = "1.21.9+build.1"
        loader = "0.18.2"
        fabric_api = "0.134.1+1.21.9"
    }
    "1.21.10" = @{
        yarn = "1.21.10+build.3"
        loader = "0.18.2"
        fabric_api = "0.138.4+1.21.10"
    }
    "1.21.11" = @{
        yarn = "1.21.11+build.4"
        loader = "0.18.2"
        fabric_api = "0.141.3+1.21.11"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $repoRoot "dist\1.21x"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

foreach ($version in $Versions) {
    if (-not $matrix.ContainsKey($version)) {
        throw "Unsupported target '$version'. Supported targets: $($matrix.Keys -join ', ')"
    }

    $target = $matrix[$version]
    Write-Host "Building Fabric target $version"

    $env:JAVA_HOME = $JavaHome
    & (Join-Path $repoRoot "gradlew.bat") `
        clean `
        remapJar `
        sourcesJar `
        "-Pminecraft_version=$version" `
        "-Pyarn_mappings=$($target.yarn)" `
        "-Ploader_version=$($target.loader)" `
        "-Pfabric_version=$($target.fabric_api)"

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
