#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=demo-common.sh
. "$(cd "$(dirname "$0")" && pwd)/demo-common.sh"

start_demo_cluster

step "write on MASTER (autosave off, WAL fsync)"
curl -s -X POST "$MASTER/api/v1/collections" -H "Content-Type: application/json" \
  -d '{"name":"docs","dimension":3,"metric":"COSINE"}'
echo
curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/x" \
  -H "Content-Type: application/json" -d '{"vector":[1,0,0]}'
echo
echo "WAL $(curl -s "$MASTER/api/v1/replication/wal?n=10")"
echo "STATUS $(curl -s "$MASTER/api/v1/replication/status")"

step "SIGKILL master (no snapshot), start the same volume"
dc kill master
dc start master
wait_http_ok "$MASTER/api/v1/health"

step "GET after WAL replay"
curl -s "$MASTER/api/v1/collections/docs/vectors/x"
echo
echo "STATUS $(curl -s "$MASTER/api/v1/replication/status")"

echo
echo "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
