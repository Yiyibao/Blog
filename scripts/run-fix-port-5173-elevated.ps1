$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'fix-port-5173.ps1'
$log = Join-Path $PSScriptRoot 'fix-port-5173.log'

if (Test-Path $log) { Remove-Item $log -Force }

$runner = @"
`$ErrorActionPreference = 'Continue'
try {
  & '$script' *>&1 | Tee-Object -FilePath '$log'
  exit `$LASTEXITCODE
} catch {
  `$_ | Out-String | Tee-Object -FilePath '$log'
  exit 1
}
"@

$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($runner))
$p = Start-Process -FilePath powershell.exe -Verb RunAs -ArgumentList @(
  '-NoProfile',
  '-ExecutionPolicy', 'Bypass',
  '-EncodedCommand', $encoded
) -Wait -PassThru

Write-Host ("Elevated process exit code: {0}" -f $p.ExitCode)
if (Test-Path $log) {
  Write-Host '=== fix-port-5173.log ==='
  Get-Content -Path $log -Raw
} else {
  Write-Host 'No log produced. UAC may have been denied.'
  exit 2
}
exit $p.ExitCode
