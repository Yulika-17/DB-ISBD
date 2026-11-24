#!/usr/bin/env bash
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/sharding-demo-common.sh"
wait_ready

say "Фаза 1. Создаём схему и записываем 120 векторов с разными ID"
curl -fsS -X POST "$MASTER/api/v1/collections" -H 'Content-Type: application/json' \
  -d '{"name":"shard-demo","dimension":3,"metric":"COSINE"}' -i | sed -n '1,12p'
for i in $(seq -w 1 120); do
  case $((10#$i % 3)) in
    0) vector='[1,0,0]' ;;
    1) vector='[0,1,0]' ;;
    2) vector='[0,0,1]' ;;
  esac
  curl -fsS -o /dev/null -X PUT "$MASTER/api/v1/collections/shard-demo/vectors/demo-$i" \
    -H 'Content-Type: application/json' -d "{\"vector\":$vector}"
done
echo "Записано 120 векторов: demo-001 — demo-120."

say "Фаза 2. Точечный GET маршрутизируется ровно на один shard"
key=demo-050
expected="$(route_shard "$key")"
echo "GET ключа $key; владелец по кольцу: $expected"
curl -sS -i "$MASTER/api/v1/collections/shard-demo/vectors/$key" | sed -n '1,12p'
echo "В заголовках ответа указан один shard; веерного запроса здесь нет."

say "Фаза 3. Распределение ключей по шардам"
python3 - "$MASTER" <<'PY'
import collections, json, sys, urllib.request
master=sys.argv[1]
keys=[f"demo-{i:03d}" for i in range(1,121)]
req=urllib.request.Request(master+"/admin/map", data=json.dumps({"keys":keys}).encode(),
                           headers={"Content-Type":"application/json"}, method="POST")
mapping=json.load(urllib.request.urlopen(req))
counts=collections.Counter(mapping.values())
for shard,count in sorted(counts.items()): print(f"{shard}: {count} ключей из 120 ({count/120:.1%})")
PY

say "Фаза 4. У k-NN запроса нет ключа шарда, поэтому он опрашивает все шарды"
curl -sS -i -X POST "$MASTER/api/v1/collections/shard-demo/vectors/search" \
  -H 'Content-Type: application/json' -d '{"vector":[1,0,0],"k":5}' | sed -n '1,20p'
echo "Заголовки X-VecDB-Shards-Queried и X-VecDB-Scatter-Gather показывают веерный запрос."
