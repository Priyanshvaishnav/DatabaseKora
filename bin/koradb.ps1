<#
.SYNOPSIS
    KoraDB Windows PowerShell Launcher
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$ArgsList
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$KoraHome = Split-Path -Parent $ScriptDir

# Locate Java
$JavaCmd = "java"
if ($env:JAVA_HOME) {
    $JavaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $JavaPath) {
        $JavaCmd = $JavaPath
    }
}

# Locate JARs
$ServerJar = Join-Path $KoraHome "lib\koradb-server.jar"
$CliJar = Join-Path $KoraHome "lib\koradb-cli.jar"
$BenchJar = Join-Path $KoraHome "lib\benchmarks.jar"

if (-not (Test-Path $ServerJar)) {
    $ServerJar = Join-Path $KoraHome "kora-server\target\koradb-server.jar"
    $CliJar = Join-Path $KoraHome "kora-cli\target\koradb-cli.jar"
    $BenchJar = Join-Path $KoraHome "benchmarks\target\benchmarks.jar"
}

function Show-Help {
    Write-Host @"
KoraDB - Relational Database Engine Built from Scratch in Java 21

Usage:
  koradb [command] [options]

Commands:
  server              Start the KoraDB standalone database server
  cli                 Start the interactive SQL REPL and AI query client (default)
  bench               Run the JMH benchmark suite
  version             Print version information
  help                Print this help manual

Examples:
  koradb server --port 8765 --data-dir ./data
  koradb cli --host 127.0.0.1 --port 8765
  koradb              (alias for 'koradb cli')
  koradb -e "SELECT 1;"
"@
}

if ($ArgsList.Count -eq 0) {
    & $JavaCmd -jar $CliJar
    exit $LASTEXITCODE
}

$Command = $ArgsList[0].ToLower()
$Remaining = $ArgsList | Select-Object -Skip 1

switch ($Command) {
    "server" {
        & $JavaCmd -jar $ServerJar $Remaining
    }
    "cli" {
        & $JavaCmd -jar $CliJar $Remaining
    }
    { $_ -in "bench", "benchmark" } {
        & $JavaCmd -jar $BenchJar $Remaining
    }
    { $_ -in "version", "-v", "--version" } {
        Write-Host "KoraDB v2.0.1 (Java 21)"
    }
    { $_ -in "help", "-h", "--help" } {
        Show-Help
    }
    default {
        & $JavaCmd -jar $CliJar $ArgsList
    }
}
exit $LASTEXITCODE
