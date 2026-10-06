<#
.SYNOPSIS
    KoraDB 1-Line Installer for Windows (PowerShell)

    Quick install:
        irm https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.ps1 | iex
#>

$ErrorActionPreference = 'Stop'

$Repo = "Priyanshvaishnav/DatabaseKora"
$InstallDir = Join-Path $env:USERPROFILE ".koradb"
$BinDir = Join-Path $InstallDir "bin"
$LibDir = Join-Path $InstallDir "lib"

Write-Host @"

  _  __              _____  ____  
 | |/ /___  _ __ __ |  __ \|  _ \ 
 | ' // _ \| '__/ _` | |  | | |_) |
 | . \ (_) | | | (_| | |__| |  _ < 
 |_|\_\___/|_|  \__,_|_____/|____/ 
                                   
 Relational Database Engine Built from Scratch in Java 21

"@ -ForegroundColor Cyan

Write-Host "Installing KoraDB to: $InstallDir`n" -ForegroundColor Gray

# 1. Check prerequisites: Java 21+
$Java = Get-Command java -ErrorAction SilentlyContinue
if ($null -ne $Java) {
    try {
        $JavaVerString = (java -version 2>&1 | Select-String "version") -replace '.*version "([^".]+).*', '$1'
        $JavaVer = [int]$JavaVerString
        if ($JavaVer -ge 21) {
            Write-Host "✓ Found Java (version $JavaVerString)" -ForegroundColor Green
        } else {
            Write-Host "WARNING: Detected Java version $JavaVer. KoraDB requires Java 21+." -ForegroundColor Yellow
            Write-Host "Download it from: https://adoptium.net/temurin/releases/?version=21`n" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "✓ Found Java on PATH" -ForegroundColor Green
    }
} else {
    Write-Host "Note: Java is not currently found in your PATH." -ForegroundColor Yellow
    Write-Host "KoraDB requires Java 21+. Please download and install it:" -ForegroundColor Yellow
    Write-Host "  - Download: https://adoptium.net/temurin/releases/?version=21`n" -ForegroundColor Yellow
}

# 2. Create directories
if (-not (Test-Path $BinDir)) { New-Item -ItemType Directory -Path $BinDir -Force | Out-Null }
if (-not (Test-Path $LibDir)) { New-Item -ItemType Directory -Path $LibDir -Force | Out-Null }

# 3. Resolve download URL (latest GitHub release)
Write-Host "Fetching latest release information..." -ForegroundColor Gray
$ZipUrl = $null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $Release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ "User-Agent" = "KoraDB-Installer" }
    $Asset = $Release.assets | Where-Object { $_.name -like "*koradb-dist.zip" } | Select-Object -First 1
    if ($null -ne $Asset) {
        $ZipUrl = $Asset.browser_download_url
    }
} catch {
    # Fallback to direct download URL
    $ZipUrl = "https://github.com/$Repo/releases/latest/download/koradb-dist.zip"
}

if ($null -eq $ZipUrl) {
    $ZipUrl = "https://github.com/$Repo/releases/latest/download/koradb-dist.zip"
}

$TempZip = Join-Path $env:TEMP "koradb-dist.zip"
$TempExtract = Join-Path $env:TEMP "koradb-extract"

Write-Host "Downloading KoraDB package..." -ForegroundColor Gray
$Downloaded = $false
try {
    Invoke-WebRequest -Uri $ZipUrl -OutFile $TempZip -UseBasicParsing
    Expand-Archive -Path $TempZip -DestinationPath $TempExtract -Force
    
    # Copy bin and lib
    $SourceBin = Get-ChildItem -Path $TempExtract -Recurse -Directory -Filter "bin" | Select-Object -First 1
    $SourceLib = Get-ChildItem -Path $TempExtract -Recurse -Directory -Filter "lib" | Select-Object -First 1
    
    if ($null -ne $SourceBin) { Copy-Item -Path "$($SourceBin.FullName)\*" -Destination $BinDir -Force -Recurse }
    if ($null -ne $SourceLib) { Copy-Item -Path "$($SourceLib.FullName)\*" -Destination $LibDir -Force -Recurse }
    $Downloaded = $true
} catch {
    Write-Host "Notice: Release asset not yet published; fetching launchers from main branch..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$Repo/main/bin/koradb.cmd" -OutFile (Join-Path $BinDir "koradb.cmd") -UseBasicParsing
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$Repo/main/bin/koradb.ps1" -OutFile (Join-Path $BinDir "koradb.ps1") -UseBasicParsing
} finally {
    if (Test-Path $TempZip) { Remove-Item -Path $TempZip -Force -ErrorAction SilentlyContinue }
    if (Test-Path $TempExtract) { Remove-Item -Path $TempExtract -Recurse -Force -ErrorAction SilentlyContinue }
}

# 4. Add to User PATH if not present
$UserPath = [Environment]::GetEnvironmentVariable("Path", [EnvironmentVariableTarget]::User)
if ($UserPath -notlike "*$BinDir*") {
    $NewPath = "$UserPath;$BinDir".TrimStart(';')
    [Environment]::SetEnvironmentVariable("Path", $NewPath, [EnvironmentVariableTarget]::User)
    $env:Path = "$env:Path;$BinDir"
    Write-Host "✓ Added $BinDir to User PATH" -ForegroundColor Green
} else {
    Write-Host "✓ $BinDir is already in User PATH" -ForegroundColor Green
}

Write-Host "`n✓ KoraDB successfully installed!`n" -ForegroundColor Green
Write-Host "To get started in a new terminal window:" -ForegroundColor White
Write-Host "  1. Start the server:   koradb server --port 8765" -ForegroundColor Cyan
Write-Host "  2. Open the CLI:       koradb" -ForegroundColor Cyan
Write-Host "  3. Try AI query:       \ai show all products`n" -ForegroundColor Cyan
