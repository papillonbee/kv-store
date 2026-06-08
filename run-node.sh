#!/usr/bin/env bash
#
# Run a single storage node. Usage:
#   ./run-node.sh                  # defaults: port 7001, node-id "node-7001"
#   ./run-node.sh 7002             # port 7002, node-id "node-7002"
#   ./run-node.sh 7002 my-node     # port 7002, node-id "my-node"
#
# JAVA_HOME is set only inside this script's subshell — your parent shell's
# default JDK is left alone.
#
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-7001}"
NODE_ID="${2:-node-${PORT}}"
JAR="target/kv-store-1.0-SNAPSHOT.jar"

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$JAR" ]]; then
    echo "→ jar not found, building first…"
    mvn -q package -DskipTests
fi

echo "→ node $NODE_ID on http://localhost:$PORT  (JDK: $(java -version 2>&1 | head -1))"
exec java -jar "$JAR" \
    --spring.profiles.active=node \
    --server.port="$PORT" \
    --kvstore.node-id="$NODE_ID"
