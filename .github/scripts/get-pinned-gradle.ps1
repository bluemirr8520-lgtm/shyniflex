$ErrorActionPreference = 'Stop'
$gradleAsset = 'https://api.github.com/repos/gradle/gradle-distributions/releases/assets/232373824'
$expectedHash = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$archive = Join-Path $env:RUNNER_TEMP 'shyniflex-gradle-8.13-bin.zip'
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseDefaultCredentials = $false
$handler.UseCookies = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(5)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('ShyniFlix-Build')
$client.DefaultRequestHeaders.Accept.ParseAdd('application/octet-stream')
try {
    $downloaded = $false
    for ($attempt = 1; $attempt -le 3 -and -not $downloaded; $attempt++) {
        # A fresh query avoids a stale cached redirect. No credentials are sent.
        $url = [Uri]($gradleAsset + '?shyniflex_retry=' + [DateTime]::UtcNow.Ticks + $attempt)
        try {
            for ($redirects = 0; $redirects -lt 4; $redirects++) {
                if ($url.Scheme -ne 'https' -or $url.Port -ne 443 -or $url.UserInfo -or
                    $url.Host -notin @('api.github.com', 'release-assets.githubusercontent.com')) {
                    throw 'Unexpected Gradle download host'
                }
                $response = $client.GetAsync($url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                try {
                    if ([int]$response.StatusCode -in @(301, 302, 303, 307, 308)) {
                        $next = $response.Headers.Location
                        if (-not $next) { throw 'Gradle download redirect missing' }
                        $url = if ($next.IsAbsoluteUri) { $next } else { [Uri]::new($url, $next) }
                        continue
                    }
                    $response.EnsureSuccessStatusCode() | Out-Null
                    $inputStream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                    try {
                        $outputStream = [IO.File]::Create($archive)
                        try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
                    } finally { $inputStream.Dispose() }
                    $downloaded = $true
                    break
                } finally { $response.Dispose() }
            }
            if (-not $downloaded) { throw 'Too many Gradle download redirects' }
        } catch {
            if ($attempt -eq 3) { throw 'Official Gradle download failed after retries' }
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        throw 'Official Gradle archive SHA-256 mismatch'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $env:RUNNER_TEMP -Force
    if (-not (Test-Path -LiteralPath (Join-Path $env:RUNNER_TEMP 'gradle-8.13/bin/gradle.bat'))) {
        throw 'Verified Gradle executable missing'
    }
    Write-Output 'Verified official Gradle 8.13 archive'
} finally {
    $client.Dispose()
    $handler.Dispose()
}
