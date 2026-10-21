param(
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$CorrespondingSourcePath,
    [string]$ApkPath = (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'),
    [string]$AndroidSdkPath = $env:ANDROID_HOME,
    [string]$Notes = 'Android TV improvements and automatic update support.'
)
$ErrorActionPreference = 'Stop'
$taskRepository = 'bluemirr8520-lgtm/shyniflex'
$taskApk = (Resolve-Path -LiteralPath $ApkPath).Path
$taskSource = (Resolve-Path -LiteralPath $CorrespondingSourcePath).Path
if (-not (Test-Path -LiteralPath $taskApk -PathType Leaf)) { throw 'Build the APK first.' }
if ($Tag -notmatch '^[A-Za-z0-9._-]+$') { throw 'Use a simple, unique release tag.' }
$taskAapt = Join-Path $AndroidSdkPath 'build-tools/36.0.0/aapt.exe'
if (-not (Test-Path -LiteralPath $taskAapt)) { throw 'Android SDK build-tools 36.0.0 are required.' }
$taskBadging = & $taskAapt dump badging $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the APK.' }
$taskPackageLine = $taskBadging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
if ($taskPackageLine -notmatch "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") { throw 'Invalid APK metadata.' }
$taskPackageName = $Matches[1]; $taskVersionCode = [long]$Matches[2]; $taskVersionName = $Matches[3]
if ($taskPackageName -notin 'io.mirr.shyniflex','io.mirr.shyniflex.debug') { throw 'Unexpected APK package.' }
$taskAssetName = if ($taskPackageName.EndsWith('.debug')) { 'shyniflex-tv.apk' } else { 'shyniflex.apk' }
$taskHash = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
$taskSourceHash = (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant()
$taskManifest = @{ schema = 1; packages = @{ $taskPackageName = @{ versionCode = $taskVersionCode;
    versionName = $taskVersionName; asset = $taskAssetName; sha256 = $taskHash } } }
$taskManifestText = $taskManifest | ConvertTo-Json -Depth 8
$taskToken = $env:GH_TOKEN
try {
    if (-not $taskToken) {
        $env:GIT_TERMINAL_PROMPT = '0'; $env:GCM_INTERACTIVE = 'Never'
        $taskCredential = @('protocol=https','host=github.com','') | git credential fill
        if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication is required.' }
        $taskToken = ($taskCredential | Where-Object { $_ -like 'password=*' } | Select-Object -First 1).Substring(9)
        $taskCredential = $null
    }
    if (-not $taskToken) { throw 'GitHub authentication is required.' }
    $taskHeaders = @{ Authorization = "Bearer $taskToken"; Accept = 'application/vnd.github+json';
        'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'ShyniFlix-Publisher' }
    $taskApi = "https://api.github.com/repos/$taskRepository"
    $taskRelease = $null
    try { $taskRelease = Invoke-RestMethod -Uri "$taskApi/releases/tags/$Tag" -Headers $taskHeaders }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw 'Unable to inspect the GitHub release.' } }
    if ($taskRelease -and -not $taskRelease.draft) { throw 'This tag is already published. Increase versionCode and use a new tag.' }
    if (-not $taskRelease) {
        $taskCommit = git -C (Split-Path $PSScriptRoot -Parent) rev-parse HEAD
        if ($LASTEXITCODE -ne 0) { throw 'No source commit is available.' }
        $taskBody = @{ tag_name = $Tag; target_commitish = $taskCommit; name = "ShyniFlix TV $taskVersionName";
            body = $Notes; draft = $true; prerelease = $false } | ConvertTo-Json
        $taskRelease = Invoke-RestMethod -Method Post -Uri "$taskApi/releases" -Headers $taskHeaders -ContentType 'application/json' -Body $taskBody
    }
    $taskReleaseId = [long]$taskRelease.id
    if ($taskReleaseId -le 0) { throw 'Invalid release identifier.' }
    # A retry may replace only draft assets, never an already published update.
    foreach ($taskAsset in $taskRelease.assets) {
        if ($taskAsset.name -in @($taskAssetName,'shyniflex-update.json','shyniflex-corresponding-source.zip','SHA256SUMS.txt')) {
            Invoke-RestMethod -Method Delete -Uri "$taskApi/releases/assets/$($taskAsset.id)" -Headers $taskHeaders | Out-Null
        }
    }
    $taskUpload = "https://uploads.github.com/repos/$taskRepository/releases/$taskReleaseId/assets"
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=$taskAssetName" -Headers $taskHeaders -ContentType 'application/vnd.android.package-archive' -InFile $taskApk | Out-Null
    $taskBytes = [Text.UTF8Encoding]::new($false).GetBytes($taskManifestText)
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=shyniflex-update.json" -Headers $taskHeaders -ContentType 'application/json' -Body $taskBytes | Out-Null
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=shyniflex-corresponding-source.zip" -Headers $taskHeaders -ContentType 'application/zip' -InFile $taskSource | Out-Null
    $taskSums = "$taskHash  $taskAssetName`n$taskSourceHash  shyniflex-corresponding-source.zip`n"
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=SHA256SUMS.txt" -Headers $taskHeaders -ContentType 'text/plain' -Body ([Text.UTF8Encoding]::new($false).GetBytes($taskSums)) | Out-Null
    $taskRelease = Invoke-RestMethod -Method Patch -Uri "$taskApi/releases/$taskReleaseId" -Headers $taskHeaders -ContentType 'application/json' -Body '{"draft":false,"make_latest":"true"}'
    Write-Output "Published $($taskRelease.html_url)"
    Write-Output "Package $taskPackageName; versionCode $taskVersionCode; SHA-256 $taskHash"
} finally {
    $taskToken = $null; $taskHeaders = $null; $taskCredential = $null
}
