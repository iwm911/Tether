"""Unit tests for the daemon client in tether_helper.py (no real daemon: a fake unix-socket server and
fixtures captured live from Claude Code 2.1.286/2.1.287). Run: python3 -m unittest discover tools/helper_tests"""

import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import FakeDaemon, HELPER_PATH, fixture_bytes, fixture_json, fixture_jsonl, fixture_path, load_helper  # noqa

h = load_helper()


class FrameCodecTest(unittest.TestCase):
    def test_round_trip_data_and_ctrl(self):
        stream = h.encode_frame(0, b"hello") + h.encode_frame(1, {"type": "resize", "cols": 80}) + h.encode_frame(0, "❯ ok")
        frames = h.FrameDecoder().feed(stream)
        self.assertEqual(frames, [(0, b"hello"), (1, {"type": "resize", "cols": 80}), (0, "❯ ok".encode("utf-8"))])

    def test_header_layout_is_big_endian_length_then_kind(self):
        f = h.encode_frame(1, b"{}")
        self.assertEqual(f[:5], b"\x00\x00\x00\x02\x01")
        self.assertEqual(f[5:], b"{}")
        big = h.encode_frame(0, b"x" * 70000)
        self.assertEqual(big[:5], bytes(bytearray([0, 1, 0x11, 0x70, 0])))

    def test_partial_feeds_byte_by_byte(self):
        stream = h.encode_frame(0, b"abc") + h.encode_frame(1, {"a": 1})
        dec = h.FrameDecoder()
        got = []
        for i in range(len(stream)):
            got.extend(dec.feed(stream[i:i + 1]))
        self.assertEqual(got, [(0, b"abc"), (1, {"a": 1})])
        self.assertEqual(dec.buf, b"")

    def test_empty_payload(self):
        self.assertEqual(h.FrameDecoder().feed(h.encode_frame(0, b"")), [(0, b"")])

    def test_unknown_kind_is_an_error(self):
        with self.assertRaises(h.DaemonError) as cm:
            h.FrameDecoder().feed(b"\x00\x00\x00\x01\x07x")
        self.assertEqual(cm.exception.code, "EDAEMON")

    def test_too_large_is_an_error(self):
        with self.assertRaises(h.DaemonError):
            h.FrameDecoder().feed(b"\x7f\xff\xff\xff\x00")

    def test_bad_ctrl_json_is_an_error(self):
        with self.assertRaises(h.DaemonError):
            h.FrameDecoder().feed(b"\x00\x00\x00\x03\x01{x}")

    def test_raw_attach_capture_is_not_framed(self):
        # 2.1.286+: control-socket attach streams raw pty bytes after the reply line.
        raw = fixture_bytes("attach_raw_mcp_dialog.bin")
        self.assertTrue(raw.startswith(b"\x1b["))
        with self.assertRaises(h.DaemonError):
            h.FrameDecoder().feed(raw)


class LineParsingTest(unittest.TestCase):
    def test_line_buffer_keeps_partial_tail(self):
        lb = h.LineBuffer()
        self.assertEqual(lb.feed(b'{"a":1}\n{"b"'), [b'{"a":1}'])
        self.assertEqual(lb.feed(b':2}\n'), [b'{"b":2}'])
        self.assertEqual(lb.buf, b"")

    def test_decode_json_line(self):
        self.assertEqual(h.decode_json_line(b'{"ok":true}'), {"ok": True})
        for bad in (b"nope", b"[1,2]", b'"s"'):
            with self.assertRaises(h.DaemonError) as cm:
                h.decode_json_line(bad)
            self.assertEqual(cm.exception.code, "EDAEMON")


