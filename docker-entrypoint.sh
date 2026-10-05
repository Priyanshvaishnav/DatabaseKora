#!/bin/bash
set -e

# Support flexible container invocation:
#   docker run koradb                  -> starts server with defaults
#   docker run koradb server [flags]   -> starts server with flags
#   docker run koradb cli [flags]      -> starts CLI REPL
#   docker run koradb --role replica   -> starts server with replica role
#   docker run koradb bash             -> starts interactive shell

if [ "$1" = "server" ]; then
    shift
    exec java $JAVA_OPTS -jar /app/koradb-server.jar "$@"
elif [ "$1" = "cli" ] || [ "$1" = "koradb-cli" ]; then
    shift
    exec java -jar /app/koradb-cli.jar "$@"
elif [ "$1" = "bash" ] || [ "$1" = "sh" ]; then
    exec "$@"
elif [[ "$1" == -* ]]; then
    # Passed options directly (e.g., --role replica -p 8765)
    exec java $JAVA_OPTS -jar /app/koradb-server.jar "$@"
else
    exec "$@"
fi
