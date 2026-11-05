# Sets up a new box from a PC: the StreamBox launcher, StreamBox Updates, Home and the remote-button helper.
# It is safe to run again. It stops, and says why, rather than risk the box's Wi-Fi or Home screen.
#
#   install.bat <box ip[:port]>                 install and set up
#   install.bat <box ip[:port]> -Debloat        also turn off or remove the listed apps (the Onn 4K Pro this list is for only)
#   install.bat <box ip[:port]> -UndoDebloat    turn the listed apps back on, and bring back removed ones that came with the box
#   install.bat <box ip> -CheckClosed [-Port n] after turning debugging off: is the port really closed?
#
# -Ethernet is for a box with no saved Wi-Fi network at all, where the Wi-Fi owner can't be read.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Address,
    [switch]$Debloat,
    [switch]$UndoDebloat,
    [switch]$Ethernet,
    [switch]$CheckClosed,
    [int]$Port = 5555
)
$ErrorActionPreference = 'Continue'

$launcher = 'com.gathin.tvhome'
$updates = 'ca.techshop.streamboxupdates'
$wizard = 'com.google.android.tungsten.setupwraith'
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher')
$debloatModel = 'onn. Streaming Device 4K pro'
$here = $PSScriptRoot

function Stop-Here([string]$why) {
    Write-Host ''
    Write-Host "STOPPED: $why" -ForegroundColor Yellow
    exit 1
}
function Step([string]$what) { Write-Host "== $what" -ForegroundColor Cyan }

if ($CheckClosed) {
    # Only the box's own refusal shows the port closed. No answer at all could just be a box that is asleep.
    $boxIp = ($Address -split ':')[0]
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.ConnectAsync($boxIp, $Port)
        if (-not $attempt.Wait(3000)) { Write-Host "UNKNOWN: nothing answered on ${boxIp}:$Port. Is the box on and awake?"; exit 2 }
        Write-Host "OPEN: ${boxIp}:$Port still accepts connections." -ForegroundColor Yellow
        exit 1
    } catch {
        $socketError = $_.Exception.GetBaseException()
        if ($socketError -is [System.Net.Sockets.SocketException] -and $socketError.SocketErrorCode -eq 'ConnectionRefused') {
            Write-Host "CLOSED: ${boxIp}:$Port refused the connection." -ForegroundColor Green
            exit 0
        }
        Write-Host "UNKNOWN: $($socketError.Message)"
        exit 2
    } finally { $client.Close() }
}

if (-not $UndoDebloat -and -not ((Test-Path (Join-Path $here 'apps\launcher.apk')) -and (Test-Path (Join-Path $here 'apps\updates.apk')))) {
    Stop-Here "the apps are not in $here\apps. Run install.bat from the unpacked StreamBox-Deploy package, not from the repo's deploy folder. python deploy\build-package.py makes it, in work\deploy. Nothing was changed."
}

$adb = @(
    (Join-Path $here 'platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adb) { $found = Get-Command adb -ErrorAction SilentlyContinue; if ($found) { $adb = $found.Source } }
if (-not $adb) {
    Stop-Here 'adb was not found. Get Android platform-tools from https://developer.android.com/tools/releases/platform-tools and unzip it into this folder.'
}

$dev = if ($Address -match ':') { $Address } else { "${Address}:5555" }
function Adb { (& $adb -s $dev @args 2>&1 | ForEach-Object { "$_" }) -join "`n" }
function Box { (Adb shell @args).Trim() }
function Listed([string]$pkg, [string[]]$flags) { (Box pm list packages @flags $pkg) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -eq "package:$pkg" } }
# Installed and not on the disabled list. The Onn's "enabled" list (-e) also names packages that are disabled.
function Enabled([string]$pkg) { [bool](Listed $pkg @()) -and -not (Listed $pkg @('-d')) }
function Home-Owner { ((Box cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -split "`n")[-1].Trim() }

Step "Connecting to $dev"
Write-Host (& $adb connect $dev 2>&1 | ForEach-Object { "$_" })
$state = (Adb get-state).Trim()
if ($state -ne 'device') {
    Stop-Here "the box answered '$state'. Check the address and port, and approve this PC on the TV. Wireless debugging needs pairing first: see README.txt."
}

$listFile = Join-Path $here 'debloat-onn-4k-pro.txt'
$lines = @()
if (Test-Path $listFile) { $lines = @(Get-Content $listFile | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }) }
$listed = @($lines | Where-Object { $_ -notmatch '^remove\s' })
$removed = @($lines | Where-Object { $_ -match '^remove\s' } | ForEach-Object { ($_ -replace '^remove\s+', '') })
$model = Box getprop ro.product.model

if ($UndoDebloat) {
    Step 'Turning the listed apps back on'
    foreach ($pkg in $listed) { Write-Host "$pkg : $(Box pm enable --user 0 $pkg)" }
    foreach ($pkg in $removed) {
        [void](Box cmd package install-existing --user 0 $pkg)
        if (Listed $pkg @()) { Write-Host "$pkg : back" }
        else { Write-Host "$pkg : not brought back. It didn't come with this box, so install it again from the Play Store." }
    }
    exit 0
}

$sdk = 0
[void][int]::TryParse((Box getprop ro.build.version.sdk), [ref]$sdk)
if ($sdk -lt 26) { Stop-Here "this box runs SDK $sdk and the launcher needs 26. Nothing was changed." }

Step 'Declining Play Protect app scanning'
# These boxes run apps from outside the Play Store. README.txt says how to undo this.
[void](Box settings put global package_verifier_user_consent '-1')
if ((Box settings get global package_verifier_user_consent) -eq '-1') { Write-Host 'Play Protect scanning: declined' }
else { Write-Host 'WARNING: the Play Protect setting did not change.' -ForegroundColor Yellow }

Step 'Installing the apps'
foreach ($app in @(@('StreamBox launcher', 'launcher.apk'), @('StreamBox Updates', 'updates.apk'))) {
    $result = Adb install -r (Join-Path $here "apps\$($app[1])")
    if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Stop-Here "$($app[0]) is already on this box, signed with another key. Save a backup of its layout, uninstall it, and run this again."
    }
    if ($result -notmatch 'Success') { Stop-Here "$($app[0]) did not install: $result" }
    Write-Host "$($app[0]): installed"
}
# The TV's own page for this opens on the wrong app, so it is set here.
[void](Box appops set $updates REQUEST_INSTALL_PACKAGES allow)
Write-Host 'StreamBox Updates: allowed to install apps'

