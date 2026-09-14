# IrisGUI control script (Windows / PowerShell) -- exported from app
# Runs the installed app (party.qwer.irisgui) as an app_process daemon over ADB (rooted).
# Usage: ./iris_control.ps1 {status|start|stop|restart} [-Port N]
# Note: messages are English on purpose (Windows PowerShell 5.1 mis-decodes BOM-less UTF-8).
param(
    [Parameter(Position=0)][ValidateSet('status','start','stop','restart')][string]$Action='status',
    [int]$Port=__PORT__
)
$PACKAGE="party.qwer.irisgui"; $MAINCLASS="party.qwer.irisgui.Main"

function Resolve-ApkPath {
  $l = adb shell "su root sh -c 'pm path $PACKAGE'" | Where-Object { $_ -match 'package:' } | Select-Object -First 1
  if (-not $l) { return $null }
  return ($l -replace 'package:','').Trim()
}
function Get-IrisPid {
  $l = adb shell "su root sh -c 'ps -ef'" | Where-Object { $_ -match [regex]::Escape($MAINCLASS) -and $_ -notmatch 'sh -c' } | Select-Object -First 1
  if (-not $l) { return $null }
  return (($l -split '\s+') | Where-Object { $_ -match '^[0-9]+$' } | Select-Object -First 1)
}
switch ($Action) {
  "status"  { $p=Get-IrisPid; if ($p) { "IrisGUI is running. PID: $p" } else { "IrisGUI is not running." } }
  "start"   {
    $p=Get-IrisPid; if ($p) { "Already running (PID: $p)"; break }
    $apk=Resolve-ApkPath; if (-not $apk) { "APK path not found. Is the app installed?"; break }
    "Starting on port $Port (APK: $apk)..."
    $cmd = "CLASSPATH=$apk setsid app_process / $MAINCLASS $Port > /dev/null 2>&1 < /dev/null &"
    adb shell "su root sh -c '$cmd'"
    Start-Sleep 3; $np=Get-IrisPid; if ($np) { "Started. PID: $np" } else { "Failed to start." }
  }
  "stop"    { $p=Get-IrisPid; if (-not $p) { "IrisGUI is not running."; break }; adb shell "su root sh -c 'pkill -f $MAINCLASS'"; "Stop requested." }
  "restart" {
    adb shell "su root sh -c 'pkill -f $MAINCLASS'"; Start-Sleep 1
    $apk=Resolve-ApkPath; if ($apk) { $cmd = "CLASSPATH=$apk setsid app_process / $MAINCLASS $Port > /dev/null 2>&1 < /dev/null &"; adb shell "su root sh -c '$cmd'" }
  }
}
