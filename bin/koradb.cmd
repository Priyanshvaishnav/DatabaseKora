@echo off
setlocal enabledelayedexpansion

REM ==============================================================================
REM KoraDB Windows Command Launcher
REM ==============================================================================

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "KORA_HOME=%%~fI"

REM Find Java
if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_CMD=java"
)

REM Locate JARs (support both installed layout in lib\ and dev layout)
if exist "%KORA_HOME%\lib\koradb-server.jar" (
    set "SERVER_JAR=%KORA_HOME%\lib\koradb-server.jar"
    set "CLI_JAR=%KORA_HOME%\lib\koradb-cli.jar"
    set "BENCH_JAR=%KORA_HOME%\lib\benchmarks.jar"
) else if exist "%KORA_HOME%\kora-server\target\koradb-server.jar" (
    set "SERVER_JAR=%KORA_HOME%\kora-server\target\koradb-server.jar"
    set "CLI_JAR=%KORA_HOME%\kora-cli\target\koradb-cli.jar"
    set "BENCH_JAR=%KORA_HOME%\benchmarks\target\benchmarks.jar"
)

set "COMMAND=%~1"

if /i "%COMMAND%"=="server" goto :do_server
if /i "%COMMAND%"=="cli" goto :do_cli
if /i "%COMMAND%"=="bench" goto :do_bench
if /i "%COMMAND%"=="benchmark" goto :do_bench
if /i "%COMMAND%"=="version" goto :do_version
if /i "%COMMAND%"=="-v" goto :do_version
if /i "%COMMAND%"=="--version" goto :do_version
if /i "%COMMAND%"=="help" goto :show_help
if /i "%COMMAND%"=="-h" goto :show_help
if /i "%COMMAND%"=="--help" goto :show_help

REM Default: run CLI
"%JAVA_CMD%" -jar "%CLI_JAR%" %*
goto :eof

:do_server
shift
"%JAVA_CMD%" -jar "%SERVER_JAR%" %*
goto :eof

:do_cli
shift
"%JAVA_CMD%" -jar "%CLI_JAR%" %*
goto :eof

:do_bench
shift
"%JAVA_CMD%" -jar "%BENCH_JAR%" %*
goto :eof

:do_version
echo KoraDB v2.0.1 (Java 21)
goto :eof
goto :eof

:show_help
echo KoraDB - Relational Database Engine Built from Scratch in Java 21
echo.
echo Usage:
echo   koradb [command] [options]
echo.
echo Commands:
echo   server              Start the KoraDB standalone database server
echo   cli                 Start the interactive SQL REPL and AI query client (default)
echo   bench               Run the JMH benchmark suite
echo   version             Print version information
echo   help                Print this help manual
echo.
echo Examples:
echo   koradb server --port 8765 --data-dir ./data
echo   koradb cli --host 127.0.0.1 --port 8765
echo   koradb              (alias for 'koradb cli')
echo   koradb -e "SELECT 1;"
goto :eof