Step 'Blocking TiviMate recordings'
# A file where TiviMate wants its Recordings folder, so recordings can't fill the box. The file is what blocks it:
# shared storage ignores chmod here. An empty folder is replaced; one holding recordings is left alone.
$tivimate = '/sdcard/Download/TiviMate'
[void](Box "mkdir -p $tivimate; rmdir $tivimate/Recordings 2>/dev/null; touch $tivimate/Recordings; chmod 444 $tivimate/Recordings")
if ((Box "[ -f $tivimate/Recordings ] && echo file") -eq 'file') { Write-Host 'TiviMate recordings: blocked' }
else { Write-Host "WARNING: $tivimate/Recordings is a folder with files in it, so recordings are not blocked. Nothing was deleted." -ForegroundColor Yellow }

Step 'Copying the Edge settings backup'
$edgeLocal = Join-Path $here 'files\edge-settings.bak'
$edgeBox = '/sdcard/Download/edge-settings.bak'
if (-not (Test-Path $edgeLocal)) {
    Write-Host 'WARNING: files\edge-settings.bak is not in this package, so it was not copied.' -ForegroundColor Yellow
} else {
    [void](Adb push $edgeLocal $edgeBox)
    if ((Box "wc -c < $edgeBox") -eq "$((Get-Item $edgeLocal).Length)") { Write-Host "Edge settings backup: copied to $edgeBox" }
    else { Write-Host 'WARNING: the Edge settings backup did not copy.' -ForegroundColor Yellow }
}

Step 'Making Aerial Views the screensaver'
# The default and the chosen one both: Android uses the chosen one, and -Debloat turns off Google's default.
$aerial = 'com.neilturner.aerialviews/com.neilturner.aerialviews.ui.screensaver.DreamActivity'
if (-not (Listed 'com.neilturner.aerialviews' @())) {
    Write-Host 'WARNING: Aerial Views is not installed, so the screensaver was left alone. Install it, then run this again.' -ForegroundColor Yellow
} else {
    [void](Box settings put secure screensaver_default_component $aerial)
    [void](Box settings put secure screensaver_components $aerial)
    if ((Box settings get secure screensaver_default_component) -eq $aerial -and (Box settings get secure screensaver_components) -eq $aerial) {
        Write-Host 'Screensaver: Aerial Views'
    } else { Write-Host 'WARNING: the screensaver setting did not change.' -ForegroundColor Yellow }
}

Step 'Checking who owns Home'
if ((Box pm list packages) -match 'package:com\.cvte\.') {
    Stop-Here 'this is a CVTE board, where changing Home needs its own steps. The apps are installed; Home was not touched.'
}
$owner = Home-Owner
Write-Host "Home is $owner"
$stock = $null
if (-not $owner.StartsWith("$launcher/")) {
    $ownerPkg = ($owner -split '/')[0]
    if ($knownStock -contains $ownerPkg) {
        $stock = $ownerPkg
    } elseif ($ownerPkg -eq 'android' -or $ownerPkg -eq $wizard) {
        $claimants = (Box cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME)
        $found = @($knownStock | Where-Object { $claimants -match [regex]::Escape("$_/") })
        if ($found.Count -ne 1) { Stop-Here "Home is '$owner' and $($found.Count) known launchers claim it. Pick a default on the TV, then run this again. The apps are installed; Home was not touched." }
        $stock = $found[0]
    } else {
        Stop-Here "Home belongs to '$owner', which this script doesn't know, so it left Home alone. The apps are installed."
    }
}

