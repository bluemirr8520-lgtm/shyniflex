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
$taskIsPlex = $taskPackageName -eq 'io.mirr.shyniflex.plex'
if ($taskPackageName -notin 'io.mirr.shyniflex','io.mirr.shyniflex.debug','io.mirr.shyniflex.renewed','io.mirr.shyniflex.plex') { throw 'Unexpected APK package.' }
if ($taskIsPlex -or $taskPackageName -eq 'io.mirr.shyniflex.renewed') {
    $taskExpectedSigner = if ($taskIsPlex) { '771172249ed6bd21eac6d024a2fe58dc0f18d46f5f84dd52e8406fede5aec8e7' } else { '8d4bfd6534ce913ce90ca176878778ef24b6ebd1c4f7790972d2bcdae9b31c79' }
    $taskSigner = Join-Path $AndroidSdkPath 'build-tools/36.0.0/apksigner.bat'
    $taskCertificate = & $taskSigner verify --print-certs $taskApk
    if ($LASTEXITCODE -ne 0 -or -not ($taskCertificate -match "certificate SHA-256 digest: ${taskExpectedSigner}$")) { throw "Unexpected signing certificate for $taskPackageName." }
}
$taskAssetName = if ($taskIsPlex) { "PLEXMINI-$taskVersionName.apk" } elseif ($taskPackageName.EndsWith('.debug')) { 'shyniflex-tv.apk' } elseif ($taskPackageName.EndsWith('.renewed')) { 'shyniflex-new.apk' } else { 'shyniflex.apk' }
$taskManifestName = if ($taskIsPlex) { 'plexmini-update.json' } else { 'shyniflex-update.json' }
$taskSourceName = if ($taskIsPlex) { "PLEXMINI-$taskVersionName-corresponding-source.zip" } else { 'shyniflex-corresponding-source.zip' }
$taskTitle = if ($taskIsPlex) { "PLEXMINI $taskVersionName" } else { "ShyniFlix TV $taskVersionName" }
if ($taskIsPlex -and ($taskVersionName -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$' -or $Tag -cne "plexmini-v$taskVersionName")) { throw 'The PLEXMINI tag must match the APK version.' }
if (-not $taskIsPlex -and $Tag.StartsWith('plexmini-v')) { throw 'PLEXMINI tags are reserved for PLEXMINI packages.' }
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
    $taskCommit = git -C (Split-Path $PSScriptRoot -Parent) rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'No source commit is available.' }
    if ($taskIsPlex) {
        $taskTaggedCommit = Invoke-RestMethod -Uri "$taskApi/commits/$Tag" -Headers $taskHeaders
        if ($taskTaggedCommit.sha -ne $taskCommit) { throw 'Check out the published PLEXMINI source tag before publishing.' }
    }
    $taskRelease = $null
    try { $taskRelease = Invoke-RestMethod -Uri "$taskApi/releases/tags/$Tag" -Headers $taskHeaders }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw 'Unable to inspect the GitHub release.' } }
    if ($taskRelease -and -not $taskRelease.draft) { throw 'This tag is already published. Increase versionCode and use a new tag.' }
    if (-not $taskRelease) {
        $taskBody = @{ tag_name = $Tag; target_commitish = $taskCommit; name = $taskTitle;
            body = $Notes; draft = $true; prerelease = $false } | ConvertTo-Json
        $taskRelease = Invoke-RestMethod -Method Post -Uri "$taskApi/releases" -Headers $taskHeaders -ContentType 'application/json' -Body $taskBody
    }
    $taskReleaseId = [long]$taskRelease.id
    if ($taskReleaseId -le 0) { throw 'Invalid release identifier.' }
    # A retry may replace only draft assets, never an already published update.
    foreach ($taskAsset in $taskRelease.assets) {
        if ($taskAsset.name -in @($taskAssetName,$taskManifestName,$taskSourceName,'SHA256SUMS.txt')) {
            Invoke-RestMethod -Method Delete -Uri "$taskApi/releases/assets/$($taskAsset.id)" -Headers $taskHeaders | Out-Null
        }
    }
    $taskUpload = "https://uploads.github.com/repos/$taskRepository/releases/$taskReleaseId/assets"
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=$taskAssetName" -Headers $taskHeaders -ContentType 'application/vnd.android.package-archive' -InFile $taskApk | Out-Null
    $taskBytes = [Text.UTF8Encoding]::new($false).GetBytes($taskManifestText)
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=$taskManifestName" -Headers $taskHeaders -ContentType 'application/json' -Body $taskBytes | Out-Null
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=$taskSourceName" -Headers $taskHeaders -ContentType 'application/zip' -InFile $taskSource | Out-Null
    $taskSums = "$taskHash  $taskAssetName`n$taskSourceHash  $taskSourceName`n"
    Invoke-RestMethod -Method Post -Uri "${taskUpload}?name=SHA256SUMS.txt" -Headers $taskHeaders -ContentType 'text/plain' -Body ([Text.UTF8Encoding]::new($false).GetBytes($taskSums)) | Out-Null
    $taskMakeLatest = if ($taskIsPlex) { 'false' } else { 'true' }
    $taskPublish = @{ draft = $false; make_latest = $taskMakeLatest } | ConvertTo-Json
    $taskRelease = Invoke-RestMethod -Method Patch -Uri "$taskApi/releases/$taskReleaseId" -Headers $taskHeaders -ContentType 'application/json' -Body $taskPublish
    Write-Output "Published $($taskRelease.html_url)"
    Write-Output "Package $taskPackageName; versionCode $taskVersionCode; SHA-256 $taskHash"
} finally {
    $taskToken = $null; $taskHeaders = $null; $taskCredential = $null
}
