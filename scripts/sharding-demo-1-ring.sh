#!/usr/bin/env bash
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/sharding-demo-common.sh"

start_base_cluster
say "Кольцо consistent hashing и виртуальные узлы"
curl -fsS "$MASTER/admin/ring" | python3 -c '
import json,sys
r=json.load(sys.stdin)
print("Шарды:", ", ".join(r["shards"]))
print("Виртуальных узлов на shard:", r["virtualNodesPerShard"])
print("Всего точек на кольце:", r["totalVirtualNodes"])
for s in r["shards"]:
    points=[p for p in r["points"] if p["shard"] == s]
    print(f"{s}: токенов {len(points)}; примеры:", ", ".join(str(p["token"]) for p in points[:5]))
'
say "Контейнеры Docker"
dc ps
