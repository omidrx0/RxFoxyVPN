<#
  Builds dist\RXFOXYVPN.exe (Windows, amd64, no CGO).
  Editable product metadata lives in scripts\version-info.json.

  powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Embed -UPX -Version "1.0.1"
#>
param(
    [switch]$Embed,
    [switch]$UPX,
    [string]$Version = "dev"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path "bin\wintun.dll")) {
    Write-Host "TUN runtime missing - downloading it first ..."
    & "$PSScriptRoot\prepare_runtime.ps1"
    if ($LASTEXITCODE -ne 0) { throw "Runtime preparation failed." }
}

go mod tidy
if ($LASTEXITCODE -ne 0) { throw "go mod tidy failed." }

# Read metadata from one editable JSON file and synchronize version numbers.
$metadataPath = Join-Path $PSScriptRoot "version-info.json"
$metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
# goversioninfo accepts icon and manifest as command-line flags.
$iconPath = Join-Path $root "packaging\icon.ico"
$manifestPath = Join-Path $root "packaging\rxvpn.manifest"
if (-not (Test-Path $iconPath)) { throw "Application icon not found: $iconPath" }
if (-not (Test-Path $manifestPath)) { throw "Application manifest not found: $manifestPath" }
if ($Version -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') {
    throw "Version must be numeric, for example 1.0.1 or 1.0.1.0. Received: $Version"
}
$parts = @($Version.Split('.') | ForEach-Object { [int]$_ })
while ($parts.Count -lt 4) { $parts += 0 }
if ($parts.Count -gt 4) { throw "Version can contain at most four numeric parts." }
$version4 = ($parts -join '.')
$metadata.StringFileInfo.FileVersion = $version4
$metadata.StringFileInfo.ProductVersion = $version4
$metadata.FixedFileInfo.FileVersion.Major = $parts[0]
$metadata.FixedFileInfo.FileVersion.Minor = $parts[1]
$metadata.FixedFileInfo.FileVersion.Patch = $parts[2]
$metadata.FixedFileInfo.FileVersion.Build = $parts[3]
$metadata.FixedFileInfo.ProductVersion.Major = $parts[0]
$metadata.FixedFileInfo.ProductVersion.Minor = $parts[1]
$metadata.FixedFileInfo.ProductVersion.Patch = $parts[2]
$metadata.FixedFileInfo.ProductVersion.Build = $parts[3]
$tempMetadata = Join-Path $env:TEMP "rxfoxyvpn-version-info.json"
$jsonText = $metadata | ConvertTo-Json -Depth 20
[System.IO.File]::WriteAllText($tempMetadata, $jsonText, [System.Text.UTF8Encoding]::new($false))

# goversioninfo embeds Windows Details metadata, the icon, and the manifest.
go install github.com/josephspurrier/goversioninfo/cmd/goversioninfo@latest
if ($LASTEXITCODE -ne 0) { throw "Installing goversioninfo failed." }
$goversioninfo = Join-Path (go env GOPATH) "bin\goversioninfo.exe"
if (-not (Test-Path $goversioninfo)) { throw "goversioninfo.exe was not found in GOPATH\bin." }
& $goversioninfo -64 "-icon=$iconPath" "-manifest=$manifestPath" "-o=cmd\rxvpn\rsrc_windows_amd64.syso" $tempMetadata
if ($LASTEXITCODE -ne 0) { throw "Generating Windows version resources failed." }

$env:GOOS = "windows"; $env:GOARCH = "amd64"; $env:CGO_ENABLED = "0"
$goArgs = @("build", "-trimpath", "-ldflags", "-H windowsgui -s -w -X main.version=$Version", "-o", "dist\RXFOXYVPN.exe")
# Go 1.27 disables the legacy HTTP/2 transport by default; sing-box v1.12 uses its legacy internals.
if ($Embed) { $goArgs += @("-tags", "http2legacy,embedruntime") } else { $goArgs += @("-tags", "http2legacy") }
$goArgs += "./cmd/rxvpn"
New-Item -ItemType Directory -Force dist | Out-Null
& go @goArgs
if ($LASTEXITCODE -ne 0) { throw "go build failed with exit code $LASTEXITCODE; output was not confirmed as rebuilt" }
if (-not $Embed) {
    New-Item -ItemType Directory -Force dist\bin | Out-Null
    Copy-Item bin\wintun.dll dist\bin -Force
}
if ($UPX) {
    $upxCommand = Get-Command upx.exe -ErrorAction SilentlyContinue
    if (-not $upxCommand) { $upxCommand = Get-Command upx -ErrorAction SilentlyContinue }
    if (-not $upxCommand) {
        throw "UPX was requested but not found. Add the folder containing upx.exe to PATH, then rerun with -UPX. The uncompressed build is at dist\RXFOXYVPN.exe."
    }
    $beforeBytes = (Get-Item "dist\RXFOXYVPN.exe").Length
    & $upxCommand.Source --best --lzma "dist\RXFOXYVPN.exe"
    if ($LASTEXITCODE -ne 0) { throw "UPX failed with exit code $LASTEXITCODE. Keep/test the uncompressed build before distribution." }
    $afterBytes = (Get-Item "dist\RXFOXYVPN.exe").Length
    Write-Host ("UPX compressed: {0} MB -> {1} MB" -f [math]::Round($beforeBytes / 1MB, 2), [math]::Round($afterBytes / 1MB, 2))
}
Remove-Item $tempMetadata -Force -ErrorAction SilentlyContinue
Write-Host "Built dist\RXFOXYVPN.exe (version $Version)"
