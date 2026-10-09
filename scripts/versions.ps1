# Shared Minecraft 26 targets. Loading this file does not build or launch anything.
$MinecraftVersions = @('26.1', '26.1.1', '26.1.2', '26.2', '26.3')
$matrix = @{
    '26.1'   = @{ loader = '0.19.5'; fabric_api = '0.145.1+26.1'; mod_version = '1.2.0' }
    '26.1.1' = @{ loader = '0.19.5'; fabric_api = '0.145.4+26.1.1'; mod_version = '1.2.0' }
    '26.1.2' = @{ loader = '0.19.5'; fabric_api = '0.155.3+26.1.2'; mod_version = '1.2.0' }
    '26.2'   = @{ loader = '0.19.5'; fabric_api = '0.161.0+26.2'; mod_version = '1.2.0' }
    '26.3'   = @{ loader = '0.19.5'; fabric_api = '0.162.0+26.3'; mod_version = '1.2.0' }
}

function Get-MinecraftTarget {
    param([string]$Version)
    if (-not $matrix.ContainsKey($Version)) {
        throw "Unsupported Minecraft '$Version'. Targets: $($MinecraftVersions -join ', ')"
    }
    $target = $matrix[$Version].Clone()
    $target.version = $Version
    return $target
}

function Resolve-JavaHome {
    param([string]$PreferredJavaHome)
    $candidates = if ($PreferredJavaHome) { @($PreferredJavaHome) } else {
        @($env:JAVA_HOME) + @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not $candidate) { continue }
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath $release) -and
            ((Get-Content -LiteralPath $release -Raw) -match 'JAVA_VERSION="25(?:\.|"|-)' )) {
            return $candidate
        }
    }
    throw 'JDK 25 is required. Install it or pass -JavaHome with its directory.'
}

function Get-MinecraftGradleArguments {
    param([string]$Version, [string]$ModVersion)
    $target = Get-MinecraftTarget $Version
    if (-not $ModVersion) { $ModVersion = $target.mod_version }
    return @("-Pminecraft_version=$Version", "-Ploader_version=$($target.loader)",
        "-Pfabric_version=$($target.fabric_api)", "-Pmod_version=$ModVersion")
}
