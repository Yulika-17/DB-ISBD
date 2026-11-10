#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=demo-common.sh
. "$(cd "$(dirname "$0")" && pwd)/demo-common.sh"

start_demo_cluster

step "sync initial record to replica"
curl -s -X POST "$MASTER/api/v1/collections" -H "Content-Type: application/json" \
  -d '{"name":"docs","dimension":3,"metric":"COSINE"}' >/dev/null
curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/a" \
  -H "Content-Type: application/json" -d '{"vector":[1,0,0]}' >/dev/null
echo "replica STATUS $(wait_replica_caught_up)"

step "stop replica, MASTER keeps writing"
dc stop replica
curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/b" \
  -H "Content-Type: application/json" -d '{"vector":[0,1,0]}' >/dev/null
curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/c" \
  -H "Content-Type: application/json" -d '{"vector":[0,0,1]}' >/dev/null
echo "master STATUS $(curl -s "$MASTER/api/v1/replication/status")"

step "start replica — catch-up from WAL"
dc start replica
wait_http_ok "$REPLICA/api/v1/health"
echo "replica STATUS $(wait_replica_caught_up)"
echo "GET b $(curl -s "$REPLICA/api/v1/collections/docs/vectors/b")"
echo "GET c $(curl -s "$REPLICA/api/v1/collections/docs/vectors/c")"

echo
echo "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
