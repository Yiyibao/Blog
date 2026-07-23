#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Free TCP port 5173 on Windows by removing Hyper-V/WinNAT excluded ranges
  that cover developer ports, and move the dynamic port pool out of the way.

.DESCRIPTION
  Vite default port 5173 often fails with EACCES on Windows when Hyper-V /
  WinNAT excludes a random block such as 5141-5240. This script:
    1. Moves the IPv4 dynamic TCP port range to 49152-65535 (ephemeral range)
    2. Stops WinNAT so non-managed exclusions can be deleted
    3. Deletes any excluded range that overlaps 5173
    4. Restarts WinNAT
    5. Verifies that 127.0.0.1:5173 is bindable
#>

$ErrorActionPreference = 'Stop'
$TargetPort = 5173

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Test-PortBindable([int]$Port) {
  try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    $listener.Stop()
    return $true
  } catch {
    return $false
  }
}

function Get-ExcludedRanges {
  $output = netsh interface ipv4 show excludedportrange protocol=tcp | Out-String
  $ranges = @()
  foreach ($line in ($output -split "`r?`n")) {
    if ($line -match '^\s*(\d+)\s+(\d+)\s*(\*)?\s*$') {
      $ranges += [PSCustomObject]@{
        Start   = [int]$Matches[1]
        End     = [int]$Matches[2]
        Managed = [bool]$Matches[3]
      }
    }
  }
  return $ranges
}

Write-Host "Fix Windows excluded port ranges so Vite can bind :$TargetPort" -ForegroundColor Green
Write-Host "Machine: $env:COMPUTERNAME | $(Get-Date -Format o)"

Write-Step "Current excluded TCP ranges"
netsh interface ipv4 show excludedportrange protocol=tcp

Write-Step "Current dynamic TCP port range"
netsh int ipv4 show dynamicport tcp

Write-Step "Move dynamic TCP port range to 49152 (16384 ports)"
# Keeps Hyper-V/WinNAT from re-claiming low developer ports after reboot.
netsh int ipv4 set dynamicport tcp start=49152 num=16384 | Out-Null
netsh int ipv4 show dynamicport tcp

Write-Step "Stop WinNAT so exclusions can be modified"
$winnat = Get-Service -Name winnat -ErrorAction SilentlyContinue
if ($winnat -and $winnat.Status -eq 'Running') {
  Stop-Service -Name winnat -Force
  Start-Sleep -Seconds 2
  Write-Host "WinNAT stopped."
} else {
  Write-Host "WinNAT not running."
}

Write-Step "Delete excluded ranges that overlap port $TargetPort"
$ranges = Get-ExcludedRanges
$overlap = $ranges | Where-Object { $_.Start -le $TargetPort -and $_.End -ge $TargetPort }
if (-not $overlap) {
  Write-Host "No excluded range currently covers $TargetPort."
} else {
  foreach ($range in $overlap) {
    $count = $range.End - $range.Start + 1
    Write-Host ("Deleting exclusion {0}-{1} (managed={2})" -f $range.Start, $range.End, $range.Managed)
    if ($range.Managed) {
      Write-Host "  Managed range — will try delete after WinNAT stop; may need reboot if it fails." -ForegroundColor Yellow
    }
    $result = netsh int ipv4 delete excludedportrange protocol=tcp startport=$($range.Start) numberofports=$count 2>&1
    Write-Host ("  Result: {0}" -f ($result | Out-String).Trim())
  }
}

# Also remove neighboring low-port developer-hostile blocks that often reappear together
Write-Step "Delete other low developer-port exclusions below 10000 (best effort)"
$ranges = Get-ExcludedRanges
foreach ($range in $ranges) {
  if ($range.Start -lt 10000 -and -not $range.Managed) {
    $count = $range.End - $range.Start + 1
    Write-Host ("Deleting {0}-{1}" -f $range.Start, $range.End)
    netsh int ipv4 delete excludedportrange protocol=tcp startport=$($range.Start) numberofports=$count 2>&1 | Out-Null
  }
}

Write-Step "Start WinNAT again"
if ($winnat) {
  Start-Service -Name winnat
  Start-Sleep -Seconds 2
  Write-Host "WinNAT started."
}

Write-Step "Excluded ranges after fix"
netsh interface ipv4 show excludedportrange protocol=tcp

Write-Step "Verify bind on 127.0.0.1:$TargetPort"
$ok = Test-PortBindable -Port $TargetPort
if ($ok) {
  Write-Host "SUCCESS: port $TargetPort is bindable." -ForegroundColor Green
  exit 0
}

Write-Host "Port $TargetPort still not bindable. Trying one more WinNAT recycle..." -ForegroundColor Yellow
Stop-Service winnat -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Start-Service winnat -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
$ok = Test-PortBindable -Port $TargetPort
if ($ok) {
  Write-Host "SUCCESS after second recycle: port $TargetPort is bindable." -ForegroundColor Green
  exit 0
}

Write-Host "FAILED: port $TargetPort still blocked." -ForegroundColor Red
Write-Host "A reboot is often required so Hyper-V picks new exclusions from the high dynamic range."
Write-Host "After reboot, re-run this script if 5173 is still excluded."
exit 1
