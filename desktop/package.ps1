[CmdletBinding()]
param([string] $PackageManifest)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = Split-Path $PSScriptRoot -Parent
$buildDir = Join-Path $PSScriptRoot 'build'
$buildScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildScript, '(?m)^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$')
if (!$versionMatch.Success) { throw 'Cannot read the desktop version from build.gradle.kts.' }
$version = $versionMatch.Groups[1].Value
$engineVersion = '26.11.9'
$engineChecksum = '1262522266337b824f07beb8e1375786b437683b3890105469088f8e17e0a69d'
$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/jpackage.exe' } else { 'jpackage.exe' }

if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'Build this package on x64 Windows with an x64 JDK 17.'
}

& (Join-Path $workspace 'gradlew.bat') -p $PSScriptRoot --console=plain test installDist
if ($LASTEXITCODE -ne 0) { throw 'Desktop Gradle build failed.' }

$native = Join-Path $buildDir 'native'
New-Item -ItemType Directory -Force -Path $native | Out-Null
$archive = Join-Path $native 'Xray-windows-64.zip'
if (!(Test-Path -LiteralPath $archive -PathType Leaf) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $engineChecksum) {
    $url = "https://github.com/XTLS/Xray-core/releases/download/v$engineVersion/Xray-windows-64.zip"
    & curl.exe --fail --location --silent --show-error --output $archive $url
    if ($LASTEXITCODE -ne 0) { throw 'Xray download failed.' }
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $engineChecksum) {
    throw 'The downloaded Xray archive failed SHA-256 verification.'
}

$packageDir = Join-Path $buildDir ('package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $packageDir | Out-Null
$engineDir = Join-Path $packageDir 'upstream'
Expand-Archive -LiteralPath $archive -DestinationPath $engineDir

$packageArgs = @(
    '--type', 'app-image', '--name', 'WhiteGame', '--app-version', $version,
    '--input', (Join-Path $buildDir 'install/whitegame-desktop/lib'),
    '--main-jar', "whitegame-desktop-$version.jar",
    '--main-class', 'com.whitegame.desktop.MainKt', '--dest', $packageDir, '--vendor', 'WhiteGame'
)
& $jpackage @packageArgs
if ($LASTEXITCODE -ne 0) { throw 'Portable Windows app image creation failed.' }

$image = Join-Path $packageDir 'WhiteGame'
$bundledEngine = Join-Path $image 'engines/xray'
New-Item -ItemType Directory -Path $bundledEngine -Force | Out-Null
foreach ($file in 'xray.exe', 'wintun.dll', 'geoip.dat', 'geosite.dat', 'LICENSE', 'LICENSE-Wintun') {
    Copy-Item -LiteralPath (Join-Path $engineDir $file) -Destination $bundledEngine
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $image 'README.md')

$dist = Join-Path $workspace 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$name = "WhiteGame-Windows-x64-$version.zip"
$stagedZip = Join-Path $packageDir $name
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($image, $stagedZip, [IO.Compression.CompressionLevel]::Optimal, $true)
$publishedZip = Join-Path $dist $name
$portableExe = Join-Path $image 'WhiteGame.exe'
Move-Item -LiteralPath $stagedZip -Destination $publishedZip -Force
if ($PackageManifest) {
    [ordered]@{ version = $version; archive = $publishedZip; app = $portableExe } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $PackageManifest -Encoding utf8
}
Write-Output "Portable executable: $portableExe"
Write-Output "Distribution: $publishedZip"
