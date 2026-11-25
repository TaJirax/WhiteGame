# The WhiteGame launcher verifies the supplied script before the approved elevated session runs it.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$kind = [string]$manifest.kind
$adapter = [string]$manifest.adapter
if ($kind -notin @('wireguard', 'amnezia', 'xray', 'dns')) { throw 'Invalid Windows network backend.' }
if ($kind -ne 'dns' -and $adapter -cnotmatch '^WG[0-9a-f]{10}$') { throw 'Invalid tunnel name.' }
$lease = [guid]::Parse($manifest.lease).ToString()
$private = Join-Path $env:ProgramData ('WhiteGame-' + $lease)
$stopPath = Join-Path $session 'stop'
$statePath = Join-Path $session 'status.json'
$encoding = [Text.UTF8Encoding]::new($false)
$suffix = if ($kind -eq 'xray') { '.json' } else { '.conf' }
$configPath = Join-Path $private ($adapter + $suffix)
$dnsPath = Join-Path $private 'dns-snapshot.json'
$prefix = if ($kind -eq 'amnezia') { 'AmneziaWGTunnel$' } else { 'WireGuardTunnel$' }
$serviceName = $prefix + $adapter

function Write-Status($phase, $detail, $counters = @{}) {
    $status = @{ phase = $phase; detail = $detail }
    foreach ($entry in $counters.GetEnumerator()) { $status[$entry.Key] = $entry.Value }
    $pending = Join-Path $session ('status-' + [guid]::NewGuid().ToString() + '.tmp')
    [IO.File]::WriteAllText($pending, ($status | ConvertTo-Json -Compress), $encoding)
    try {
        if ([IO.File]::Exists($statePath)) { [IO.File]::Replace($pending, $statePath, [NullString]::Value) }
        else { [IO.File]::Move($pending, $statePath) }
    } finally {
        if ([IO.File]::Exists($pending)) { [IO.File]::Delete($pending) }
    }
}

function Keep-Running {
    if ([IO.File]::Exists($stopPath)) { return $false }
    $owner = Get-Process -Id $ownerProcessId -ErrorAction SilentlyContinue
    return $null -ne $owner -and $owner.StartTime.ToUniversalTime() -eq $ownerStarted
}

function New-PrivateDirectory {
    if (Test-Path -LiteralPath $private) { throw 'Windows network session already exists.' }
    $security = [System.Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    foreach ($id in @('S-1-5-18', 'S-1-5-32-544')) {
        $sid = [System.Security.Principal.SecurityIdentifier]::new($id)
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
        )
        $security.AddAccessRule($rule)
    }
    [void][IO.Directory]::CreateDirectory($private, $security)
}

function Start-Cli($file, $arguments) {
    $out = Join-Path $private 'cli-output'
    $err = Join-Path $private 'cli-error'
    $process = Start-Process -FilePath $file -ArgumentList $arguments -WindowStyle Hidden -PassThru -WorkingDirectory $private -RedirectStandardOutput $out -RedirectStandardError $err
    if (!$process.WaitForExit(15000)) {
        $process.Kill()
        throw 'The Windows tunnel client did not respond.'
    }
    if ($process.ExitCode -ne 0) {
        throw 'The Windows tunnel client rejected this operation. Check its installed version and config.'
    }
}

