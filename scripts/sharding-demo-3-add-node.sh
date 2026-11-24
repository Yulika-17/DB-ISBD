#!/usr/bin/env bash
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/sharding-demo-common.sh"
wait_ready

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
python3 - <<'PY' > "$TMP_DIR/keys.json"
import json
print(json.dumps({"keys":[f"demo-{i:03d}" for i in range(1,121)]}))
PY

say "Фаза 1. Что имеем до добавления нового шарда"
curl -fsS -X POST "$MASTER/admin/map" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/keys.json" > "$TMP_DIR/before.json"
curl -fsS "$MASTER/admin/ring" | python3 -c 'import json,sys; r=json.load(sys.stdin); print("Шардов: {}; точек на кольце: {}".format(r["shardCount"], r["totalVirtualNodes"]))'
echo "Фактическое распределение записей до добавления узла:"
curl -fsS "$MASTER/admin/distribution/shard-demo" | python3 -c 'import json,sys; r=json.load(sys.stdin); [print("{}: {} записей".format(s,n)) for s,n in r["vectorsByShard"].items()]'

say "Фаза 2. Запускаем primary нового шарда и его WAL-реплику"
echo "Поднимаются два отдельных контейнера: shard-2-primary и shard-2-replica; реплика подключается к primary по внутренней сети Docker Compose."
echo "Затем мастер перезапускается с конфигурацией из трёх шардов и ждёт доступности всех primary и реплик перед продолжением сценария."
dc --profile expanded up --build -d shard2-primary shard2-replica
export SHARD_NODES='shard-0=http://shard0-primary:2222|http://shard0-replica:2222,shard-1=http://shard1-primary:2222|http://shard1-replica:2222,shard-2=http://shard2-primary:2222|http://shard2-replica:2222'
dc --profile expanded up -d --force-recreate master
wait_ready

say "Фаза 3. Рассчитываем новое распределение и план переноса"
curl -fsS -X POST "$MASTER/admin/map" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/keys.json" > "$TMP_DIR/after.json"
curl -fsS "$MASTER/admin/ring" | python3 -c 'import json,sys; r=json.load(sys.stdin); print("Шардов: {}; точек на кольце: {}".format(r["shardCount"], r["totalVirtualNodes"]))'
python3 - "$TMP_DIR/after.json" <<'PY'
import collections,json,sys
owners=json.load(open(sys.argv[1]))
print("Ожидаемое распределение 120 ключей по новому кольцу:")
for shard,count in sorted(collections.Counter(owners.values()).items()):
    print(f"{shard}: {count} ключей")
print("На этой фазе кольцо пересчитывает владельцев, но данные ещё не перемещаются.")
PY
curl -fsS -X POST "$MASTER/admin/sync-schema" | python3 -c 'import json,sys; r=json.load(sys.stdin); print("Схем коллекций проверено:", r["schemas"]); [print("Схема существующей коллекции {} добавлена на новый shard {} (данные будут перенесены отдельно).".format(x["collection"], x["shard"])) for x in r["created"]]'
python3 - "$TMP_DIR/before.json" "$TMP_DIR/after.json" > "$TMP_DIR/moves.json" <<'PY'
import json,sys
before=json.load(open(sys.argv[1])); after=json.load(open(sys.argv[2]))
moves=[{"collection":"shard-demo","id":key,"oldShard":before[key]} for key in before if before[key] != after[key]]
print(json.dumps({"moves":moves}))
PY
say "Фаза 4. Переносим ключи и проверяем фактическое распределение"
curl -fsS -X POST "$MASTER/admin/move" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/moves.json" > "$TMP_DIR/move-result.json"
python3 - "$TMP_DIR/before.json" "$TMP_DIR/move-result.json" <<'PY'
import json,sys
before=json.load(open(sys.argv[1]))
result=json.load(open(sys.argv[2]))
count=result["movedCount"]
print(f"Перенесено ключей: {count} / {len(before)} ({count/len(before):.1%})")
print("Переносятся только ключи, для которых новый расчёт consistent hashing выбрал другой shard; остальные остаются на прежнем.")
PY
echo "Фактическое распределение записей после переноса:"
curl -fsS "$MASTER/admin/distribution/shard-demo" | python3 -c 'import json,sys; r=json.load(sys.stdin); [print("{}: {} записей".format(s,n)) for s,n in r["vectorsByShard"].items()]'
say "Текущие контейнеры Docker"
dc --profile expanded ps