class ErrorMappingTest(unittest.TestCase):
    def test_live_replies_map_to_protocol_codes(self):
        replies = fixture_json("control_replies.json")
        expect = {
            "proto_mismatch": ("EPROTO", "EPROTO"),
            "bad_auth_reply": ("EAUTH", "EAUTH"),
            "no_auth_dispatch": ("EAUTH", "EAUTH"),
            "unknown_short_reply": ("ENOSESSION", "ENOJOB"),
            "unknown_short_subscribe": ("ENOSESSION", "ENOJOB"),
            "unknown_short_kill": ("ENOSESSION", "ENOJOB"),
            "await_ack_unknown": ("ETIMEOUT", "ETIMEOUT"),
            "unknown_op": ("EDAEMON", "EUNKNOWN"),
        }
        for key, (code, dcode) in expect.items():
            e = h.daemon_error_from_reply(replies[key])
            self.assertEqual((e.code, e.daemon_code), (code, dcode), key)
            self.assertTrue(str(e))
        self.assertIn("protocol 1", str(h.daemon_error_from_reply(replies["proto_mismatch"])))

    def test_other_daemon_codes_are_edaemon(self):
        for dcode in ("EALIVE", "ESTALE", "ERESPAWNING", "ENOREPLY", "ECWDGONE", "ESKEW", "EHOSTDEAD"):
            e = h.daemon_error_from_reply({"ok": False, "error": "x", "code": dcode})
            self.assertEqual((e.code, e.daemon_code), ("EDAEMON", dcode))

    def test_unreadable_reply(self):
        self.assertEqual(h.daemon_error_from_reply("junk").code, "EDAEMON")
        self.assertTrue(str(h.daemon_error_from_reply({"ok": False}, "reply")))


class SpecTest(unittest.TestCase):
    def test_prompt_spec(self):
        d = h.daemon_prompt_spec("/w", "hi there", flags=["--model", "haiku"], name="n")
        sid = d["sessionId"]
        self.assertRegex(sid, h.UUID_RE)
        self.assertEqual(d["short"], sid[:8])
        self.assertRegex(d["short"], h.SHORT_RE)
        self.assertEqual(d["proto"], 1)
        self.assertEqual(d["source"], "fleet")
        self.assertEqual(d["isolation"], "none")
        self.assertEqual(d["launch"], {"mode": "prompt", "args": ["--session-id", sid, "--model", "haiku", "--", "hi there"]})
        self.assertEqual(d["respawnFlags"], ["--model", "haiku"])
        self.assertEqual(d["seed"], {"intent": "hi there", "name": "n"})
        self.assertEqual(d["env"], {})

    def test_prompt_spec_keeps_a_given_session_id(self):
        sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
        d = h.daemon_prompt_spec("/w", "", session_id=sid)
        self.assertEqual(d["short"], "7b9f8c4c")
        self.assertEqual(d["launch"]["args"], ["--session-id", sid])

    def test_resume_spec_keeps_the_short_and_never_forks(self):
        sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
        d = h.daemon_resume_spec(sid, "/w", flags=["--model", "haiku"], transcript_path="/t.jsonl", intent="i")
        self.assertEqual(d["short"], "7b9f8c4c")
        self.assertEqual(d["launch"], {"mode": "resume", "sessionId": sid, "fork": False,
                                       "flagArgs": ["--model", "haiku"], "transcriptPath": "/t.jsonl"})
        self.assertEqual(d["seed"], {"intent": "i"})

    def test_nonce_shape(self):
        for _ in range(50):
            self.assertRegex(h.new_nonce(), h.SHORT_RE)


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="thd")
        self.cfg = os.path.join(self.tmp, "cfg")
        self.root = os.path.join(self.tmp, "run")
        os.makedirs(os.path.join(self.cfg, "daemon"))
        os.makedirs(self.root)
        self._saved = (h.claude_config_dir, h.daemon_runtime_root)
        h.claude_config_dir = lambda: self.cfg
        h.daemon_runtime_root = lambda: self.root

    def tearDown(self):
        h.claude_config_dir, h.daemon_runtime_root = self._saved
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _sock(self, name):
        d = os.path.join(self.root, name)
        os.makedirs(d)
        p = os.path.join(d, "control.sock")
        s = socket.socket(socket.AF_UNIX)
        s.bind(p)
        self.addCleanup(s.close)
        return p

    def test_hash_matches_the_daemon_rule(self):
        self.assertEqual(h.daemon_socket_hash("/home/user/.claude"),
                         hashlib.sha256(b"/home/user/.claude").hexdigest()[:8])
        self.assertEqual(len(h.daemon_socket_hash()), 8)

    def test_none_when_nothing_is_there(self):
        self.assertIsNone(h.find_daemon_socket())

    def test_prefers_the_hash_named_socket(self):
        other = self._sock("aaaaaaaa")
        expected = self._sock(h.daemon_socket_hash(self.cfg))
        os.utime(other, None)
        self.assertEqual(h.find_daemon_socket(), expected)

    def test_glob_fallback_newest_without_lock(self):
        old = self._sock("aaaaaaaa")
        new = self._sock("bbbbbbbb")
        t = time.time()
        os.utime(old, (t - 100, t - 100))
        os.utime(new, (t, t))
        self.assertEqual(h.find_daemon_socket(), new)

    def test_glob_fallback_prefers_the_lock_pid_holder(self):
        # The lock names pid 1, which holds neither socket, so the newest wins; this process (which bound
        # both) is seen as holding them through /proc.
        a = self._sock("aaaaaaaa")
        b = self._sock("bbbbbbbb")
        t = time.time()
        os.utime(a, (t, t))
        os.utime(b, (t - 100, t - 100))
        with open(os.path.join(self.cfg, "daemon.lock"), "w") as f:
            json.dump({"pid": 1}, f)  # init holds neither
        self.assertEqual(h.find_daemon_socket(), a)
        if os.path.exists("/proc/net/unix"):
            self.assertTrue(h.pid_holds_socket(os.getpid(), b))
            self.assertFalse(h.pid_holds_socket(1, b) is True)

    def test_control_key(self):
        self.assertIsNone(h.daemon_control_key())
        with open(os.path.join(self.cfg, "daemon", "control.key"), "w") as f:
            f.write("abcdef0123456789abcdef0123456789\n")
        self.assertEqual(h.daemon_control_key(), "abcdef0123456789abcdef0123456789")


