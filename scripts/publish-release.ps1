param([string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
# ScreenOhRecord release publisher v1.2.0. Only this project's explicit source/assets are published.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
Set-Location -LiteralPath $projectRoot
$repository = 'mikeymoo618/ScreenOhRecord'
$remote = 'https://github.com/' + $repository + '.git'
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'never'
function Invoke-ProjectGit([string[]]$Arguments) {
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Git operation failed: ' + ($Arguments -join ' ')) }
}
$gradle = Get-Content 'app\build.gradle' -Raw
$version = [regex]::Match($gradle, "(?m)^\s*versionName '([0-9]+\.[0-9]+\.[0-9]+)'\s*$").Groups[1].Value
$codeText = [regex]::Match($gradle, '(?m)^\s*versionCode ([0-9]+)\s*$').Groups[1].Value
if (-not $version -or -not $codeText) { throw 'Cannot read release identity from build.gradle' }
$code = [int]$codeText
$apkName = 'ScreenOhRecord-' + $version + '.apk'
$apk = Join-Path $projectRoot ('artifacts\' + $apkName)
$verificationPath = Join-Path $projectRoot ('artifacts\verification-' + $version + '.json')
$verification = Get-Content -LiteralPath $verificationPath -Raw | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($verification.version -ne $version -or $verification.versionCode -ne $code -or $verification.sha256 -ne $hash -or
        -not $verification.buildPassed -or -not $verification.runtimePassed -or $verification.lintErrors -ne 0) {
    throw 'Matching successful build/runtime verification is required before publication'
}
$testLog = Join-Path $projectRoot $verification.testLog
if ((Get-Content -LiteralPath $testLog -Raw) -match 'FAIL:|Process crashed|INSTRUMENTATION_FAILED') { throw 'Test log contains a failure' }
$java = Join-Path 'C:\Program Files\Android\Android Studio\jbr' 'bin\java.exe'
$signature = & $java -jar (Join-Path $SdkRoot 'build-tools\36.0.0\lib\apksigner.jar') verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0 -or ($signature -join "`n") -notmatch '7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8') { throw 'Release signature verification failed' }
$notes = Get-Content 'RELEASE-NOTES.md' -Raw
if ($notes -notmatch [regex]::Escape($version)) { throw 'Release notes do not match this version' }
$manifestPath = Join-Path $projectRoot 'artifacts\update.json'
$metadata = [ordered]@{schema=1;versionCode=$code;versionName=$version;apkUrl=('https://github.com/'+$repository+'/releases/download/v'+$version+'/'+$apkName);size=(Get-Item $apk).Length;sha256=$hash;notes=$notes}
[IO.File]::WriteAllText($manifestPath, ($metadata | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
$checksumPath = $apk + '.sha256'
[IO.File]::WriteAllText($checksumPath, ($hash + '  ' + $apkName + "`n"), [Text.UTF8Encoding]::new($false))

# Credentials are obtained only from Git's existing local credential manager, never written to disk/logs.
$credentialLines = @('protocol=https','host=github.com','') | & git -c credential.interactive=never credential fill
if ($LASTEXITCODE -ne 0) { throw 'GitHub credentials unavailable' }
$passwordLine = $credentialLines | Where-Object { $_.StartsWith('password=') }
if (-not $passwordLine) { throw 'No GitHub credential returned' }
$headers = @{Authorization=('Bearer '+$passwordLine.Substring(9));Accept='application/vnd.github+json';'X-GitHub-Api-Version'='2022-11-28';'User-Agent'='ScreenOhRecord-publisher'}
$credentialLines=$null; $passwordLine=$null
try {
    $repo = Invoke-RestMethod -Uri ('https://api.github.com/repos/'+$repository) -Headers $headers
    if ($repo.private -or -not $repo.permissions.push) { throw 'Public repository with push access required' }
    $existing = $null
    try { $existing = Invoke-RestMethod -Uri ('https://api.github.com/repos/'+$repository+'/releases/tags/v'+$version) -Headers $headers }
    catch { if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw 'Cannot inspect existing GitHub release' } }
    if ($existing) { throw 'This version already has a release. Do not overwrite it; increment the version.' }

    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.git'))) {
        Invoke-ProjectGit -Arguments @('init','-b','main')
        Invoke-ProjectGit -Arguments @('remote','add','origin',$remote)
        Invoke-ProjectGit -Arguments @('fetch','origin','main')
        # Initialize the index/HEAD from the existing remote without overwriting local source files.
        Invoke-ProjectGit -Arguments @('reset','--mixed','origin/main')
    }
    $actualRoot = (& git rev-parse --show-toplevel).Trim()
    if ($LASTEXITCODE -ne 0 -or -not [string]::Equals([IO.Path]::GetFullPath($actualRoot),$projectRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to publish a parent or unrelated Git repository' }
    $origin = (& git remote get-url origin).Trim()
    if ($origin -ne $remote) { throw 'Unexpected origin repository' }
    Invoke-ProjectGit -Arguments @('fetch','origin','main')
    & git merge-base --is-ancestor origin/main HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Remote history changed; integrate it before publishing' }
    $user = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers
    Invoke-ProjectGit -Arguments @('config','user.name',$user.login)
    Invoke-ProjectGit -Arguments @('config','user.email',([string]$user.id+'+'+$user.login+'@users.noreply.github.com'))
    # No artifacts, credentials, signing keys, recordings, analysis directories or test runtimes.
    Invoke-ProjectGit -Arguments @('add','--','.gitignore','AGENTS.md','README.md','DESIGN.md','TESTING.md','updates.md','RELEASE-PLAN-1.2.0.md','RELEASE-NOTES.md','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','gradle','build.ps1','app/build.gradle','app/src','scripts')
    $staged = & git diff --cached --name-only
    foreach ($path in $staged) {
        if ($path -match '(^|/)(\.local-signing|\.testing|\.publishing|artifacts|build)/|local\.properties|\.(p12|jks|keystore|apk|xapk|mp4)$') { throw ('Refusing sensitive/generated path: '+$path) }
    }
    & git diff --cached --quiet
    if ($LASTEXITCODE -eq 1) { Invoke-ProjectGit -Arguments @('commit','-m',('feat: release ScreenOhRecord '+$version)) }
    elseif ($LASTEXITCODE -ne 0) { throw 'Cannot inspect staged source' }
    Invoke-ProjectGit -Arguments @('tag','-a',('v'+$version),'-m',('ScreenOhRecord '+$version))
    Invoke-ProjectGit -Arguments @('push','-u','origin','main')
    Invoke-ProjectGit -Arguments @('push','origin',('v'+$version))
    $commit = (& git rev-parse HEAD).Trim()
    $body = @{tag_name=('v'+$version);target_commitish=$commit;name=('ScreenOhRecord '+$version);body=$notes;draft=$true;prerelease=$false} | ConvertTo-Json -Depth 10
    $release = Invoke-RestMethod -Method Post -Uri ('https://api.github.com/repos/'+$repository+'/releases') -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    $upload = $release.upload_url.Split('{')[0]
    if (([Uri]$upload).Host -ne 'uploads.github.com' -or ([Uri]$upload).Scheme -ne 'https') { throw 'Unexpected GitHub upload host' }
    foreach ($file in @($apk,$checksumPath,$manifestPath)) {
        $assetName = [Uri]::EscapeDataString((Split-Path -Leaf $file))
        $asset = Invoke-RestMethod -Method Post -Uri ($upload+'?name='+$assetName) -Headers $headers -ContentType 'application/octet-stream' -InFile $file
        if ($asset.size -ne (Get-Item $file).Length) { throw 'Uploaded release asset size mismatch; release remains draft' }
    }
    $published = Invoke-RestMethod -Method Patch -Uri ('https://api.github.com/repos/'+$repository+'/releases/'+$release.id) -Headers $headers -ContentType 'application/json' -Body '{"draft":false,"make_latest":"true"}'
    Write-Output ('Published '+$published.html_url)
} finally { $headers.Clear() }


