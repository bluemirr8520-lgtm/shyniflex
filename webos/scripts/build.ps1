[CmdletBinding()]
param(
  [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$webOsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $webOsRoot
$appRoot = Join-Path $webOsRoot 'app'
$distRoot = Join-Path $webOsRoot 'dist'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
  $OutputDirectory = Join-Path $repoRoot 'outputs'
}

$pnpmCommand = Get-Command pnpm.cmd, pnpm -ErrorAction SilentlyContinue |
  Select-Object -First 1
if ($null -eq $pnpmCommand) {
  throw 'pnpm was not found on PATH. Install Node.js and pnpm before building.'
}

# The Codex desktop pnpm shim keeps its private Node runtime next to the shim,
# while ordinary developer machines already expose node.exe on PATH.
if ($null -eq (Get-Command node.exe, node -ErrorAction SilentlyContinue | Select-Object -First 1)) {
  $shimNodeRoot = [System.IO.Path]::GetFullPath(
    (Join-Path (Split-Path -Parent $pnpmCommand.Source) '..\..\node\bin')
  )
  if (Test-Path -LiteralPath (Join-Path $shimNodeRoot 'node.exe') -PathType Leaf) {
    $env:PATH = "$shimNodeRoot;$env:PATH"
  } else {
    throw 'Node.js was not found on PATH.'
  }
}

$pnpm = $pnpmCommand.Source

$metadata = Get-Content -LiteralPath (Join-Path $appRoot 'appinfo.json') -Raw | ConvertFrom-Json
$required = 'id', 'version', 'type', 'main', 'title', 'icon'
foreach ($key in $required) {
  if ([string]::IsNullOrWhiteSpace([string]$metadata.$key)) {
    throw "appinfo.json is missing required field: $key"
  }
}
if ($metadata.type -ne 'web') {
  throw 'appinfo.json type must be web'
}
if ($metadata.version -notmatch '^\d+\.\d+\.\d+$') {
  throw 'webOS version must contain exactly three numeric components'
}
foreach ($relativePath in $metadata.main, $metadata.icon, $metadata.largeIcon) {
  if (-not (Test-Path -LiteralPath (Join-Path $appRoot $relativePath) -PathType Leaf)) {
    throw "Required app resource is missing: $relativePath"
  }
}

Push-Location $webOsRoot
try {
  # Packaging does not need the CLI's SSH/native optional dependency scripts.
  # Keeping install scripts disabled also makes this release build reproducible.
  & $pnpm install --ignore-scripts --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed' }

  & $pnpm test
  if ($LASTEXITCODE -ne 0) { throw 'webOS JavaScript tests failed' }

  New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
  $aresPackage = Join-Path $webOsRoot 'node_modules\.bin\ares-package.cmd'
  & $aresPackage --no-minify -o $distRoot $appRoot
  if ($LASTEXITCODE -ne 0) { throw 'ares-package failed' }

  $package = Get-ChildItem -LiteralPath $distRoot -Filter '*.ipk' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($null -eq $package) { throw 'ares-package did not create an IPK' }

  & $aresPackage -I $package.FullName
  if ($LASTEXITCODE -ne 0) { throw 'ares-package inspection failed' }

  New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
  $finalPath = Join-Path $OutputDirectory 'shyniflex-webos-tv.ipk'
  Copy-Item -LiteralPath $package.FullName -Destination $finalPath -Force
  $hash = Get-FileHash -LiteralPath $finalPath -Algorithm SHA256
  Set-Content -LiteralPath "$finalPath.sha256" -Value "$($hash.Hash.ToLowerInvariant())  shyniflex-webos-tv.ipk" -Encoding ascii

  Write-Host "webOS package complete: $finalPath"
  Write-Host "SHA-256: $($hash.Hash)"
} finally {
  Pop-Location
}
