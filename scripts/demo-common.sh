#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/compose.demo.yaml"
MASTER="http://localhost:2222"
REPLICA="http://localhost:2223"

dc() {
  docker compose -f "$COMPOSE_FILE" --project-directory "$ROOT" "$@"
}

wait_http_ok() {
  local url="$1"
  local seconds="${2:-90}"
  local start now
  start="$(date +%s)"
  while true; do
    if curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$url" | grep -q "^200$"; then
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start)) -ge "$seconds" ]; then
      echo "timeout waiting for $url" >&2
      return 1
    fi
    sleep 0.4
  done
}

json_long() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":\\(-\\{0,1\\}[0-9][0-9]*\\).*/\\1/p"
}

wait_replica_caught_up() {
  local seconds="${1:-60}"
  local start now master_lsn replica_status applied
  start="$(date +%s)"
  while true; do
    master_lsn="$(json_long "$(curl -s "$MASTER/api/v1/replication/status")" lastLsn)"
    replica_status="$(curl -s "$REPLICA/api/v1/replication/status")"
    applied="$(json_long "$replica_status" lastAppliedLsn)"
    if [ -n "$applied" ] && [ -n "$master_lsn" ] && [ "$applied" -ge "$master_lsn" ]; then
      printf '%s' "$replica_status"
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start)) -ge "$seconds" ]; then
      echo "replica did not catch up in ${seconds}s; last status: $replica_status" >&2
      return 1
    fi
    sleep 0.4
  done
}

start_demo_cluster() {
  echo "== cluster: recreate from compose.demo.yaml"
  dc down -v --remove-orphans
  dc up --build -d
  wait_http_ok "$MASTER/api/v1/health"
  wait_http_ok "$REPLICA/api/v1/health"
  echo "master  $MASTER  $(curl -s "$MASTER/api/v1/replication/status")"
  echo "replica $REPLICA $(curl -s "$REPLICA/api/v1/replication/status")"
}

step() {
  echo
  echo "== $*"
}
