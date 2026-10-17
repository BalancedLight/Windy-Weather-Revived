$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Invoke-GradleChecked {
    param([string[]]$GradleArgs)
    & .\gradlew.bat @GradleArgs --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $GradleArgs" }
}
function Read-PackagedPhoneCode {
    $metadata = Get-Content app/build/outputs/apk/github/debug/output-metadata.json -Raw | ConvertFrom-Json
    return [long]$metadata.elements[0].versionCode
}
$before = Get-Content version.properties -Raw
Invoke-GradleChecked -GradleArgs @('help', ':app:processGithubDebugMainManifest', ':app:testGithubDebugUnitTest', ':app:testPlayDebugUnitTest')
if ((Get-Content version.properties -Raw) -cne $before) { throw 'Non-packaging tasks reserved a code' }
Invoke-GradleChecked -GradleArgs @(':app:assembleGithubDebug', ':app:bundlePlayDebug', '--dry-run')
if ((Get-Content version.properties -Raw) -cne $before) { throw 'Dry-run reserved a code' }
Invoke-GradleChecked -GradleArgs @('clean', ':app:verifyDistributionArtifacts', '-PverificationBuildType=debug')
$first = Read-PackagedPhoneCode
Invoke-GradleChecked -GradleArgs @(':app:verifyDistributionArtifacts', '-PverificationBuildType=debug')
$second = Read-PackagedPhoneCode
if ($second -le $first) { throw "Actual artifact codes did not increase: $first -> $second" }
if ($second % 2 -ne 0) { throw 'Phone code must be even' }
$afterPackaging = Get-Content version.properties -Raw
Invoke-GradleChecked -GradleArgs @(':app:testGithubDebugUnitTest', ':app:testPlayDebugUnitTest')
if ((Get-Content version.properties -Raw) -cne $afterPackaging) { throw 'Unit tests reserved a code' }
Write-Host "PASS: actual APK and AAB manifests verified; packaging codes increased $first -> $second"
