#!/usr/bin/env bash
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/sharding-demo-common.sh"

say "Определяем shard ключа, его primary и реплику"
route="$(curl -fsS "$MASTER/admin/route/demo-001")"
printf '%s\n' "$route" | python3 -c 'import json,sys; r=json.load(sys.stdin); print("Ключ:",r["key"]); print("Shard:",r["shard"]); print("Primary:",r["primary"]); print("Реплика:",r["replica"])'
shard="$(printf '%s' "$route" | python3 -c 'import json,sys; print(json.load(sys.stdin)["shard"])')"
case "$shard" in
  shard-0) service=shard0-primary ;;
  shard-1) service=shard1-primary ;;
  shard-2) service=shard2-primary ;;
  *) echo "Неизвестный shard: $shard" >&2; exit 1 ;;
esac

say "Ждём, пока реплика применит актуальный WAL primary"
for attempt in $(seq 1 60); do
  primary="$(curl -fsS "$MASTER/admin/replication/$shard/primary")"
  replica="$(curl -fsS "$MASTER/admin/replication/$shard/replica")"
  primary_lsn="$(printf '%s' "$primary" | python3 -c 'import json,sys; print(json.load(sys.stdin)["lastLsn"])')"
  applied_lsn="$(printf '%s' "$replica" | python3 -c 'import json,sys; print(json.load(sys.stdin)["lastAppliedLsn"])')"
  if [ "$applied_lsn" -ge "$primary_lsn" ]; then break; fi
  sleep 1
done
echo "Состояние primary: $primary"
echo "Состояние реплики: $replica"
if [ "$applied_lsn" -lt "$primary_lsn" ]; then
  echo "Реплика не догнала primary: LSN primary=$primary_lsn, применённый LSN=$applied_lsn" >&2
  exit 1
fi

say "Останавливаем primary и читаем тот же ключ через мастер"
dc --profile expanded stop "$service"
response="$(curl -sS -i --max-time 10 "$MASTER/api/v1/collections/shard-demo/vectors/demo-001")"
printf '%s\n' "$response" | sed -n '1,16p'
if ! printf '%s\n' "$response" | grep -qi '^X-VecDB-Node: replica'; then
  echo "Ожидалось, что запрос будет обслужен репликой" >&2
  exit 1
fi
echo "После сбоя соединения с primary маршрутизатор повторил чтение на реплике."