class FakeDaemonTest(unittest.TestCase):
    KEY = "0123456789abcdef0123456789abcdef"

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="thd")
        self.cfg = os.path.join(self.tmp, "cfg")
        os.makedirs(os.path.join(self.cfg, "daemon"))
        with open(os.path.join(self.cfg, "daemon", "control.key"), "w") as f:
            f.write(self.KEY)
        self.root = os.path.join(self.tmp, "run")
        self.sock_dir = os.path.join(self.root, h.daemon_socket_hash(self.cfg))
        os.makedirs(self.sock_dir)
        self.path = os.path.join(self.sock_dir, "control.sock")
        self._saved = (h.claude_config_dir, h.daemon_runtime_root)
        h.claude_config_dir = lambda: self.cfg
        h.daemon_runtime_root = lambda: self.root
        self.server = None

    def tearDown(self):
        h.claude_config_dir, h.daemon_runtime_root = self._saved
        if self.server:
            self.server.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def serve(self, handler):
        self.server = FakeDaemon(self.path, handler)
        return self.server

    def test_no_socket_is_enodaemon(self):
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_ping()
        self.assertEqual(cm.exception.code, "ENODAEMON")

    def test_dead_socket_is_enodaemon(self):
        s = socket.socket(socket.AF_UNIX)
        s.bind(self.path)
        s.close()  # file stays, nobody listens
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_list()
        self.assertEqual(cm.exception.code, "ENODAEMON")

    def test_ping_and_list(self):
        jobs = [{"short": "7b9f8c4c", "state": "done"}]

        def handler(req, conn):
            if req["op"] == "ping":
                return {"ok": True, "op": "ping", "version": "2.1.286", "proto": 1}
            return {"ok": True, "op": "list", "jobs": jobs}

        srv = self.serve(handler)
        self.assertEqual(h.daemon_ping()["version"], "2.1.286")
        self.assertEqual(h.daemon_list(), jobs)
        self.assertEqual([r["proto"] for r in srv.requests], [1, 1])

    def test_ping_proto_mismatch_is_eproto(self):
        self.serve(lambda req, conn: {"ok": True, "op": "ping", "version": "9.0.0", "proto": 2})
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_ping()
        self.assertEqual(cm.exception.code, "EPROTO")

    def test_estarting_is_retried(self):
        calls = []

        def handler(req, conn):
            calls.append(1)
            if len(calls) < 3:
                return {"ok": False, "error": "starting", "code": "ESTARTING"}
            return {"ok": True, "op": "has", "alive": True, "present": True, "ready": True}

        self.serve(handler)
        self.assertTrue(h.daemon_has("7b9f8c4c")["alive"])
        self.assertEqual(len(calls), 3)

    def test_dispatch_sends_key_nonce_and_timeout(self):
        srv = self.serve(lambda req, conn: {"ok": True, "op": "dispatch", "short": req["d"]["short"], "pid": 42,
                                            "messagingSock": "", "via": "spare"})
        d = h.daemon_prompt_spec("/w", "hi", flags=["--model", "haiku"])
        r = h.daemon_dispatch(d, timeout_ms=4000)
        req = srv.requests[0]
        self.assertEqual(req["op"], "dispatch")
        self.assertEqual(req["auth"], self.KEY)
        self.assertEqual(req["timeoutMs"], 4000)
        self.assertRegex(req["d"]["nonce"], h.SHORT_RE)
        self.assertEqual(r["nonce"], req["d"]["nonce"])
        self.assertEqual(r["pid"], 42)
        self.assertNotIn("nonce", d)  # caller's spec untouched

    def test_await_ack(self):
        srv = self.serve(lambda req, conn: {"ok": True, "op": "await-ack", "short": req["short"], "pid": 1})
        h.daemon_await_ack("7b9f8c4c", "0000abcd", 2000)
        self.assertEqual(srv.requests[0], {"op": "await-ack", "short": "7b9f8c4c", "timeoutMs": 2000,
                                           "nonce": "0000abcd", "proto": 1})

    def test_reply_and_kill_requests(self):
        srv = self.serve(lambda req, conn: {"ok": True, "op": req["op"]})
        h.daemon_reply("7b9f8c4c", "hello", next_turn=True)
        h.daemon_kill("7b9f8c4c", evict=True)
        h.daemon_kill("7b9f8c4c", signal_name="SIGKILL")
        self.assertEqual(srv.requests[0], {"op": "reply", "short": "7b9f8c4c", "text": "hello", "auth": self.KEY,
                                           "nextTurn": True, "proto": 1})
        self.assertEqual(srv.requests[1], {"op": "kill", "short": "7b9f8c4c", "evict": True, "proto": 1})
        self.assertEqual(srv.requests[2], {"op": "kill", "short": "7b9f8c4c", "signal": "SIGKILL", "proto": 1})

    def test_reply_to_retired_worker_is_enosession(self):
        self.serve(lambda req, conn: {"ok": False, "error": "job not found — it may have already exited",
                                      "code": "ENOJOB"})
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_reply("7b9f8c4c", "x")
        self.assertEqual((cm.exception.code, cm.exception.daemon_code), ("ENOSESSION", "ENOJOB"))

    def test_missing_key_is_eauth_before_connecting(self):
        os.remove(os.path.join(self.cfg, "daemon", "control.key"))
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_reply("7b9f8c4c", "x")
        self.assertEqual(cm.exception.code, "EAUTH")

    def test_silence_is_etimeout(self):
        def handler(req, conn):
            time.sleep(1.5)
            return {"ok": True}

        self.serve(handler)
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_request({"op": "list"}, timeout=0.3)
        self.assertEqual(cm.exception.code, "ETIMEOUT")

    def test_close_without_reply_is_edaemon(self):
        def handler(req, conn):
            conn.close()
            return None

        self.serve(handler)
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_list()
        self.assertEqual(cm.exception.code, "EDAEMON")

    def test_subscribe_replays_the_live_stream(self):
        events = fixture_jsonl("subscribe_kill_settled.jsonl")
        # deliver it in awkward chunks: split mid-line
        blob = b"".join((json.dumps(e) + "\n").encode("utf-8") for e in events)
        chunks = [blob[i:i + 777] for i in range(0, len(blob), 777)]
        srv = self.serve(lambda req, conn: chunks)
        got = list(h.daemon_subscribe("7b9f8c4c", tail=5, timeout=5))
        self.assertEqual(got, events)
        self.assertEqual(srv.requests[0], {"proto": 1, "op": "subscribe", "short": "7b9f8c4c", "tail": 5})
        self.assertEqual(got[0]["type"], "snapshot")
        self.assertEqual(got[-1], {"type": "settled", "outcome": "killed"})

    def test_subscribe_unknown_short_is_enosession(self):
        self.serve(lambda req, conn: {"ok": False, "error": "job not found", "code": "ENOJOB"})
        with self.assertRaises(h.DaemonError) as cm:
            list(h.daemon_subscribe("00000000"))
        self.assertEqual(cm.exception.code, "ENOSESSION")

    def test_attach_raw_stream_and_keys(self):
        screen = fixture_bytes("attach_raw_before_shifttab.bin")
        after = fixture_bytes("attach_raw_after_shifttab.bin")
        got_keys = []

        def handler(req, conn):
            # reply line and the first screen bytes in ONE packet: the client must keep the backlog
            conn.sendall((json.dumps({"ok": True, "op": "attach", "decModes": [2004], "via": "adopted",
                                      "state": "done"}) + "\n").encode() + screen[:100])
            time.sleep(0.05)
            conn.sendall(screen[100:])
            conn.settimeout(3)
            got_keys.append(conn.recv(100))
            conn.sendall(after)
            time.sleep(0.2)
            conn.close()
            return None

        srv = self.serve(handler)
        a = h.DaemonAttach("7b9f8c4c", cols=100, rows=30)
        self.assertEqual(a.reply["op"], "attach")
        req = srv.requests[0]
        self.assertEqual((req["op"], req["auth"], req["cols"], req["rows"]), ("attach", self.KEY, 100, 30))
        buf = b""
        end = time.time() + 2
        while len(buf) < len(screen) and time.time() < end:
            d = a.read(0.3)
            if d is None:
                break
            buf += d
        self.assertEqual(buf, screen)
        a.send_keys(b"\x1b[Z")
        rest = b""
        while True:
            d = a.read(1.0)
            if d is None:
                break
            rest += d
        a.close()
        self.assertEqual(got_keys, [b"\x1b[Z"])
        self.assertEqual(rest, after)
        s = h.Screen(rows=30, cols=100)
        s.feed((buf + rest).decode("utf-8"))
        text = "\n".join(s.lines())
        self.assertIn("accept edits on", text)

    def test_attach_refused(self):
        self.serve(lambda req, conn: {"ok": False, "error": "attach rejected", "code": "EAUTH"})
        with self.assertRaises(h.DaemonError) as cm:
            h.DaemonAttach("7b9f8c4c")
        self.assertEqual(cm.exception.code, "EAUTH")

    def test_daemon_status_running(self):
        self.serve(lambda req, conn: {"ok": True, "op": "ping", "version": "2.1.286", "proto": 1})
        st = h.daemon_status()
        self.assertEqual((st["running"], st["proto"], st["version"]), (True, 1, "2.1.286"))
        self.assertIn(st["auth"], ("ok", "needs_login", "unknown"))

    def test_daemon_status_down(self):
        st = h.daemon_status()
        self.assertEqual((st["running"], st["code"]), (False, "ENODAEMON"))