function Test-OwnedService {
    $service = Get-CimInstance -ClassName Win32_Service -Filter ("Name = '" + $serviceName + "'")
    if ($null -eq $service) { return $false }
    if ($service.PathName -notlike '*/tunnelservice *' -or
        $service.PathName.IndexOf($configPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'The Windows service name belongs to another tunnel.'
    }
    return $true
}

function Start-Xray {
    $stdout = Join-Path $private 'xray-output'
    $stderr = Join-Path $private 'xray-error'
    return Start-Process -FilePath $engine -ArgumentList ('run -config "' + $configPath + '"') -WindowStyle Hidden -WorkingDirectory ([IO.Path]::GetDirectoryName($engine)) -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
}

function Stop-Xray($process) {
    if (!$process -or $process.HasExited) { return }
    # Xray restores Wintun settings when it receives a console interrupt.
    try {
        if (!('WhiteGame.NativeConsole' -as [type])) {
            Add-Type -Namespace WhiteGame -Name NativeConsole -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool AttachConsole(uint processId);
[DllImport("kernel32.dll")] public static extern uint GetConsoleProcessList([Out] uint[] processes, uint count);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint eventType, uint processGroup);
private delegate bool CtrlHandler(uint eventType);
private static readonly CtrlHandler ignoreBreak = (eventType) => eventType == 1;
[DllImport("kernel32.dll", EntryPoint = "SetConsoleCtrlHandler")]
private static extern bool RegisterHandler(CtrlHandler handler, bool add);
public static bool ProtectFromBreak() { return RegisterHandler(ignoreBreak, true); }
'@
        }
        [void][WhiteGame.NativeConsole]::FreeConsole()
        if ([WhiteGame.NativeConsole]::AttachConsole([uint32]$process.Id)) {
            try {
                $occupants = [uint32[]]::new(3)
                $count = [WhiteGame.NativeConsole]::GetConsoleProcessList($occupants, 3)
                $isolated = $count -eq 2 -and $occupants -contains [uint32]$PID -and $occupants -contains [uint32]$process.Id
                if ($isolated -and [WhiteGame.NativeConsole]::ProtectFromBreak() -and
                    [WhiteGame.NativeConsole]::GenerateConsoleCtrlEvent(1, 0) -and $process.WaitForExit(5000)) { return }
            } finally {
                [void][WhiteGame.NativeConsole]::FreeConsole()
            }
        }
    } catch {
        # A force stop remains available if the process has no private console.
    }
    if (!$process.HasExited) { Stop-Process -InputObject $process -Force }
    if (!$process.WaitForExit(5000)) { throw 'Xray is still shutting down.' }
}

function Clear-XrayRoutes {
    # The pinned Xray core derives its Wintun adapter GUID from the adapter name.
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $identity = [guid]::new($md5.ComputeHash($encoding.GetBytes($adapter)))
    } finally {
        $md5.Dispose()
    }
    $network = Get-NetAdapter -Name $adapter -IncludeHidden -ErrorAction SilentlyContinue |
        Where-Object { [guid]$_.InterfaceGuid -eq $identity } | Select-Object -First 1
    if (!$network) { return }
    $routes = @(Get-NetRoute -InterfaceIndex $network.ifIndex -PolicyStore ActiveStore -ErrorAction SilentlyContinue |
        Where-Object { $_.RouteMetric -eq 0 -and $_.NextHop -in @('0.0.0.0', '::') })
    if ($routes.Count) { Remove-NetRoute -InputObject $routes -Confirm:$false }
}

