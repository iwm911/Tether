"""Loads app/src/main/assets/tether_helper.py as a module, and a tiny fake daemon for the client tests."""

import importlib.util
import json
import os
import socket
import threading

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HELPER_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "tether_helper.py")
FIXTURES = os.path.join(ROOT, "app", "src", "test", "resources", "fixtures", "daemon")


def load_helper():
    spec = importlib.util.spec_from_file_location("tether_helper", HELPER_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_helper_at(home):
    """A fresh helper module whose ~ (and so every ~/.claude path it derives) is home."""
    saved = {k: os.environ.get(k) for k in ("HOME", "CLAUDE_CONFIG_DIR")}
    os.environ["HOME"] = home
    os.environ.pop("CLAUDE_CONFIG_DIR", None)
    try:
        return load_helper()
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def fixture_path(name):
    return os.path.join(FIXTURES, name)


def fixture_bytes(name):
    with open(fixture_path(name), "rb") as f:
        return f.read()


def fixture_jsonl(name):
    with open(fixture_path(name)) as f:
        return [json.loads(l) for l in f if l.strip()]


def attach_bytes(events):
    """A recorded subscribe stream (snapshot ring tail + stream lines) as the raw repaint an attach sends."""
    raw = ""
    for ev in events:
        if ev.get("type") == "snapshot":
            raw = "".join(c for c in ev.get("streamTail") or [] if isinstance(c, str))
        elif ev.get("type") == "stream":
            raw += ev.get("line") or ""
    return raw.encode("utf-8")


def fixture_json(name):
    with open(fixture_path(name)) as f:
        return json.load(f)


class FakeDaemon(object):
    """A unix-socket server speaking the daemon's line protocol. handler(request_dict, conn) returns either a
    dict (sent as one line, then the connection closes), a list of dicts/bytes (sent in order, then close),
    or None (the handler wrote and closed the socket itself)."""

    def __init__(self, path, handler):
        self.path = path
        self.handler = handler
        self.requests = []
        self.received = []
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind(path)
        self.sock.listen(8)
        self.closed = False
        self.thread = threading.Thread(target=self._serve)
        self.thread.daemon = True
        self.thread.start()

    def _serve(self):
        while not self.closed:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            t = threading.Thread(target=self._one, args=(conn,))
            t.daemon = True
            t.start()

    def _one(self, conn):
        buf = b""
        try:
            while b"\n" not in buf:
                d = conn.recv(65536)
                if not d:
                    return
                buf += d
            line, rest = buf.split(b"\n", 1)
            req = json.loads(line.decode("utf-8"))
            self.requests.append(req)
            out = self.handler(req, conn)
            if out is None:
                return
            if not isinstance(out, list):
                out = [out]
            for item in out:
                if isinstance(item, bytes):
                    conn.sendall(item)
                else:
                    conn.sendall((json.dumps(item) + "\n").encode("utf-8"))
            conn.close()
        except OSError:
            pass

    def close(self):
        """Stops listening. shutdown() first: close() alone does not wake a thread blocked in accept(), and the
        kernel keeps accepting connections for it."""
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
