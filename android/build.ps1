param(
    [string]$JdkPath = 'C:/TMP/plexplay-android-tools/jdk/jdk-17.0.19+10',
    [string]$AndroidSdkPath = 'C:/TMP/plexplay-android-tools/android-sdk',
    [switch]$Online,
    [switch]$WithDeviceTestApks,
    [switch]$SkipTests,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$taskProject = $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin/java.exe'))) { throw 'A Java 17 JDK is required. Pass -JdkPath.' }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdkPath 'platforms/android-36/android.jar'))) { throw 'Android SDK platform 36 is required. Pass -AndroidSdkPath.' }
$env:JAVA_HOME = $JdkPath
$env:ANDROID_HOME = $AndroidSdkPath
Set-Content -LiteralPath (Join-Path $taskProject 'local.properties') -Value ('sdk.dir=' + $AndroidSdkPath.Replace('\','/')) -Encoding ascii

$taskSigningDir = Join-Path $taskProject '.local'
New-Item -ItemType Directory -Path $taskSigningDir -Force | Out-Null
$taskSigningProperties = Join-Path $taskSigningDir 'release.properties'
if (-not (Test-Path -LiteralPath $taskSigningProperties)) {
    $taskKeyFile = Join-Path $taskSigningDir 'shyniflex-release.p12'
    if (Test-Path -LiteralPath $taskKeyFile) { throw 'An existing signing key has no settings. Restore its release.properties before building.' }
    $taskRandom = [byte[]]::new(32)
    $taskGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskGenerator.GetBytes($taskRandom) } finally { $taskGenerator.Dispose() }
    $taskPassword = [Convert]::ToBase64String($taskRandom)
    $env:SHYNIFLEX_NEW_KEY_PASSWORD = $taskPassword
    try {
        & (Join-Path $JdkPath 'bin/keytool.exe') -genkeypair -keystore $taskKeyFile -alias shyniflex -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12 -storepass:env SHYNIFLEX_NEW_KEY_PASSWORD -keypass:env SHYNIFLEX_NEW_KEY_PASSWORD -dname 'CN=shyniflex, OU=Personal Apps, O=shyniflex, C=KR'
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
        $taskProperties = "storeFile=.local/shyniflex-release.p12`nstorePassword=$taskPassword`nkeyAlias=shyniflex`nkeyPassword=$taskPassword`n"
        [System.IO.File]::WriteAllText($taskSigningProperties, $taskProperties, [System.Text.UTF8Encoding]::new($false))
        $taskIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls.exe $taskSigningDir /inheritance:r /grant:r "*${taskIdentity}:(OI)(CI)F" /Q | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict signing-key directory access.' }
    } finally {
        Remove-Item Env:\SHYNIFLEX_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
        $taskPassword = $null
        $taskProperties = $null
    }
}

if ($PrepareOnly) { Write-Output 'Android SDK and local shyniflex signing key are ready.'; return }

Push-Location $taskProject
try {
    $taskArguments = @('assembleDebug', 'assembleRelease', "-PshyniflexSigningRoot=$taskProject", '--no-daemon', '--max-workers=2', '--console=plain')
    if (-not $SkipTests) { $taskArguments += 'testDebugUnitTest' }
    if ($WithDeviceTestApks) { $taskArguments += 'assembleDebugAndroidTest' }
    if (-not $Online) { $taskArguments += '--offline' }
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed. For missing cached dependencies, retry with -Online.' }
} finally { Pop-Location }
Write-Output ('APK: ' + (Join-Path $taskProject 'app/build/outputs/apk/release/app-release.apk'))
