#!/usr/bin/env python3
"""Small HTTP shard master used only by the Docker sharding demo."""
import bisect
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit

VIRTUAL_NODES = int(os.environ.get("VIRTUAL_NODES", "128"))


def signed_hash(value):
    raw = int.from_bytes(hashlib.sha256(value.encode()).digest()[:8], "big", signed=False)
    return raw - (1 << 64) if raw >= (1 << 63) else raw


def read_shards():
    result = {}
    for item in os.environ["SHARD_NODES"].split(","):
        name, endpoints = item.split("=", 1)
        primary, replica = endpoints.split("|", 1)
        result[name] = (primary.rstrip("/"), replica.rstrip("/"))
    if len(result) < 2:
        raise RuntimeError("the demo master requires at least two shards")
    return result


SHARDS = read_shards()
RING = sorted((signed_hash(f"{name}#{i}"), name, i) for name in SHARDS for i in range(VIRTUAL_NODES))
TOKENS = [point[0] for point in RING]


def owner(key):
    index = bisect.bisect_left(TOKENS, signed_hash(key))
    if index == len(RING):
        index = 0
    return RING[index][1]


def call(url, method="GET", body=None, timeout=4):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"} if data is not None else {})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.status, response.read(), dict(response.headers.items())


def fetch(shard, path, method="GET", body=None, read=False):
    primary, replica = SHARDS[shard]
    try:
        status, data, headers = call(primary + path, method, body)
        return status, data, headers, "primary"
    except urllib.error.HTTPError:
        raise
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
        if not read:
            raise
        status, data, headers = call(replica + path, method, body)
        return status, data, headers, "replica"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("[master] " + (fmt % args) + "\n")
        sys.stdout.flush()

    def respond(self, status, data=b"", headers=None, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def json(self, status, value, headers=None):
        self.respond(status, json.dumps(value).encode(), headers)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/api/v1/health":
            return self.json(200, {"status": "UP", "database": "shard-demo-master"})
        if path == "/admin/ring":
            points = [{"token": token, "shard": shard, "vnode": vnode} for token, shard, vnode in RING]
            return self.json(200, {"shardCount": len(SHARDS), "virtualNodesPerShard": VIRTUAL_NODES,
                                   "totalVirtualNodes": len(points), "shards": list(SHARDS), "points": points})
        distribution = re.fullmatch(r"/admin/distribution/([^/]+)", path)
        if distribution:
            collection = unquote(distribution.group(1))
            counts = {}
            try:
                for shard, (primary, _) in SHARDS.items():
                    status, data, _ = call(primary + "/api/v1/collections/" + quote(collection) + "/stats")
                    counts[shard] = json.loads(data)["liveVectors"]
                return self.json(200, {"collection": collection, "vectorsByShard": counts})
            except urllib.error.HTTPError as exc:
                return self.respond(exc.code, exc.read())
            except Exception as exc:
                return self.json(502, {"error": str(exc), "collection": collection})
        if path == "/admin/health":
            checks = {}
            for shard, (primary, replica) in SHARDS.items():
                for role, base in (("primary", primary), ("replica", replica)):
                    try:
                        status, _, _ = call(base + "/api/v1/health", timeout=1)
                        checks[f"{shard}-{role}"] = status == 200
                    except Exception:
                        checks[f"{shard}-{role}"] = False
            ready = all(checks.values())
            return self.json(200 if ready else 503, {"ready": ready, "nodes": checks})
        repl = re.fullmatch(r"/admin/replication/([^/]+)/(primary|replica)", path)
        if repl:
            shard, role = repl.groups()
            if shard not in SHARDS: return self.json(404, {"error": "unknown shard"})
            base = SHARDS[shard][0 if role == "primary" else 1]
            try:
                status, data, _ = call(base + "/api/v1/replication/status")
                return self.respond(status, data, {"X-VecDB-Shard": shard, "X-VecDB-Node": role})
            except Exception as exc:
                return self.json(502, {"error": str(exc), "shard": shard, "node": role})
        if path.startswith("/admin/route/"):
            key = unquote(path[len("/admin/route/"):])
            shard = owner(key)
            primary, replica = SHARDS[shard]
            return self.json(200, {"key": key, "shard": shard, "primary": primary, "replica": replica,
                                   "vnodeCount": VIRTUAL_NODES})
        route = re.fullmatch(r"/api/v1/collections/([^/]+)/vectors/([^/]+)(?:/(exists))?", path)
        if route:
            collection, key, exists = map(lambda x: unquote(x) if x else x, route.groups())
            shard = owner(key)
            suffix = "/exists" if exists else ""
            return self.forward(shard, f"/api/v1/collections/{quote(collection)}/vectors/{quote(key)}{suffix}", read=True)
        if path.endswith("/search") and path.startswith("/api/v1/collections/"):
            return self.scatter_search(path)
        if path == "/api/v1/collections" or path.startswith("/api/v1/collections/"):
            return self.forward(next(iter(SHARDS)), self.path, read=True)
        return self.respond(404, b"not found", content_type="text/plain")

    def do_PUT(self): self.route_vector("PUT")
    def do_DELETE(self):
        path = urlsplit(self.path).path
        route = re.fullmatch(r"/api/v1/collections/([^/]+)/vectors/([^/]+)", path)
        if route:
            collection, key = map(unquote, route.groups())
            return self.forward(owner(key), f"/api/v1/collections/{quote(collection)}/vectors/{quote(key)}", read=False)
        if path == "/api/v1/collections" or re.fullmatch(r"/api/v1/collections/[^/]+", path):
            return self.fanout(self.command, self.path)
        return self.respond(404, b"not found", content_type="text/plain")

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/admin/map":
            body = self.read_body()
            return self.json(200, {str(key): owner(str(key)) for key in body.get("keys", [])})
        if path == "/admin/sync-schema":
            try:
                _, data, _ = call(next(iter(SHARDS.values()))[0] + "/api/v1/collections")
                schemas = json.loads(data)
                created = []
                for schema in schemas:
                    collection_path = "/api/v1/collections/" + quote(schema["name"])
                    for shard, (primary, _) in SHARDS.items():
                        try:
                            call(primary + collection_path)
                        except urllib.error.HTTPError as exc:
                            if exc.code != 404: raise
                            call(primary + "/api/v1/collections", "POST", schema)
                            created.append({"shard": shard, "collection": schema["name"]})
                return self.json(200, {"created": created, "schemas": len(schemas)})
            except Exception as exc:
                return self.json(502, {"error": str(exc)})
        if path == "/admin/move":
            body = self.read_body()
            moved, missing = [], []
            for item in body.get("moves", []):
                collection, key, old_shard = item["collection"], str(item["id"]), item["oldShard"]
                new_shard = owner(key)
                if old_shard == new_shard: continue
                vector_path = f"/api/v1/collections/{quote(collection)}/vectors/{quote(key)}"
                try:
                    _, data, _ = call(SHARDS[old_shard][0] + vector_path)
                    record = json.loads(data)
                    call(SHARDS[new_shard][0] + vector_path, "PUT",
                         {"vector": record["vector"], "data": record.get("data")})
                    call(SHARDS[old_shard][0] + vector_path, "DELETE")
                    moved.append({"id": key, "from": old_shard, "to": new_shard})
                except urllib.error.HTTPError as exc:
                    if exc.code == 404: missing.append(key)
                    else: return self.json(502, {"error": str(exc), "id": key})
                except Exception as exc:
                    return self.json(502, {"error": str(exc), "id": key})
            return self.json(200, {"moved": moved, "missing": missing, "movedCount": len(moved)})
        if path.endswith("/search") and path.startswith("/api/v1/collections/"):
            body = self.read_body()
            return self.scatter_search(path, body)
        if path == "/api/v1/collections":
            return self.fanout("POST", path, self.read_body())
        return self.respond(404, b"not found", content_type="text/plain")

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try: return json.loads(raw)
        except json.JSONDecodeError: return {}

    def route_vector(self, method):
        path = urlsplit(self.path).path
        route = re.fullmatch(r"/api/v1/collections/([^/]+)/vectors/([^/]+)", path)
        if not route:
            return self.respond(404, b"not found", content_type="text/plain")
        collection, key = map(unquote, route.groups())
        self.forward(owner(key), f"/api/v1/collections/{quote(collection)}/vectors/{quote(key)}", method,
                     self.read_body(), read=False)

    def forward(self, shard, path, method="GET", body=None, read=False):
        try:
            status, data, headers, node = fetch(shard, path, method, body, read)
            response_headers = {"X-VecDB-Shard": shard, "X-VecDB-Node": node}
            if "X-VecDB-LSN" in headers:
                response_headers["X-VecDB-LSN"] = headers["X-VecDB-LSN"]
            return self.respond(status, data, response_headers)
        except urllib.error.HTTPError as exc:
            return self.respond(exc.code, exc.read())
        except Exception as exc:
            return self.json(502, {"error": str(exc), "shard": shard})

    def fanout(self, method, path, body=None):
        responses = []
        for shard in SHARDS:
            try:
                status, data, _, node = fetch(shard, path, method, body, read=False)
                responses.append((shard, status, data, node))
            except Exception as exc:
                return self.json(502, {"error": str(exc), "shard": shard})
        _, status, data, _ = responses[0]
        return self.respond(status, data, {"X-VecDB-Shards-Queried": ",".join(SHARDS),
                                           "X-VecDB-Scatter-Gather": "true"})

    def scatter_search(self, path, body=None):
        if body is None: body = self.read_body()
        hits, queried = [], []
        for shard in SHARDS:
            queried.append(shard)
            try:
                status, data, _, _ = fetch(shard, path, "POST", body, read=True)
                if status >= 400: return self.respond(status, data)
                hits.extend(json.loads(data))
            except Exception as exc:
                return self.json(502, {"error": str(exc), "shard": shard})
        hits.sort(key=lambda item: item["distance"])
        result = json.dumps(hits[:int(body.get("k", 10))]).encode()
        return self.respond(200, result, {"X-VecDB-Shards-Queried": ",".join(queried),
                                          "X-VecDB-Scatter-Gather": "true"})


ThreadingHTTPServer(("0.0.0.0", 2222), Handler).serve_forever()
