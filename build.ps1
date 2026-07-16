<#
.SYNOPSIS
    Builds the Glyph Bar app, provisioning the whole toolchain on first run.

.DESCRIPTION
    Needs nothing preinstalled - no JDK, no Android SDK, no Gradle, no admin
    rights. Anything missing is downloaded into a self-contained toolchain
    folder (default: .toolchain next to this script). Nothing is installed
    system-wide and nothing outside that folder is touched; delete it to undo.

    First run downloads ~500 MB and takes a while. Later runs reuse it and are
    fast. Existing JAVA_HOME / ANDROID_HOME are honoured if they're usable.

.PARAMETER Install
    After building, install the APK onto a connected device via adb.

.PARAMETER Release
    Build an unsigned release APK instead of debug.

.PARAMETER Clean
    Clean build outputs first. Does not discard the toolchain.

.PARAMETER ToolchainDir
    Where to keep the downloaded toolchain. Default: .toolchain beside this script.

.EXAMPLE
    .\build.ps1
    .\build.ps1 -Install
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Release,
    [switch]$Clean,
    [string]$ToolchainDir
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12


# Pinned versions. Gradle 8.11.1 satisfies AGP 8.7.2; AGP 8.7 needs JDK 17+.
$JdkUrl      = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
$GradleVer   = '8.11.1'
$GradleUrl   = "https://services.gradle.org/distributions/gradle-$GradleVer-bin.zip"
$CmdlineUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$CompileSdk  = 'platforms;android-35'
$BuildTools  = 'build-tools;35.0.0'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Definition
if (-not $ToolchainDir) { $ToolchainDir = Join-Path $Root '.toolchain' }

function Write-Step   ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok     ($m) { Write-Host "    $m" -ForegroundColor Green }
function Write-Detail ($m) { Write-Host "    $m" -ForegroundColor DarkGray }

# Windows PowerShell 5.1 raises a terminating NativeCommandError when a native
# command writes to stderr while $ErrorActionPreference is 'Stop'. Tools here
# legitimately use stderr (java -version prints its banner there), so run those
# pipelines with the preference relaxed.
function Invoke-Native {
    param([scriptblock]$Script)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Script } finally { $ErrorActionPreference = $prev }
}

function Get-Archive {
    param([string]$Url, [string]$OutFile, [string]$Label)
    if (Test-Path -LiteralPath $OutFile) {
        Write-Detail "$Label already downloaded"
        return
    }
    Write-Detail "downloading $Label ..."
    $tmp = "$OutFile.part"
    # Progress rendering makes Invoke-WebRequest an order of magnitude slower.
    $prev = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $tmp -UseBasicParsing
        Move-Item -LiteralPath $tmp -Destination $OutFile -Force
    } finally {
        $ProgressPreference = $prev
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    }
}

function Expand-Once {
    param([string]$Zip, [string]$Dest, [string]$Marker, [string]$Label)
    if (Test-Path -LiteralPath $Marker) {
        Write-Detail "$Label already extracted"
        return
    }
    Write-Detail "extracting $Label ..."
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $Dest -Force
}

