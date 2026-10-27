<#
  Downloads Wintun's native DLL into bin\. The sing-box engine is compiled
  into RXFOXYVPN from the Go module and is no longer downloaded as sing-box.exe.

    powershell -ExecutionPolicy Bypass -File scripts\prepare_runtime.ps1
#>
param([string]$WintunVersion = "0.14.1")
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root "bin"
$tmp = Join-Path $env:TEMP "rxvpn-runtime"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $runtime, $tmp | Out-Null
$wzip = Join-Path $tmp "wintun.zip"
Invoke-WebRequest "https://www.wintun.net/builds/wintun-$WintunVersion.zip" -OutFile $wzip
Expand-Archive $wzip -DestinationPath (Join-Path $tmp "wintun") -Force
$source = Join-Path $tmp "wintun\wintun\bin\amd64\wintun.dll"
if (-not (Test-Path $source)) { throw "Wintun DLL was not found in the downloaded archive" }
Copy-Item $source (Join-Path $runtime "wintun.dll") -Force
Remove-Item $tmp -Recurse -Force
Write-Host "Wintun DLL ready in $runtime"
