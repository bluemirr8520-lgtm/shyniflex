[CmdletBinding()]
param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

# Pin both the release and its bytes. Never select an unreviewed "latest" APK.
$mpvTag = '2026-09-17'
$mpvApkSha256 = 'c3b505e45b919b767b9867e7f0d16fd823d77bc7c9a6e6b181b80ea60c018a70'
$mpvSourceSha256 = '53446a665ee5231cc40a2e55133fbb625fe60786bbd94fac01d4300983fd2181'
$mpvApkUrl = "https://github.com/mpv-android/mpv-android/releases/download/$mpvTag/app-default-universal-release.apk"
$mpvSourceUrl = "https://github.com/mpv-android/mpv-android/archive/refs/tags/$mpvTag.zip"
$mpvSourceDir = Join-Path $PSScriptRoot 'runtime-source'
$mpvMainDir = Join-Path $PSScriptRoot 'app\src\main'
$mpvNativeDir = Join-Path $mpvMainDir 'jniLibs'
$mpvAssetDir = Join-Path $mpvMainDir 'assets'
$mpvLicenseDir = Join-Path $mpvAssetDir 'licenses'
foreach ($mpvDirectory in @($mpvSourceDir, $mpvNativeDir, $mpvAssetDir, $mpvLicenseDir)) {
    New-Item -ItemType Directory -Path $mpvDirectory -Force | Out-Null
}

function Get-VerifiedMpvArtifact {
    param([string]$Url, [string]$Destination, [string]$Sha256)
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) {
            Write-Output "Verified cached $(Split-Path -Leaf $Destination)"
            return
        }
    }
    if ($Offline) { throw "Missing or modified pinned artifact: $(Split-Path -Leaf $Destination). Run setup-mpv.ps1 without -Offline to download it." }
    $mpvDownloadPath = $Destination + '.download'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $mpvDownloadPath -UseBasicParsing -TimeoutSec 180 -MaximumRedirection 5
        $mpvDownloadedHash = (Get-FileHash -LiteralPath $mpvDownloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($mpvDownloadedHash -ne $Sha256) {
            throw "SHA256 mismatch for $(Split-Path -Leaf $Destination). The downloaded artifact was not installed."
        }
        Move-Item -LiteralPath $mpvDownloadPath -Destination $Destination -Force
    }
    finally {
        # Only remove this script's exact temporary file; never recursively clean a runtime folder.
        if (Test-Path -LiteralPath $mpvDownloadPath -PathType Leaf) { Remove-Item -LiteralPath $mpvDownloadPath -Force }
    }
}

$mpvApkPath = Join-Path $mpvSourceDir "mpv-android-$mpvTag.apk"
$mpvSourcesPath = Join-Path $mpvSourceDir "mpv-android-$mpvTag-source.zip"
Get-VerifiedMpvArtifact -Url $mpvApkUrl -Destination $mpvApkPath -Sha256 $mpvApkSha256
Get-VerifiedMpvArtifact -Url $mpvSourceUrl -Destination $mpvSourcesPath -Sha256 $mpvSourceSha256

