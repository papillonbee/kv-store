#!/usr/bin/env bash
#
# Run the router. Usage:
#   ./run-router.sh                                              # defaults
#   ./run-router.sh 7000                                         # router port 7000
#   ./run-router.sh 7000 http://localhost:7001,http://localhost:7002
#
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-7000}"
NODES="${2:-http://localhost:7001,http://localhost:7002,http://localhost:7003}"
JAR="target/kv-store-1.0-SNAPSHOT.jar"

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$JAR" ]]; then
    echo "→ jar not found, building first…"
    mvn -q package -DskipTests
fi

echo "→ router on http://localhost:$PORT  →  $NODES"
exec java -jar "$JAR" \
    --spring.profiles.active=router \
    --server.port="$PORT" \
    --kvstore.nodes="$NODES"
