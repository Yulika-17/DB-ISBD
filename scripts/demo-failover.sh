#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=demo-common.sh
. "$(cd "$(dirname "$0")" && pwd)/demo-common.sh"

start_demo_cluster

step "seed a collection on MASTER"
curl -s -X POST "$MASTER/api/v1/collections" -H "Content-Type: application/json" \
  -d '{"name":"docs","dimension":3,"metric":"COSINE"}' >/dev/null
curl -s -X PUT "$MASTER/api/v1/collections/docs/vectors/a" \
  -H "Content-Type: application/json" -d '{"vector":[1,0,0]}' >/dev/null
sleep 3

step "write on replica is rejected (expect 403)"
code="$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$REPLICA/api/v1/collections/docs/vectors/x" \
  -H "Content-Type: application/json" -d '{"vector":[0,1,0]}' || true)"
echo "PUT replica HTTP $code"

step "kill MASTER, PROMOTE replica"
dc kill master
curl -s -X POST "$REPLICA/api/v1/replication/promote"
echo
echo "STATUS $(curl -s "$REPLICA/api/v1/replication/status")"

step "writes go to the new MASTER (old replica :2223)"
curl -s -X PUT "$REPLICA/api/v1/collections/docs/vectors/b" \
  -H "Content-Type: application/json" -d '{"vector":[0,1,0]}'
echo
echo "GET b $(curl -s "$REPLICA/api/v1/collections/docs/vectors/b")"

echo
echo "do not start the old master: two masters are not handled. Clean: docker compose -f compose.demo.yaml down -v"
