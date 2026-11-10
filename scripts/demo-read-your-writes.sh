#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=demo-common.sh
. "$(cd "$(dirname "$0")" && pwd)/demo-common.sh"

start_demo_cluster

step "create collection + PUT on MASTER"
curl -s -X POST "$MASTER/api/v1/collections" -H "Content-Type: application/json" \
  -d '{"name":"docs","dimension":3,"metric":"COSINE"}'
echo
put="$(curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/7" \
  -H "Content-Type: application/json" -d '{"vector":[1,0,0]}')"
echo "$put"
lsn="$(printf '%s' "$put" | sed -n 's/.*"lsn":\([0-9][0-9]*\).*/\1/p')"
echo "[CLIENT] write ok lsn=$lsn"

step "anomaly: GET replica immediately (expect 404, lag > 0)"
code="$(curl -s -o /dev/null -w "%{http_code}" "$REPLICA/api/v1/collections/docs/vectors/7" || true)"
echo "GET replica id=7 HTTP $code"
echo "replica STATUS $(curl -s "$REPLICA/api/v1/replication/status")"

step "solution: GET replica with LSN token waitLsn=$lsn"
curl -s "$REPLICA/api/v1/collections/docs/vectors/7?waitLsn=$lsn"
echo
echo "replica STATUS $(curl -s "$REPLICA/api/v1/replication/status")"

echo
echo "cluster is still up. Stop: docker compose -f compose.demo.yaml down -v"
