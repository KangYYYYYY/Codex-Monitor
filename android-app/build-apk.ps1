$ErrorActionPreference = "Stop"

Write-Host "Checking Android build environment..."

$studioJbr = "D:\Android Studio\jbr"
if (-not $env:JAVA_HOME -and (Test-Path "$studioJbr\bin\java.exe")) {
    $env:JAVA_HOME = $studioJbr
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

$sdkFromLocalProperties = $null
if (Test-Path ".\local.properties") {
    $line = Get-Content ".\local.properties" | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1
    if ($line) {
        $sdkFromLocalProperties = ($line -replace "^sdk\.dir=", "").Replace("\\", "\")
    }
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    if ($sdkFromLocalProperties -and (Test-Path $sdkFromLocalProperties)) {
        $env:ANDROID_HOME = $sdkFromLocalProperties
        $env:ANDROID_SDK_ROOT = $sdkFromLocalProperties
    } elseif (Test-Path "D:\AndroidStudioSDK") {
        $env:ANDROID_HOME = "D:\AndroidStudioSDK"
        $env:ANDROID_SDK_ROOT = "D:\AndroidStudioSDK"
    }
}

if ($env:ANDROID_HOME) {
    $env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
}

$javaVersion = (cmd /c "java -version 2>&1") -join "`n"
Write-Host $javaVersion

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Host ""
    Write-Host "ANDROID_HOME / ANDROID_SDK_ROOT is not set."
    Write-Host "Install Android Studio, then open this project and build from Android Studio."
    exit 1
}

if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat assembleDebug
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle assembleDebug
} else {
    Write-Host ""
    Write-Host "Gradle is not installed and gradlew.bat is not present."
    Write-Host "Open this project in Android Studio, or install Gradle."
    exit 1
}

Write-Host ""
Write-Host "APK output:"
Write-Host ".\app\build\outputs\apk\debug\app-debug.apk"