Add-Type -AssemblyName System.IO.Compression.FileSystem
$mpvAbis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
$mpvLibraries = @(
    'libavcodec.so', 'libavdevice.so', 'libavfilter.so', 'libavformat.so', 'libavutil.so',
    'libc++_shared.so', 'libmpv.so', 'libplayer.so', 'libswresample.so', 'libswscale.so'
)
$mpvNativeHashes = @()
$mpvApkArchive = [IO.Compression.ZipFile]::OpenRead($mpvApkPath)
try {
    foreach ($mpvAbi in $mpvAbis) {
        $mpvAbiDir = Join-Path $mpvNativeDir $mpvAbi
        New-Item -ItemType Directory -Path $mpvAbiDir -Force | Out-Null
        foreach ($mpvLibrary in $mpvLibraries) {
            # Explicit archive entry names prevent ZIP path traversal and unintended extraction.
            $mpvEntryName = "lib/$mpvAbi/$mpvLibrary"
            $mpvEntry = $mpvApkArchive.GetEntry($mpvEntryName)
            if ($null -eq $mpvEntry) { throw "Pinned APK is missing $mpvEntryName" }
            $mpvTarget = Join-Path $mpvAbiDir $mpvLibrary
            [IO.Compression.ZipFileExtensions]::ExtractToFile($mpvEntry, $mpvTarget, $true)
            $mpvNativeHashes += [PSCustomObject]@{
                path = "app/src/main/jniLibs/$mpvAbi/$mpvLibrary"
                sha256 = (Get-FileHash -LiteralPath $mpvTarget -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    }
    $mpvCaEntry = $mpvApkArchive.GetEntry('assets/cacert.pem')
    if ($null -eq $mpvCaEntry) { throw 'Pinned APK is missing assets/cacert.pem' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($mpvCaEntry, (Join-Path $mpvAssetDir 'cacert.pem'), $true)
}
finally { $mpvApkArchive.Dispose() }

# Preserve the unmodified source archive, JNI declarations and MIT wrapper license.
$mpvSourceArchive = [IO.Compression.ZipFile]::OpenRead($mpvSourcesPath)
try {
    $mpvArchivePrefix = "mpv-android-$mpvTag/"
    $mpvLicenseEntry = $mpvSourceArchive.GetEntry($mpvArchivePrefix + 'LICENSE')
    $mpvJniEntry = $mpvSourceArchive.GetEntry($mpvArchivePrefix + 'app/src/main/java/is/xyz/mpv/MPVLib.kt')
    if ($null -eq $mpvLicenseEntry -or $null -eq $mpvJniEntry) { throw 'Pinned source archive is missing its JNI declarations or license.' }
    $mpvLicensePath = Join-Path $mpvSourceDir 'LICENSE.mpv-android'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($mpvLicenseEntry, $mpvLicensePath, $true)
    [IO.Compression.ZipFileExtensions]::ExtractToFile($mpvJniEntry, (Join-Path $mpvSourceDir 'MPVLib.kt'), $true)
    Copy-Item -LiteralPath $mpvLicensePath -Destination (Join-Path $mpvLicenseDir 'mpv-android-MIT.txt') -Force
}
finally { $mpvSourceArchive.Dispose() }

$mpvManifest = [ordered]@{
    release = $mpvTag
    apk_url = $mpvApkUrl
    apk_sha256 = $mpvApkSha256
    source_url = $mpvSourceUrl
    source_sha256 = $mpvSourceSha256
    native_libraries = $mpvNativeHashes
}
$mpvManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $mpvSourceDir 'native-manifest.json') -Encoding UTF8

$mpvNotice = @"
Shyni MPV Android native runtime

Official release: https://github.com/mpv-android/mpv-android/releases/tag/$mpvTag
APK: $mpvApkUrl
APK SHA256: $mpvApkSha256
Matching source: $mpvSourceUrl
Source SHA256: $mpvSourceSha256

The unmodified official native libraries are bundled for arm64-v8a, armeabi-v7a
and x86_64. The official CA bundle is copied from assets/cacert.pem.
The complete matching wrapper/build-recipe source archive is retained in
android/runtime-source. Individual installed library digests are recorded in
native-manifest.json. Run setup-mpv.ps1 -Offline to reproduce from cached bytes.

The JNI wrapper is MIT licensed. Its copyright and full license are preserved
as licenses/mpv-android-MIT.txt. JNI declarations must retain the exact package
is.xyz.mpv.MPVLib because the bundled libplayer.so resolves that class name.

The bundled libmpv and FFmpeg builds include GPL components and retain their
upstream licensing. Any redistribution must provide compatible licensing,
notices and corresponding sources. Exact dependency revisions are documented
on the pinned release page and in the matching upstream build recipes.

libmpv sources: https://github.com/mpv-player/mpv
FFmpeg sources: https://github.com/FFmpeg/FFmpeg
Build recipes: https://github.com/mpv-android/mpv-android/tree/$mpvTag/buildscripts
"@
[IO.File]::WriteAllText((Join-Path $mpvSourceDir 'NATIVE-RUNTIME.txt'), $mpvNotice, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $mpvLicenseDir 'NATIVE-RUNTIME.txt'), $mpvNotice, [Text.UTF8Encoding]::new($false))
Write-Output "Installed verified mpv-android ${mpvTag}: $($mpvLibraries.Count) libraries for each of $($mpvAbis.Count) ABIs, CA bundle and license notices."