function Wait-TunnelAdapter($process = $null) {
    $deadline = [DateTime]::UtcNow.AddSeconds(26)
    $nextReport = [DateTime]::UtcNow.AddSeconds(2)
    do {
        if (!(Keep-Running)) { return }
        if ([DateTime]::UtcNow -ge $nextReport) {
            Write-Status 'starting' 'Waiting for the Windows tunnel adapter.'
            $nextReport = [DateTime]::UtcNow.AddSeconds(2)
        }
        if ($null -ne $process -and $process.HasExited) {
            throw 'Xray rejected the tunnel. Check the profile, core version, and Windows Wintun files.'
        }
        $network = Get-NetAdapter -Name $adapter -IncludeHidden -ErrorAction SilentlyContinue
        if ($network -and $network.Status -eq 'Up') {
            if ($kind -ne 'xray') {
                $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
                if ($service -and $service.Status -eq 'Running') { return }
            } else {
                $route = Get-NetRoute -InterfaceIndex $network.ifIndex -ErrorAction SilentlyContinue |
                    Where-Object { $_.RouteMetric -eq 0 } | Select-Object -First 1
                if ($route) { return }
            }
        }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Windows could not bring up the tunnel network adapter.'
}

function Read-TunnelCounters {
    $counters = @{}
    if ($kind -eq 'xray') {
        $stats = Get-NetAdapterStatistics -Name $adapter -ErrorAction SilentlyContinue
        if ($stats) {
            $counters.rx = [long]$stats.ReceivedBytes
            $counters.tx = [long]$stats.SentBytes
        }
        return $counters
    }
    $executable = if ($kind -eq 'amnezia') { 'awg.exe' } else { 'wg.exe' }
    $cli = Join-Path ([IO.Path]::GetDirectoryName($engine)) $executable
    if (Test-Path -LiteralPath $cli -PathType Leaf) {
        $rx = 0L; $tx = 0L
        $transfer = @(& $cli show $adapter transfer 2>$null)
        foreach ($line in $transfer) {
            $values = $line -split '\s+'
            if ($values.Length -ge 3) { $rx += [long]$values[1]; $tx += [long]$values[2] }
        }
        if ($transfer.Count -gt 0) { $counters.rx = $rx; $counters.tx = $tx }
        $latest = 0L
        foreach ($line in @(& $cli show $adapter latest-handshakes 2>$null)) {
            $values = $line -split '\s+'
            if ($values.Length -ge 2) { $latest = [Math]::Max($latest, [long]$values[1]) }
        }
        if ($latest -gt 0) { $counters.handshake = [Math]::Max(0L, [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $latest) }
    }
    return $counters
}

function Dns-ServerList($value) {
    return @($value | Where-Object { $_ } | ForEach-Object { [Net.IPAddress]::Parse([string]$_).ToString() })
}

function Save-And-ApplyDns {
    $changes = @()
    $adapters = @(Get-NetRoute -DestinationPrefix @('0.0.0.0/0', '::/0') -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty InterfaceIndex -Unique)
    foreach ($index in $adapters) {
        $network = Get-NetAdapter -InterfaceIndex $index -IncludeHidden -ErrorAction SilentlyContinue
        if (!$network -or $network.Status -ne 'Up') { continue }
        $guid = [guid]$network.InterfaceGuid
        foreach ($family in @('IPv4', 'IPv6')) {
            $applied = @(Dns-ServerList ($manifest.servers | Where-Object { ($_ -like '*:*') -eq ($family -eq 'IPv6') }))
            if (!$applied.Count) { continue }
            $entry = Get-DnsClientServerAddress -InterfaceIndex $index -AddressFamily $family -ErrorAction SilentlyContinue
            if (!$entry) { continue }
            $driver = if ($family -eq 'IPv4') { 'Tcpip' } else { 'Tcpip6' }
            $key = "HKLM:\SYSTEM\CurrentControlSet\Services\$driver\Parameters\Interfaces\" + $guid.ToString('B')
            $static = Get-ItemPropertyValue -LiteralPath $key -Name NameServer -ErrorAction SilentlyContinue
            $original = @(([string]$static -split '[,\s]+') | Where-Object { $_ })
            $changes += @{ guid = $guid.ToString(); family = $family; original = $original; applied = $applied }
        }
    }
    if (!$changes.Count) { throw 'No active Windows adapter supports the selected DNS addresses.' }
    [IO.File]::WriteAllText($dnsPath, (ConvertTo-Json -InputObject $changes -Depth 4 -Compress), $encoding)
    foreach ($change in $changes) {
        if (!(Keep-Running)) { return }
        $network = Get-NetAdapter -IncludeHidden | Where-Object { [guid]$_.InterfaceGuid -eq [guid]$change.guid } |
            Select-Object -First 1
        if (!$network) { throw 'The active Windows network adapter changed.' }
        $entry = Get-DnsClientServerAddress -InterfaceIndex $network.ifIndex -AddressFamily $change.family
        Set-DnsClientServerAddress -InputObject $entry -ServerAddresses $change.applied
    }
    Clear-DnsClientCache
}

function Restore-Dns {
    if (!(Test-Path -LiteralPath $dnsPath -PathType Leaf)) { return }
    $changes = @(Get-Content -LiteralPath $dnsPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    $allAdapters = @(Get-NetAdapter -IncludeHidden)
    foreach ($change in $changes) {
        $network = $allAdapters | Where-Object { [guid]$_.InterfaceGuid -eq [guid]$change.guid } | Select-Object -First 1
        if (!$network) { throw 'Reconnect the Windows network adapter and restore DNS again.' }
        $entry = Get-DnsClientServerAddress -InterfaceIndex $network.ifIndex -AddressFamily $change.family
        $current = @(Dns-ServerList $entry.ServerAddresses)
        if (($current -join ',') -ne ($change.applied -join ',')) { continue }
        if (@($change.original).Count -gt 0) {
            Set-DnsClientServerAddress -InputObject $entry -ServerAddresses $change.original
        } else {
            Set-DnsClientServerAddress -InputObject $entry -ResetServerAddresses
        }
    }
    Clear-DnsClientCache
}

function Stop-Session {
    if (!(Test-Path -LiteralPath $private -PathType Container)) { return }
    if ($kind -eq 'dns') { Restore-Dns }
    elseif ($kind -eq 'xray') {
        Stop-Xray $core
        if ($recover) {
            $workers = Get-CimInstance -ClassName Win32_Process | Where-Object {
                $_.ExecutablePath -eq $engine -and $_.CommandLine -and
                $_.CommandLine.IndexOf($configPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
            }
            foreach ($worker in $workers) {
                Stop-Xray (Get-Process -Id $worker.ProcessId -ErrorAction SilentlyContinue)
            }
        }
        Clear-XrayRoutes
    } elseif (Test-OwnedService) {
        Start-Cli $engine ('/uninstalltunnelservice ' + $adapter)
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while ((Get-Service -Name $serviceName -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 220
        }
        if ((Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) { throw 'The tunnel service has not shut down.' }
    }
    foreach ($file in @($configPath, $dnsPath, (Join-Path $private 'cli-output'), (Join-Path $private 'cli-error'),
        (Join-Path $private 'xray-output'), (Join-Path $private 'xray-error'))) {
        [IO.File]::Delete($file)
    }
    [IO.Directory]::Delete($private)
}

$core = $null
$failure = $null
$ownsSession = $false
try {
    if ($recover) {
        $ownsSession = $true
        Write-Status 'starting' 'Restoring prior Windows network settings…'
    } else {
        $owner = Get-Process -Id $ownerProcessId -ErrorAction Stop
        $ownerStarted = $owner.StartTime.ToUniversalTime()
        if (!(Keep-Running)) { return }
        Write-Status 'starting' 'Starting the Windows network session…'
        New-PrivateDirectory
        $ownsSession = $true
        if ($kind -eq 'dns') {
            Save-And-ApplyDns
            while (Keep-Running) {
                Write-Status 'running' 'Windows DNS override is active.'
                Start-Sleep -Seconds 2
            }
        } else {
            $data = [IO.File]::ReadAllBytes((Join-Path $session ($adapter + $suffix)))
            $hash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($data)).Replace('-', '')
            if ($hash -ne $manifest.payloadSha) { throw 'The staged tunnel config changed before startup.' }
            [IO.File]::WriteAllBytes($configPath, $data)
            $data = $null
            [IO.File]::Delete((Join-Path $session ($adapter + $suffix)))
            if ($kind -eq 'xray') { $core = Start-Xray }
            else {
                if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) { throw 'The Windows tunnel name is already in use.' }
                Start-Cli $engine ('/installtunnelservice "' + $configPath + '"')
                Set-Service -Name $serviceName -StartupType Manual
            }
            Wait-TunnelAdapter $core
            $restarts = 0
            while (Keep-Running) {
                $alive = if ($kind -eq 'xray') { !$core.HasExited }
                    else { (Get-Service -Name $serviceName -ErrorAction SilentlyContinue).Status -eq 'Running' }
                if (!$alive) {
                    if (!$manifest.reconnect -or $restarts -ge 2) { throw 'The Windows tunnel stopped.' }
                    $restarts++
                    Write-Status 'starting' 'Reconnecting the Windows tunnel…'
                    Start-Sleep -Seconds 1
                    if (!(Keep-Running)) { break }
                    if ($kind -eq 'xray') { $core = Start-Xray }
                    else { Start-Service -Name $serviceName }
                    Wait-TunnelAdapter $core
                }
                $counters = @{}
                try { $counters = Read-TunnelCounters } catch { }
                $detail = if ($kind -eq 'xray') { 'Xray tunnel running' }
                    elseif ($counters.ContainsKey('handshake')) { 'Tunnel running' }
                    else { 'Tunnel running · waiting for first handshake' }
                Write-Status 'running' $detail $counters
                Start-Sleep -Seconds 2
            }
        }
    }
} catch {
    $failure = $_.Exception.Message
} finally {
    $restored = $true
    if ($ownsSession) {
        try { Stop-Session } catch { $restored = $false }
    }
    if (!$restored) { Write-Status 'recovery' 'Windows could not restore the network settings. Retry Disconnect or Restore DNS.' }
    elseif ($failure) { Write-Status 'failed' $failure }
    else { Write-Status 'stopped' 'Windows network settings restored.' }
}
