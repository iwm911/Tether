"""A throwaway ~ with Claude Code's files (jobs, session registry, transcripts, task lists) and a scripted fake
daemon on the control socket the helper finds there. Used by the sessions / follow / writes unit tests."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time

from helper_loader import FakeDaemon, HELPER_PATH, load_helper_at

DEAD_PID = 4194303  # above pid_max: never alive


class FakeHome(object):
    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="thome")
        self.claude = os.path.join(self.root, ".claude")
        for d in ("jobs", "sessions", "projects", "tasks", "daemon"):
            os.makedirs(os.path.join(self.claude, d))
        self.key = "0123456789abcdef0123456789abcdef"
        with open(os.path.join(self.claude, "daemon", "control.key"), "w") as f:
            f.write(self.key)
        self.run_root = os.path.join(self.root, "tmp", "cc-daemon-%d" % os.getuid())
        self.h = load_helper_at(self.root)
        self.h.daemon_runtime_root = lambda: self.run_root
        self.server = None
        self.model = None

    def close(self):
        if self.server:
            self.server.close()
        shutil.rmtree(self.root, ignore_errors=True)

    # ── files ──
    def path(self, *p):
        return os.path.join(self.root, *p)

    def folder(self, name, trusted=True):
        d = self.path(name)
        os.makedirs(d, exist_ok=True)
        if trusted:
            cj = self.path(".claude.json")
            data = {}
            if os.path.exists(cj):
                with open(cj) as f:
                    data = json.load(f)
            data.setdefault("projects", {})[d] = {"hasTrustDialogAccepted": True}
            with open(cj, "w") as f:
                json.dump(data, f)
        return d

    def job(self, short, **state):
        d = os.path.join(self.claude, "jobs", short)
        os.makedirs(d, exist_ok=True)
        st = {"state": "done", "tempo": "idle", "detail": "", "needs": "", "template": "bg", "backend": "daemon",
              "createdAt": "2026-10-01T10:00:00.000Z", "updatedAt": "2026-10-01T10:00:00.000Z"}
        st.update(state)
        with open(os.path.join(d, "state.json"), "w") as f:
            json.dump(st, f)
        return st

    def registry(self, pid, **entry):
        e = {"pid": pid, "kind": "bg", "status": "idle", "startedAt": 1790870000000, "updatedAt": 1790870000000}
        if pid == os.getpid():
            e["procStart"] = self.h.proc_start_tag(pid)
        e.update(entry)
        with open(os.path.join(self.claude, "sessions", "%d.json" % pid), "w") as f:
            json.dump(e, f)
        return e

    def transcript(self, cwd, sid, lines, mtime=None, raw=None):
        d = os.path.join(self.claude, "projects", self.h.project_dir_name(cwd))
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, sid + ".jsonl")
        with open(p, "w") as f:
            if raw is not None:
                f.write(raw)
            for l in lines:
                f.write(json.dumps(l, separators=(",", ":")) + "\n")
        if mtime:
            os.utime(p, (mtime, mtime))
        return p

    # ── daemon ──
    def serve(self, model=None):
        self.model = model or DaemonModel(self)
        sock_dir = os.path.join(self.run_root, self.h.daemon_socket_hash(self.claude))
        os.makedirs(sock_dir, exist_ok=True)
        os.chmod(self.run_root, 0o700)  # private, as the daemon makes them (the helper checks)
        os.chmod(sock_dir, 0o700)
        self.server = FakeDaemon(os.path.join(sock_dir, "control.sock"), self.model.handle)
        return self.model

    # ── running the real helper ──
    def env(self):
        env = dict(os.environ, HOME=self.root, TERMUX_VERSION="1", PREFIX=self.root)
        env.pop("CLAUDE_CONFIG_DIR", None)
        return env

    def run(self, *args, stdin=None, timeout=60):
        p = subprocess.run([sys.executable, HELPER_PATH] + list(args), input=(stdin or "").encode(),
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env(), timeout=timeout)
        out = p.stdout.decode()
        lines = [json.loads(l) for l in out.splitlines() if l.strip()]
        return p.returncode, (lines[0] if len(lines) == 1 else lines), p.stderr.decode()

    def popen(self, *args):
        return subprocess.Popen([sys.executable, HELPER_PATH] + list(args), stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env())


class DaemonModel(object):
    """The daemon's control socket, scripted: job records, dispatch (writes state.json like the daemon does),
    reply, kill, has, await-ack, subscribe (scripted events) and attach (records the raw keys)."""

    def __init__(self, home):
        self.home = home
        self.records = {}
        self.dispatched = []
        self.replies = []
        self.kills = []
        self.keys = bytearray()
        self.attach_screen = b"\x1b[2J\x1b[H\xe2\x9d\xaf \r\n"
        self.subscribe_events = []  # list of dicts / ("sleep", s) / callables
        self.on_keys = None
        self.lock = threading.Lock()

    def add(self, short, sid, **rec):
        r = {"short": short, "sessionId": sid, "nonce": "0000abcd", "pid": 4242, "cwd": "/", "backend": "daemon",
             "tempo": "idle", "state": "done", "source": "fleet", "createdAt": 1790870000000, "startedAt": 1790870000000}
        r.update(rec)
        self.records[short] = r
        return r

    def handle(self, req, conn):
        op = req.get("op")
        if op == "ping":
            return {"ok": True, "op": "ping", "version": "2.1.287", "proto": 1}
        if op == "list":
            return {"ok": True, "op": "list", "jobs": list(self.records.values())}
        if op == "has":
            r = self.records.get(req.get("short"))
            alive = bool(r) and not r.get("dying") and not r.get("outcome")
            return {"ok": True, "op": "has", "alive": alive, "present": bool(r), "ready": alive}
        if op == "dispatch":
            if req.get("auth") != self.home.key:
                return {"ok": False, "error": "dispatch rejected", "code": "EAUTH"}
            d = req["d"]
            self.dispatched.append(d)
            launch = d.get("launch") or {}
            self.add(d["short"], d["sessionId"], cwd=d["cwd"], tempo="active", state="working",
                     intent=(d.get("seed") or {}).get("intent", ""), nonce=d.get("nonce"))
            self.home.job(d["short"], sessionId=d["sessionId"], cwd=d["cwd"], tempo="active", state="working",
                          intent=(d.get("seed") or {}).get("intent", ""), respawnFlags=d.get("respawnFlags") or [],
                          name=(d.get("seed") or {}).get("name"), daemonShort=d["short"],
                          resumeSessionId=launch.get("sessionId"))
            return {"ok": True, "op": "dispatch", "short": d["short"], "pid": 4243, "messagingSock": "", "via": "spare"}
        if op == "await-ack":
            return {"ok": True, "op": "await-ack"}
        if op == "reply":
            if req.get("auth") != self.home.key:
                return {"ok": False, "error": "reply rejected", "code": "EAUTH"}
            r = self.records.get(req.get("short"))
            if not r or r.get("dying") or r.get("outcome"):
                return {"ok": False, "error": "job not found — it may have already exited", "code": "ENOJOB"}
            self.replies.append((req["short"], req["text"]))
            return {"ok": True, "op": "reply"}
        if op == "kill":
            r = self.records.get(req.get("short"))
            if not r:
                return {"ok": False, "error": "job not found — it may have already exited", "code": "ENOJOB"}
            self.kills.append((req["short"], bool(req.get("evict"))))
            if req.get("evict"):
                del self.records[req["short"]]
            else:
                r.update(dying=True, outcome="killed", tempo="idle")
            return {"ok": True, "op": "kill"}
        if op == "subscribe":
            r = self.records.get(req.get("short"))
            if not r:
                return {"ok": False, "error": "job not found — it may have already exited", "code": "ENOJOB"}
            for ev in self.subscribe_events:
                if isinstance(ev, tuple) and ev[0] == "sleep":
                    time.sleep(ev[1])
                elif callable(ev):
                    ev()
                else:
                    try:
                        conn.sendall((json.dumps(ev) + "\n").encode("utf-8"))
                    except OSError:
                        return None
            time.sleep(0.5)
            conn.close()
            return None
        if op == "attach":
            if req.get("auth") != self.home.key:
                return {"ok": False, "error": "attach rejected", "code": "EAUTH"}
            if req.get("short") not in self.records:
                return {"ok": False, "error": "job not found — it may have already exited", "code": "ENOJOB"}
            conn.sendall((json.dumps({"ok": True, "op": "attach", "decModes": [2004], "via": "pty", "booting": False,
                                      "tempo": "idle", "state": "done", "cached": False, "stale": False}) + "\n").encode())
            conn.sendall(self.attach_screen)
            conn.settimeout(0.2)
            end = time.time() + 15
            while time.time() < end:
                try:
                    d = conn.recv(4096)
                except OSError:
                    continue
                if not d:
                    break
                with self.lock:
                    self.keys.extend(d)
                if self.on_keys:
                    self.on_keys(bytes(self.keys))
            conn.close()
            return None
        return {"ok": False, "error": "unknown op: %s" % op, "code": "EUNKNOWN"}
