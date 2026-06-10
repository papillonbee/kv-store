#!/usr/bin/env bash
#
# Bring up a 3-node + 1-router cluster in one terminal. Logs go to
# target/logs/<name>.log so stdout stays usable. Ctrl-C tears the whole
# cluster down via the EXIT trap.
#
#   ./run-cluster.sh
#   tail -f target/logs/node-1.log     # in another terminal
#
set -euo pipefail
cd "$(dirname "$0")"

JAR="target/kv-store-1.0-SNAPSHOT.jar"
LOG_DIR="target/logs"

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"

echo "→ Building…"
mvn -q package -DskipTests

mkdir -p "$LOG_DIR"
PIDS=()

cleanup() {
    echo
    echo "→ Stopping cluster…"
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    echo "→ Done."
}
trap cleanup EXIT INT TERM

start_node() {
    local name="$1"; shift
    java -jar "$JAR" "$@" > "$LOG_DIR/$name.log" 2>&1 &
    PIDS+=($!)
    printf "  %-10s pid=%-6s log=%s\n" "$name" "$!" "$LOG_DIR/$name.log"
}

echo "→ Starting cluster…"
start_node node-1 --spring.profiles.active=node --server.port=7001 --kvstore.node-id=node-1 --kvstore.persistence.dir=./data/kv-store
start_node node-2 --spring.profiles.active=node --server.port=7002 --kvstore.node-id=node-2 --kvstore.persistence.dir=./data/kv-store
start_node node-3 --spring.profiles.active=node --server.port=7003 --kvstore.node-id=node-3 --kvstore.persistence.dir=./data/kv-store

# Give nodes a moment to bind their ports before the router starts probing them.
sleep 2

start_node router --spring.profiles.active=router --server.port=7000 \
    --kvstore.nodes=http://localhost:7001,http://localhost:7002,http://localhost:7003

cat <<EOF

→ Cluster up. Try:
    curl -X PUT  http://localhost:7000/kv/foo -H 'Content-Type: application/json' -d '{"v":1}'
    curl         http://localhost:7000/kv/foo
    curl -X PATCH http://localhost:7000/kv/foo -H 'Content-Type: application/json' -d '{"x":42}'
    curl         http://localhost:7000/kv

  Tail any node's logs:
    tail -f target/logs/router.log
    tail -f target/logs/node-1.log

→ Press Ctrl-C to stop the cluster.
EOF

wait
