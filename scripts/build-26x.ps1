param(
    [ValidateNotNullOrEmpty()][string[]]$Versions = @('26.1', '26.1.1', '26.1.2', '26.2', '26.3'),
    [string]$JavaHome,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$ModVersion = '1.2.0',
    [string]$GradleUserHome
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'versions.ps1')
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Resolve-JavaHome $JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
foreach ($version in $Versions) { $null = Get-MinecraftTarget $version }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location $repoRoot
try {
    foreach ($version in $Versions) {
        $target = Get-MinecraftTarget $version
        $arguments = @('compileJava', 'compileTestJava', 'compileGametestJava', 'processGametestResources', 'stageArtifacts',
            '--no-daemon', '--console=plain') + @(Get-MinecraftGradleArguments $version $ModVersion)
        if ($GradleUserHome) { $arguments += @('-g', $GradleUserHome) }
        $gradleErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & (Join-Path $repoRoot 'gradlew.bat') @arguments
        } finally { $ErrorActionPreference = $gradleErrorAction }
        if ($LASTEXITCODE -ne 0) { throw "Compilation failed for Minecraft $version" }
        $jar = Join-Path $repoRoot "build\candidates\$version\NoLostPets-$ModVersion+mc$version.jar"
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
        try {
            $reader = [System.IO.StreamReader]::new($archive.GetEntry('fabric.mod.json').Open())
            try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($metadata.depends.minecraft -ne $version -or $metadata.depends.java -ne '>=25' -or
                $metadata.version -ne "$ModVersion+mc$version" -or
                $metadata.depends.fabricloader -ne ">=$($target.loader)" -or
                $metadata.depends.'fabric-api' -ne ">=$($target.fabric_api)") { throw "Wrong artifact metadata: $jar" }
            $classes = @($archive.Entries | Where-Object { $_.FullName -like 'com/creas/petrecall/*.class' })
            if ($classes.Count -eq 0) { throw "Missing mod classes: $jar" }
            foreach ($entry in $classes) {
                if ($entry.FullName -match '/gametest/' -or $entry.FullName -match 'Test.class$') {
                    throw "Test class included in production jar: $($entry.FullName)"
                }
                $stream = $entry.Open()
                try {
                    $header = New-Object byte[] 8
                    if ($stream.Read($header, 0, 8) -ne 8 -or ($header[6] * 256 + $header[7]) -ne 69) {
                        throw "Unexpected Java class version: $($entry.FullName)"
                    }
                } finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
        $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        [System.IO.File]::WriteAllText("$jar.sha256", "$hash  $([System.IO.Path]::GetFileName($jar))`n")
        Write-Host "Candidate compiled (behavior tests NOT run): $jar"
    }
} finally { Pop-Location }
