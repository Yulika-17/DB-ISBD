#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="$ROOT/demo-results/sharding"
mkdir -p "$RESULTS"

run_step() {
  local script="$1" output="$2"
  echo "Запуск $script -> $output"
  if bash "$ROOT/scripts/$script" > "$RESULTS/$output" 2>&1; then
    echo "Готово: $RESULTS/$output"
  else
    local status=$?
    echo "Сценарий завершился с ошибкой $status: $RESULTS/$output" >&2
    tail -n 40 "$RESULTS/$output" >&2 || true
    exit "$status"
  fi
}

{
  echo "Демонстрация распределённого шардирования"
  echo "Начало: $(date -Is)"
  echo "Compose-файл: compose.sharding.demo.yaml"
  echo
} > "$RESULTS/run.txt"

run_step sharding-demo-1-ring.sh 01-ring-and-vnodes.txt
run_step sharding-demo-2-queries.sh 02-routing-distribution-scatter-gather.txt
run_step sharding-demo-3-add-node.sh 03-add-node-and-migration.txt
run_step sharding-demo-4-replica-failover.sh 04-replica-read-failover.txt

{
  echo
  echo "Окончание: $(date -Is)"
  echo "Результаты: $RESULTS"
  echo "Кластер оставлен запущенным, выбранный primary остановлен. Для очистки выполните:"
  echo "  docker compose -f compose.sharding.demo.yaml --profile expanded down -v"
} >> "$RESULTS/run.txt"

echo "Все сценарии завершены. Файлы результатов: $RESULTS"