# A Wi-Fi network saved during first setup belongs to the setup wizard, and Android deletes that network when the
# wizard is disabled, which would take the box off Wi-Fi and off this connection.
$wizardOn = Enabled $wizard
if ($wizardOn) {
    $wifi = Box dumpsys wifi
    if ($wifi -match 'cname=[^ ]*setupwraith') {
        Stop-Here "the saved Wi-Fi network belongs to the setup wizard, and turning the wizard off would delete it. On the TV: Settings > Network & Internet, forget the network and join it again, then run this again (the port may change). The apps are installed; Home was not touched."
    }
    if ($wifi -notmatch 'cname=' -and -not $Ethernet) {
        Stop-Here "could not read who owns the saved Wi-Fi networks, so the setup wizard was left alone. If the box has no saved Wi-Fi and is on Ethernet, run this again with -Ethernet. The apps are installed; Home was not touched."
    }
}

Step 'Starting the launcher'
# A stop first, so the start below makes a fresh one rather than reusing one Android restarted mid-update.
[void](Box am force-stop $launcher)
$start = Box am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$launcher/.MainActivity"
if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { Stop-Here "the launcher did not start, so nothing was disabled:`n$start" }
[void](Box pm grant $launcher android.permission.ACCESS_FINE_LOCATION)
[void](Box cmd package compile -m speed -f $launcher)

Step 'Making it Home'
# Order matters: the new launcher is proven to start before anything else is turned off, and the Home preference
# is set last, because Android drops it whenever the set of Home apps changes.
$turnedOff = @()
if ($stock) {
    if ($wizardOn) { Write-Host (Box pm disable-user --user 0 $wizard); $turnedOff += $wizard }
    Write-Host (Box pm disable-user --user 0 $stock); $turnedOff += $stock
}
[void](Box cmd package set-home-activity --user 0 "$launcher/.MainActivity")
$owner = Home-Owner
if (-not $owner.StartsWith("$launcher/")) {
    foreach ($pkg in $turnedOff) { [void](Box pm enable --user 0 $pkg) }
    if ($stock) {
        $back = ((Box cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $stock) -split "`n")[-1].Trim()
        [void](Box cmd package set-home-activity --user 0 $back)
    }
    Stop-Here "Home went to '$owner' instead of the launcher, so what was turned off was turned back on. Home is now '$(Home-Owner)'."
}
Write-Host "Home is $owner" -ForegroundColor Green

Step 'Turning on the remote-button helper'
[void](Box cmd appops set $launcher AUTO_START allow)
[void](Box cmd appops set $launcher APP_AUTO_START allow)
[void](Box appops write-settings)
$service = "$launcher/.InputKeyService"
$services = Box settings get secure enabled_accessibility_services
if ($services -eq 'null' -or -not $services) {
    $services = $service
} elseif ($services -notmatch 'InputKeyService') {
    $services = "${services}:$service"     # other apps' services, such as RustDesk's, stay on
} else {
    # Already listed: Android binds it again only when the list changes.
    [void](Box settings delete secure enabled_accessibility_services)
    [void](Box settings put secure accessibility_enabled 0)
    Start-Sleep -Seconds 1
}
[void](Box settings put secure enabled_accessibility_services $services)
[void](Box settings put secure accessibility_enabled 1)
if ((Box settings get secure enabled_accessibility_services) -notmatch 'InputKeyService') {
    Write-Host 'WARNING: the helper did not turn on. Remote buttons will not work until it does.' -ForegroundColor Yellow
} else { Write-Host 'Helper on' }

if ($Debloat) {
    Step 'Removing and turning off the listed apps'
    if ($model -ne $debloatModel) {
        Write-Host "SKIPPED: this list is for '$debloatModel' and this box is '$model'." -ForegroundColor Yellow
    } else {
        foreach ($pkg in $removed) {
            if (-not (Listed $pkg @())) { Write-Host "$pkg : not on this box"; continue }
            $answer = Box pm uninstall --user 0 $pkg
            if (Listed $pkg @()) { Write-Host "$pkg : STILL THERE ($answer)" -ForegroundColor Yellow }
            else { Write-Host "$pkg : removed" }
        }
        # One package per shell call: chained pm commands can silently stop after the first.
        foreach ($pkg in $listed) {
            if (-not (Listed $pkg @())) { Write-Host "$pkg : not on this box"; continue }
            $answer = Box pm disable-user --user 0 $pkg
            if (Enabled $pkg) { Write-Host "$pkg : STILL ON ($answer)" -ForegroundColor Yellow }
            else { Write-Host "$pkg : off" }
        }
    }
}

Write-Host ''
Write-Host 'Done. Now, on the TV:' -ForegroundColor Green
Write-Host '  1. Restore your layout: hold OK on Settings, Launcher settings, Backup and restore.'
Write-Host '  2. Check that a real press of each remapped button works, and that Update opens.'
Write-Host '  3. Turn off Wireless debugging and USB debugging, forget this PC, and restart the box.'
Write-Host '  4. Then run: install.bat <box ip> -CheckClosed'