function Get-JavaMajor {
    param([string]$JavaExe)
    if (-not (Test-Path -LiteralPath $JavaExe)) { return 0 }
    try {
        $out = Invoke-Native { & $JavaExe -version 2>&1 } | Out-String
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
        if ($out -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    } catch {
        Write-Detail "java -version failed: $_"
        return 0
    }
    return 0
}

New-Item -ItemType Directory -Force -Path $ToolchainDir | Out-Null
$Downloads = Join-Path $ToolchainDir 'downloads'
New-Item -ItemType Directory -Force -Path $Downloads | Out-Null

# ---------------------------------------------------------------- JDK 17
Write-Step 'Java 17'
$JavaHome = $null

if ($env:JAVA_HOME -and (Test-Path -LiteralPath $env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $major = Get-JavaMajor $candidate
    if ($major -ge 17) {
        $JavaHome = $env:JAVA_HOME
        Write-Ok "using JAVA_HOME (Java $major)"
    } else {
        Write-Detail "JAVA_HOME is Java $major - need 17+, ignoring it"
    }
}

if (-not $JavaHome) {
    $jdkDir = Join-Path $ToolchainDir 'jdk'
    $existing = if (Test-Path -LiteralPath $jdkDir) {
        Get-ChildItem -LiteralPath $jdkDir -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
            Select-Object -First 1
    } else { $null }

    if (-not $existing) {
        $zip = Join-Path $Downloads 'jdk17.zip'
        Get-Archive -Url $JdkUrl -OutFile $zip -Label 'JDK 17 (~180 MB)'
        Expand-Once -Zip $zip -Dest $jdkDir -Marker (Join-Path $jdkDir '__never__') -Label 'JDK 17'
        $existing = Get-ChildItem -LiteralPath $jdkDir -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
            Select-Object -First 1
    }
    if (-not $existing) { throw "JDK extraction failed - no bin\java.exe under $jdkDir" }
    $JavaHome = $existing.FullName
    Write-Ok "using local JDK ($(Split-Path -Leaf $JavaHome))"
}

$env:JAVA_HOME = $JavaHome
$JavaExe = Join-Path $JavaHome 'bin\java.exe'

# ---------------------------------------------------------------- Android SDK
Write-Step 'Android SDK'
$SdkRoot = $null

foreach ($env_sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($env_sdk -and (Test-Path -LiteralPath (Join-Path $env_sdk $CompileSdk.Replace(';', '\')))) {
        $SdkRoot = $env_sdk
        Write-Ok "using existing SDK at $SdkRoot"
        break
    }
}

if (-not $SdkRoot) {
    $SdkRoot = Join-Path $ToolchainDir 'android-sdk'
    New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null

    $sdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        $zip = Join-Path $Downloads 'cmdline-tools.zip'
        Get-Archive -Url $CmdlineUrl -OutFile $zip -Label 'Android command-line tools (~130 MB)'

        $staging = Join-Path $ToolchainDir 'cmdline-staging'
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
        Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force

        # sdkmanager insists on living at cmdline-tools/latest/.
        $target = Join-Path $SdkRoot 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        Move-Item -LiteralPath (Join-Path $staging 'cmdline-tools') -Destination $target
        Remove-Item -LiteralPath $staging -Recurse -Force
    }

    if (-not (Test-Path -LiteralPath $sdkManager)) { throw "sdkmanager not found at $sdkManager" }

    Write-Detail 'accepting SDK licenses ...'
    # sdkmanager prompts y/N per licence; feed it enough acceptances.
    $yes = ("y`n" * 50)
    Invoke-Native { $yes | & $sdkManager --sdk_root="$SdkRoot" --licenses 2>&1 } |
        Where-Object { $_ -match 'accepted|All SDK' } | Select-Object -First 1 | Out-Null

    Write-Detail "installing platform-tools, $CompileSdk, $BuildTools ..."
    Invoke-Native { & $sdkManager --sdk_root="$SdkRoot" 'platform-tools' $CompileSdk $BuildTools 2>&1 } |
        Where-Object { $_ -match 'Installing|100%|done' } | ForEach-Object { Write-Detail $_ }

    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
    Write-Ok "SDK ready at $SdkRoot"
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

# AGP reads sdk.dir from local.properties. Java .properties treats '\' as an
# escape, so forward slashes are the safe form here.
$localProps = Join-Path $Root 'local.properties'
$sdkDirValue = $SdkRoot -replace '\\', '/'
Set-Content -LiteralPath $localProps -Value "sdk.dir=$sdkDirValue" -Encoding ASCII
Write-Detail "wrote local.properties"

# ---------------------------------------------------------------- Release key
# An unsigned APK cannot be installed, so a release build needs a key. Mint a
# dedicated self-signed one on first use. This is NOT the debug key - signing a
# release with the debug cert would be a quiet wrong success.
if ($Release) {
    Write-Step 'Release signing key'
    $keystore  = Join-Path $Root 'glyphbar-release.keystore'
    $keyProps  = Join-Path $Root 'keystore.properties'

    if ((Test-Path -LiteralPath $keystore) -and (Test-Path -LiteralPath $keyProps)) {
        Write-Ok 'using existing keystore'
    } else {
        $keytool = Join-Path $JavaHome 'bin\keytool.exe'
        if (-not (Test-Path -LiteralPath $keytool)) { throw "keytool not found at $keytool" }

        # Random password; stored beside the keystore. Both stay local and are
        # gitignored. Keep them: signing a later build with a different key
        # blocks upgrade-installs over this one.
        $bytes = [byte[]]::new(18)
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $pw = [Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x'

        if (Test-Path -LiteralPath $keystore) { Remove-Item -LiteralPath $keystore -Force }
        Write-Detail 'generating self-signed release key (valid ~27 years) ...'
        Invoke-Native {
            & $keytool -genkeypair -noprompt `
                -keystore $keystore -alias glyphbar `
                -keyalg RSA -keysize 2048 -validity 10000 `
                -storepass $pw -keypass $pw `
                -dname 'CN=GlyphBar, OU=Dev, O=GlyphBar, L=-, ST=-, C=US' 2>&1
        } | ForEach-Object { Write-Detail $_ }
        if (-not (Test-Path -LiteralPath $keystore)) { throw 'keytool failed to create the keystore' }

        # Java .properties treats '\' as an escape - use forward slashes.
        @(
            "storeFile=$($keystore -replace '\\', '/')",
            "storePassword=$pw",
            "keyAlias=glyphbar",
            "keyPassword=$pw"
        ) | Set-Content -LiteralPath $keyProps -Encoding ASCII
        Write-Ok 'keystore created (keep glyphbar-release.keystore + keystore.properties)'
    }
}

# ---------------------------------------------------------------- Gradle
Write-Step "Gradle $GradleVer"
$gradleDir = Join-Path $ToolchainDir 'gradle'
$gradleBat = Join-Path $gradleDir "gradle-$GradleVer\bin\gradle.bat"

if (-not (Test-Path -LiteralPath $gradleBat)) {
    $zip = Join-Path $Downloads "gradle-$GradleVer.zip"
    Get-Archive -Url $GradleUrl -OutFile $zip -Label "Gradle $GradleVer (~130 MB)"
    Expand-Once -Zip $zip -Dest $gradleDir -Marker $gradleBat -Label "Gradle $GradleVer"
}
if (-not (Test-Path -LiteralPath $gradleBat)) { throw "Gradle not found at $gradleBat" }
Write-Ok "using Gradle $GradleVer"

# Generate the wrapper so Android Studio and CI agree on the version.
if (-not (Test-Path -LiteralPath (Join-Path $Root 'gradlew.bat'))) {
    Write-Detail 'generating Gradle wrapper ...'
    Push-Location -LiteralPath $Root
    try { & $gradleBat wrapper --gradle-version $GradleVer --quiet 2>&1 | Out-Null }
    finally { Pop-Location }
}

# ---------------------------------------------------------------- Build
$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += $task

Write-Step "Building ($($tasks -join ', '))"
Push-Location -LiteralPath $Root
try {
    # Gradle picks up the JDK from JAVA_HOME (set above). Don't pass
    # -Dorg.gradle.java.home: PowerShell mangles that argument when the path
    # contains a space, and Gradle then reads the fragment as a task name.
    & $gradleBat @tasks --console=plain
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($buildExit -ne 0) {
    Write-Host ''
    Write-Host "BUILD FAILED (exit $buildExit)" -ForegroundColor Red
    exit $buildExit
}

$variant = if ($Release) { 'release' } else { 'debug' }
$outDir = Join-Path $Root "app\build\outputs\apk\$variant"

# Gradle owns app/build, and an unsigned release is named app-release-unsigned.apk,
# so find the artifact rather than assuming its name.
$built = Get-ChildItem -LiteralPath $outDir -Filter '*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $built) { throw "Build reported success but no APK found in $outDir" }

# Publish a copy to the project root as the convenient, canonical artifact.
# Gradle would overwrite/clean anything left inside app/build, hence a copy.
$apk = Join-Path $Root "GlyphBar-$variant.apk"
Copy-Item -LiteralPath $built.FullName -Destination $apk -Force

Write-Host ''
Write-Host 'BUILD OK' -ForegroundColor Green
$size = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 2)
Write-Ok "$apk ($size MB)"
Write-Detail "(copied from app\build\outputs\apk\$variant\$($built.Name))"

# ---------------------------------------------------------------- Install
if ($Install) {
    Write-Step 'Installing to device'
    $adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if (-not $devices) {
        Write-Host '    No device detected. Enable USB debugging and reconnect.' -ForegroundColor Yellow
        exit 1
    }

    & $adb install -r "$apk"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Ok 'installed'

    & $adb shell monkey -p com.kosta.glyphbar -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Ok 'launched'
}
