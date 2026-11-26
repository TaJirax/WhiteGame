[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageManifest,
    [switch] $DisposableWindowsVm
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$hosted = $env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted'
if (!$hosted -and !$DisposableWindowsVm) {
    throw 'Run native network tests on a GitHub-hosted Windows runner or use -DisposableWindowsVm inside an isolated VM.'
}
if ($env:OS -ne 'Windows_NT' -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'The native smoke test requires x64 Windows.'
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'The disposable Windows test runner must already be running as administrator.'
}

$build = Join-Path $PSScriptRoot 'build'
$manifest = Get-Content -LiteralPath $PackageManifest -Raw | ConvertFrom-Json
$appPath = (Resolve-Path -LiteralPath $manifest.app).Path
$image = if (Test-Path -LiteralPath $appPath -PathType Container) { $appPath } else { Split-Path $appPath -Parent }
$buildPrefix = $build.TrimEnd([char]92) + [char]92
if (!$image.StartsWith($buildPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The integration test expects the packaged image inside desktop/build.'
}
$xray = Join-Path $image 'engines/xray/xray.exe'
$wintun = Join-Path $image 'engines/xray/wintun.dll'
if (!(Test-Path -LiteralPath $xray -PathType Leaf) -or !(Test-Path -LiteralPath $wintun -PathType Leaf)) {
    throw 'The packaged Windows image must contain its Xray engine and Wintun driver.'
}

$work = Join-Path $build 'integration'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$wireguard = Join-Path $env:ProgramFiles 'WireGuard/wireguard.exe'
if (!(Test-Path -LiteralPath $wireguard -PathType Leaf)) {
    $msi = Join-Path $work 'wireguard-amd64-1.1.1.msi'
    & curl.exe --fail --location --silent --show-error 'https://download.wireguard.com/windows-client/wireguard-amd64-1.1.1.msi' --output $msi
    if ($LASTEXITCODE -ne 0) { throw 'Could not download the WireGuard test dependency.' }
    $checksum = '7BFED60AD61B785C914B38B61555A975488E1D3EC472DBFB2FCDF498FCA75242'
    if ((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ne $checksum) {
        throw 'The WireGuard test installer failed its pinned SHA-256 check.'
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $msi
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch '^CN=WireGuard LLC,') {
        throw 'The WireGuard test installer did not have a valid WireGuard signature.'
    }
    $log = Join-Path $work 'wireguard-install.log'
    $args = '/i "' + $msi + '" /quiet /norestart DO_NOT_LAUNCH=1 /l*v "' + $log + '"'
    $installer = Start-Process -FilePath 'msiexec.exe' -ArgumentList $args -PassThru -Wait -WindowStyle Hidden
    if ($installer.ExitCode -ne 0) { throw "WireGuard test installation exited with $($installer.ExitCode)." }
}

$peer = Join-Path $work 'wireguard-test-peer.exe'
Push-Location (Join-Path $PSScriptRoot 'src/test/fixtures/wireguard-peer')
try {
    & go build -mod=readonly -buildvcs=false -trimpath -o $peer .
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the userspace WireGuard fixture.' }
} finally {
    Pop-Location
}

$keys = @('WHITEGAME_WINDOWS_LIVE_SMOKE', 'WHITEGAME_DISPOSABLE_VM', 'WHITEGAME_XRAY',
    'WHITEGAME_WIREGUARD', 'WHITEGAME_WIREGUARD_TEST_PEER')
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $env:WHITEGAME_WINDOWS_LIVE_SMOKE = '1'
    if ($DisposableWindowsVm) { $env:WHITEGAME_DISPOSABLE_VM = '1' }
    $env:WHITEGAME_XRAY = $xray
    $env:WHITEGAME_WIREGUARD = $wireguard
    $env:WHITEGAME_WIREGUARD_TEST_PEER = $peer
    $gradle = Join-Path (Split-Path $PSScriptRoot -Parent) 'gradlew.bat'
    & $gradle -p $PSScriptRoot --no-daemon --console=plain test --rerun --tests 'com.whitegame.desktop.WindowsNetworkIntegrationTest'
    if ($LASTEXITCODE -ne 0) { throw 'The Windows DNS/VPN integration tests failed.' }
    $report = Join-Path $build 'test-results/test/TEST-com.whitegame.desktop.WindowsNetworkIntegrationTest.xml'
    $suite = ([xml](Get-Content -LiteralPath $report -Raw)).testsuite
    if ($suite.tests -ne '4' -or $suite.skipped -ne '0' -or $suite.failures -ne '0' -or $suite.errors -ne '0') {
        throw 'Expected all four Windows network checks to run and pass.'
    }
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
