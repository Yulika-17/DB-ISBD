#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/compose.sharding.demo.yaml"
MASTER="http://localhost:2222"

dc() { docker compose -f "$COMPOSE_FILE" --project-directory "$ROOT" "$@"; }

wait_ready() {
  local timeout="${1:-180}" start now code
  start="$(date +%s)"
  while true; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$MASTER/admin/health" || true)"
    if [ "$code" = 200 ]; then return 0; fi
    now="$(date +%s)"
    if [ $((now - start)) -ge "$timeout" ]; then
      echo "Не дождались готовности кластера; /admin/health вернул HTTP ${code:-нет ответа}" >&2
      curl -s "$MASTER/admin/health" >&2 || true
      return 1
    fi
    sleep 1
  done
}

route_shard() {
  curl -fsS "$MASTER/admin/route/$1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["shard"])'
}

say() { printf '\n== %s ==\n' "$*"; }

start_base_cluster() {
  say "Сборка и запуск мастера, двух шардов и двух реплик"
  dc --profile expanded down -v --remove-orphans
  unset SHARD_NODES || true
  dc up --build -d
  wait_ready
  dc ps
}
