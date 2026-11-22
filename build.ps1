param(
    [string]$SdkBuildTools = "",
    [string]$AndroidJar = "",
    [string]$JdkBin = "",
    [string]$Keystore = "signing\release.keystore",
    [string]$KeyAlias = "s9plusplus",
    [string]$StorePass = "s9plusplus",
    [string]$OutApk = "releases\S9PlusPlus-v1.0.0.apk"
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location $Root

# Auto-discover Android SDK Build Tools if omitted
if (-not $SdkBuildTools) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "" }
    if ($sdkRoot -and (Test-Path "$sdkRoot\build-tools")) {
        $cand = Get-ChildItem "$sdkRoot\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($cand) { $SdkBuildTools = $cand.FullName }
    }
}
if (-not $SdkBuildTools -or -not (Test-Path "$SdkBuildTools\aapt2.exe")) {
    throw "Android SDK build-tools not found. Pass -SdkBuildTools <path> or set ANDROID_HOME."
}

# Auto-discover android.jar if omitted
if (-not $AndroidJar) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "" }
    if ($sdkRoot -and (Test-Path "$sdkRoot\platforms")) {
        foreach ($plat in @("android-29", "android-30", "android-31", "android-33", "android-34")) {
            if (Test-Path "$sdkRoot\platforms\$plat\android.jar") {
                $AndroidJar = "$sdkRoot\platforms\$plat\android.jar"
                break
            }
        }
        if (-not $AndroidJar) {
            $platDir = Get-ChildItem "$sdkRoot\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($platDir -and (Test-Path "$($platDir.FullName)\android.jar")) {
                $AndroidJar = "$($platDir.FullName)\android.jar"
            }
        }
    }
}
if (-not $AndroidJar -or -not (Test-Path $AndroidJar)) {
    throw "android.jar not found. Pass -AndroidJar <path> or set ANDROID_HOME."
}

# Auto-discover JDK bin if omitted
if (-not $JdkBin) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        $JdkBin = "$env:JAVA_HOME\bin"
    } else {
        $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javacCmd) {
            $JdkBin = Split-Path -Parent $javacCmd.Source
        }
    }
}
if (-not $JdkBin -or -not (Test-Path "$JdkBin\javac.exe")) {
    throw "JDK bin folder not found. Pass -JdkBin <path> or set JAVA_HOME."
}

$aapt2 = Join-Path $SdkBuildTools 'aapt2.exe'
$aapt = Join-Path $SdkBuildTools 'aapt.exe'
$zipalign = Join-Path $SdkBuildTools 'zipalign.exe'
$d8jar = Join-Path $SdkBuildTools 'lib\d8.jar'
$apksigner = Join-Path $SdkBuildTools 'lib\apksigner.jar'

$javac = Join-Path $JdkBin 'javac.exe'
$jarTool = Join-Path $JdkBin 'jar.exe'
$javaExe = Join-Path $JdkBin 'java.exe'
$keytool = Join-Path $JdkBin 'keytool.exe'

if (Test-Path 'build') { Remove-Item 'build' -Recurse -Force }
New-Item -ItemType Directory -Path 'build\classes', 'build\dex' -Force | Out-Null

$outDir = Split-Path -Parent $OutApk
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

# Generate a clean local signing keystore if one does not exist
if (-not (Test-Path $Keystore)) {
    $ksDir = Split-Path -Parent $Keystore
    if ($ksDir -and -not (Test-Path $ksDir)) {
        New-Item -ItemType Directory -Path $ksDir -Force | Out-Null
    }
    Write-Host "Generating signing keystore at $Keystore..."
    & $keytool -genkeypair -v -keystore $Keystore -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -storepass $StorePass -keypass $StorePass -dname "CN=S9PlusPlus, OU=OpenSource, O=Community, L=Unknown, ST=Unknown, C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "[1/6] Compiling resources with aapt2..."
& $aapt2 compile --dir 'res' -o 'build\res.zip'
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& $aapt2 link -I $AndroidJar --manifest 'AndroidManifest.xml' 'build\res.zip' -o 'build\unaligned.apk'
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] Compiling unencrypted, unobfuscated Java sources (-g)..."
$javaFiles = (Get-ChildItem 'src' -Recurse -Filter '*.java').FullName
& $javac -g -source 8 -target 8 -encoding UTF-8 -bootclasspath $AndroidJar -d 'build\classes' $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# Enforce zero inner/lambda classes for clean D8 compatibility on all SDK versions
$innerClasses = Get-ChildItem 'build\classes' -Recurse -Filter '*$*.class'
if ($innerClasses) {
    $innerClasses.FullName
    throw "Found disallowed *$*.class files! Keep all helper classes top-level package-private."
}

Write-Host "[3/6] Packaging classes & running D8 (no shrinking/obfuscation/encryption)..."
& $jarTool cf 'build\classes.jar' -C 'build\classes' .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& $javaExe -cp $d8jar com.android.tools.r8.D8 --min-api 26 --lib $AndroidJar --output 'build\dex' 'build\classes.jar'
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/6] Merging classes.dex into APK..."
Push-Location 'build\dex'
try {
    & $aapt add '..\unaligned.apk' 'classes.dex'
    if ($LASTEXITCODE -ne 0) { throw "aapt add failed" }
} finally {
    Pop-Location
}

Write-Host "[5/6] Aligning APK..."
& $zipalign -f -p 4 'build\unaligned.apk' 'build\aligned.apk'
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] Signing & verifying $OutApk..."
& $javaExe -jar $apksigner sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" --out $OutApk 'build\aligned.apk'
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $javaExe -jar $apksigner verify $OutApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$apkSize = (Get-Item $OutApk).Length
Write-Host "Build succeeded: $OutApk ($apkSize bytes)"