class FixtureShapeTest(unittest.TestCase):
    """The live captures keep the shapes the helper's later tasks (follow/sessions) rely on."""

    def test_subscribe_starts_with_snapshot_record(self):
        for name in ("subscribe_new_session_mcp_dialog.jsonl", "subscribe_reply.jsonl",
                     "subscribe_reply_while_busy.jsonl", "subscribe_kill_settled.jsonl"):
            evs = fixture_jsonl(name)
            self.assertEqual(evs[0]["type"], "snapshot", name)
            rec = evs[0]["record"]
            for k in ("short", "sessionId", "pid", "cwd", "state", "tempo", "source"):
                self.assertIn(k, rec, (name, k))
            self.assertIsInstance(evs[0]["streamTail"], list)
            self.assertTrue(set(e["type"] for e in evs) <= {"snapshot", "stream", "state", "settled"}, name)
            for e in evs:
                if e["type"] == "stream":
                    self.assertIsInstance(e["line"], str)
                elif e["type"] == "state":
                    self.assertIsInstance(e["patch"], dict)

    def test_reply_stream_renders_the_answer(self):
        evs = fixture_jsonl("subscribe_reply.jsonl")
        s = h.Screen(rows=30, cols=100)
        s.feed("".join(e["line"] for e in evs if e["type"] == "stream"))
        self.assertIn("● PONG-TWO", "\n".join(s.lines()))
        patches = [e["patch"] for e in evs if e["type"] == "state"]
        self.assertEqual(patches[0]["tempo"], "active")
        self.assertEqual((patches[-1]["state"], patches[-1]["tempo"]), ("done", "idle"))

    def test_reply_while_busy_queues_a_second_turn(self):
        patches = [e["patch"] for e in fixture_jsonl("subscribe_reply_while_busy.jsonl") if e["type"] == "state"]
        details = [p.get("detail") for p in patches]
        self.assertEqual([p.get("tempo") for p in patches], ["active", "idle", "active", "idle"])
        self.assertEqual(details[2], "Then reply with exactly: PONG-THREE.")

    def test_new_session_stream_shows_the_mcp_dialog(self):
        evs = fixture_jsonl("subscribe_new_session_mcp_dialog.jsonl")
        s = h.Screen(rows=40, cols=120)
        s.feed("".join(e["line"] for e in evs if e["type"] == "stream"))
        self.assertIn("new MCP servers found in this project", "\n".join(s.lines()))
        self.assertEqual(evs[0]["record"]["source"], "fleet")

    def test_state_and_registry_samples(self):
        st = fixture_json("state_live_idle.json")
        for k in ("state", "detail", "tempo", "sessionId", "resumeSessionId", "daemonShort", "respawnFlags",
                  "intent", "name", "cwd", "inFlight", "tokens", "linkScanPath"):
            self.assertIn(k, st)
        self.assertEqual(st["daemonShort"], st["sessionId"][:8])
        reg = fixture_json("registry_bg_idle.json")
        self.assertEqual((reg["kind"], reg["jobId"]), ("bg", reg["sessionId"][:8]))
        self.assertIn(reg["status"], ("busy", "idle", "waiting"))
        killed = fixture_json("state_killed.json")
        self.assertEqual((killed["state"], killed["tempo"]), ("done", "idle"))


class CliTest(unittest.TestCase):
    def test_daemon_status_command_without_daemon(self):
        tmp = tempfile.mkdtemp(prefix="thd")
        try:
            env = dict(os.environ, HOME=tmp, CLAUDE_CONFIG_DIR=os.path.join(tmp, "cfg"),
                       TERMUX_VERSION="1", PREFIX=tmp)
            env.pop("ANTHROPIC_API_KEY", None)
            env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)
            p = subprocess.run([sys.executable, HELPER_PATH, "daemon-status"], stdout=subprocess.PIPE, env=env,
                               timeout=20)
            out = json.loads(p.stdout.decode())
            self.assertEqual(p.returncode, 0)
            self.assertEqual((out["running"], out["code"]), (False, "ENODAEMON"))
            self.assertIn(out["auth"], ("needs_login", "unknown"))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
