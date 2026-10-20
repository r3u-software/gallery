#!/usr/bin/env python3
"""Stand-in for the user's Apps Script web app plus Google's resumable upload endpoint.

Emulator smoke test only: the phone reaches this machine at 10.0.2.2. It answers POST /exec with a redirect to the
page holding the reply (like the real Apps Script does), hands out upload slots, accepts chunked PUTs with
Content-Range, and lists finished uploads at GET /report.

    python3 fake_gas.py [port]
"""
import hashlib
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = "e2e-backup-secret"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9099

lock = threading.Lock()
slots = {}    # slot id -> {"name": str, "total": int, "data": bytearray}
replies = {}  # reply id -> json text
finished = []  # {"name", "size", "sha256"}
counter = [0]


def next_id(prefix):
    with lock:
        counter[0] += 1
        return f"{prefix}{counter[0]}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the CI log quiet
        pass

    def send(self, code, body=b"", headers=None, ctype="application/json"):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        return self.rfile.read(length) if length else b""

    def do_POST(self):
        if self.path != "/exec":
            return self.send(404, "nf", ctype="text/plain")
        raw = self.body()
        try:
            req = json.loads(raw)
        except ValueError:
            req = {}
        if req.get("secret") != SECRET:
            reply = {"ok": False, "error": "unauthorized"}
        elif req.get("action") == "ping":
            reply = {"ok": True}
        elif req.get("action") == "init":
            with lock:
                already = any(f["name"] == req.get("name") and f["size"] == req.get("size") for f in finished)
            if already:
                reply = {"ok": True, "exists": True}
            else:
                sid = next_id("slot")
                with lock:
                    slots[sid] = {"name": req["name"], "total": int(req["size"]), "data": bytearray()}
                reply = {"ok": True, "session": f"http://10.0.2.2:{PORT}/upload/{sid}"}
        else:
            reply = {"ok": False, "error": "bad_request"}
        rid = next_id("reply")
        with lock:
            replies[rid] = json.dumps(reply)
        self.send(302, b"", {"Location": f"/reply/{rid}"}, ctype="text/plain")

    def do_GET(self):
        if self.path.startswith("/reply/"):
            with lock:
                text = replies.get(self.path[len("/reply/"):], "{}")
            return self.send(200, text)
        if self.path == "/report":
            with lock:
                return self.send(200, json.dumps({"files": finished}))
        self.send(404, "nf", ctype="text/plain")

    def do_PUT(self):
        sid = self.path[len("/upload/"):] if self.path.startswith("/upload/") else ""
        data = self.body()
        with lock:
            slot = slots.get(sid)
        if slot is None:
            return self.send(404, "gone", ctype="text/plain")
        rng = self.headers.get("Content-Range", "")
        have = len(slot["data"])
        if not rng.startswith("bytes */"):
            start = int(rng.split(" ")[1].split("-")[0])
            if start == have:
                slot["data"].extend(data)
                have = len(slot["data"])
        if have == slot["total"]:
            with lock:
                finished.append({"name": slot["name"], "size": slot["total"], "sha256": hashlib.sha256(bytes(slot["data"])).hexdigest()})
            return self.send(200, '{"id":"file"}')
        headers = {"Range": f"bytes=0-{have - 1}"} if have else {}
        self.send(308, b"", headers, ctype="text/plain")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
