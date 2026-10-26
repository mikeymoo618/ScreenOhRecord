param([switch]$Online)
# ScreenOhRecord build helper v1.0. Local signing material is never printed.
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$oldJavaHome = $env:JAVA_HOME
$oldSigningPassword = $env:SCREENOHRECORD_KEYSTORE_PASSWORD
$initialDirectory = Get-Location
try {
    Set-Location -LiteralPath $projectRoot
    if (-not $env:JAVA_HOME) {
        $bundledJdk = 'C:\Program Files\Android\Android Studio\jbr'
        if (Test-Path -LiteralPath (Join-Path $bundledJdk 'bin\java.exe')) { $env:JAVA_HOME = $bundledJdk }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
        throw 'Set JAVA_HOME to an installed JDK before building.'
    }
    $signingDirectory = Join-Path $projectRoot '.local-signing'
    $signingProperties = Join-Path $signingDirectory 'signing.properties'
    $keyStore = Join-Path $signingDirectory 'screenohrecord.p12'
    New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
    if ((Test-Path -LiteralPath $keyStore) -ne (Test-Path -LiteralPath $signingProperties)) {
        throw 'Signing material is incomplete. Restore both local signing files; no key was overwritten.'
    }
    if (-not (Test-Path -LiteralPath $keyStore)) {
        $randomBytes = New-Object byte[] 32
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
        $env:SCREENOHRECORD_KEYSTORE_PASSWORD = [Convert]::ToBase64String($randomBytes)
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -storetype PKCS12 `
            -keystore $keyStore -alias screenohrecord -keyalg RSA -keysize 3072 -validity 10000 `
            -dname 'CN=ScreenOhRecord Local Build' -storepass:env SCREENOHRECORD_KEYSTORE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Local signing-key generation failed.' }
        Set-Content -LiteralPath $signingProperties -Encoding ascii `
            -Value ('storePassword=' + $env:SCREENOHRECORD_KEYSTORE_PASSWORD)
    }
    $gradleOptions = @('--console=plain')
    if (-not $Online) { $gradleOptions += '--offline' }
    & '.\gradlew.bat' @gradleOptions assembleRelease lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed. See the complete Gradle output.' }
    New-Item -ItemType Directory -Force -Path 'artifacts' | Out-Null
    $versionLine = Get-Content 'app\build.gradle' | Where-Object { $_ -match "versionName '([^']+)'" }
    if ($versionLine -notmatch "versionName '([^']+)'") { throw 'Cannot read the app version.' }
    $output = Join-Path $projectRoot ('artifacts\ScreenOhRecord-' + $Matches[1] + '.apk')
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $output
    $digest = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($output + '.sha256') -Encoding ascii -Value ($digest + '  ' + (Split-Path $output -Leaf))
    Write-Output ('APK: ' + $output)
    Write-Output ('SHA-256: ' + $digest)
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:SCREENOHRECORD_KEYSTORE_PASSWORD = $oldSigningPassword
    Set-Location -LiteralPath $initialDirectory
}
