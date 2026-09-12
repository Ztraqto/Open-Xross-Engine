$ErrorActionPreference = 'Stop'
$wrapperRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$wrapperDirectory = Join-Path $wrapperRoot 'gradle\wrapper'
$wrapperPath = Join-Path $wrapperDirectory 'gradle-wrapper.jar'
$expectedHash = '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172'
$wrapperUrl = 'https://raw.githubusercontent.com/gradle/gradle/v8.14.4/gradle/wrapper/gradle-wrapper.jar'
function Get-WrapperHash([string] $path) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
try {
    if ([System.IO.File]::Exists($wrapperPath) -and (Get-WrapperHash $wrapperPath) -eq $expectedHash) {
        Write-Output 'Gradle wrapper JAR verified.'
        exit 0
    }
    [System.IO.Directory]::CreateDirectory($wrapperDirectory) | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $client = New-Object System.Net.WebClient
    try { $bytes = $client.DownloadData($wrapperUrl) } finally { $client.Dispose() }
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { $actualHash = ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
    if ($actualHash -ne $expectedHash) { throw 'Gradle wrapper checksum mismatch; downloaded bytes were not installed.' }
    [System.IO.File]::WriteAllBytes($wrapperPath, $bytes)
    Write-Output 'Gradle wrapper JAR downloaded and SHA-256 verified.'
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
