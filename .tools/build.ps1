param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
$daymarkRoot = Split-Path $PSScriptRoot -Parent
$daymarkJava = Join-Path $env:LOCALAPPDATA 'Programs/Java/jdk-21.0.12.1+1'
$daymarkGradle = Join-Path $env:LOCALAPPDATA 'Programs/Gradle/gradle-8.13/bin/gradle.bat'
$daymarkTemp = Join-Path $PSScriptRoot 'java-tmp'
New-Item -ItemType Directory -Path $daymarkTemp -Force | Out-Null
$env:JAVA_HOME = $daymarkJava
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=' + $daymarkTemp.Replace('\','/')
& $daymarkGradle -p $daymarkRoot @Tasks --offline --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
