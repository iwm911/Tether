"""Live end-to-end test of the one-session protocol (TETHER_LIVE=1) against this machine's Claude Code daemon,
running the real helper commands: new -> follow (draft, then the final line) -> stop -> send (wakes under the
SAME short) -> key shift-tab -> rm. One throwaway haiku session in ~/tether-exp/live, always removed.

    TETHER_LIVE=1 python3 -m unittest tools/helper_tests/test_sessions_live.py
"""

import glob
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import HELPER_PATH, load_helper  # noqa

h = load_helper()
LIVE_DIR = os.path.expanduser("~/tether-exp/live")


def helper(*args, stdin=None, timeout=120):
    p = subprocess.run([sys.executable, HELPER_PATH] + list(args), input=(stdin or "").encode(), stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=timeout)
    out = [json.loads(l) for l in p.stdout.decode().splitlines() if l.strip()]
    return p.returncode, (out[0] if len(out) == 1 else out), p.stderr.decode()


def claude_agents():
    out = subprocess.run(["claude", "agents", "--json", "--all"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         timeout=60).stdout
    d = json.loads(out.decode() or "[]")
    return d if isinstance(d, list) else []


def screen_of(short, keys=None):
    """The session's screen via a raw attach (like `claude attach`), after optional keys."""
    with h.DaemonAttach(short, cols=120, rows=40) as a:
        s = h.Screen(rows=40, cols=120)

        def pump(sec):
            end = time.time() + sec
            while time.time() < end:
                d = a.read(0.3)
                if d is None:
                    return
                s.feed(d.decode("utf-8", "replace"))

        pump(2.5)
        if keys:
            a.send_keys(keys)
            pump(2.0)
        return "\n".join(s.lines())


class Follow(object):
    """`follow <sid>` in a subprocess; events collected by a thread."""

    def __init__(self, sid):
        self.p = subprocess.Popen([sys.executable, HELPER_PATH, "follow", sid], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
        self.events = []
        self.t = threading.Thread(target=self._read)
        self.t.daemon = True
        self.t.start()

    def _read(self):
        for raw in self.p.stdout:
            try:
                self.events.append(json.loads(raw.decode()))
            except ValueError:
                pass

    def wait_for(self, pred, seconds):
        end = time.time() + seconds
        while time.time() < end:
            for i, e in enumerate(list(self.events)):
                if pred(e):
                    return i
            time.sleep(0.2)
        return None

    def close(self):
        self.p.kill()
        self.p.wait()


def assistant_text(e, needle):
    if e.get("e") != "line" or e["line"].get("type") != "assistant":
        return False
    return any(needle in (b.get("text") or "") for b in e["line"]["message"].get("content") or [] if isinstance(b, dict))


@unittest.skipUnless(os.environ.get("TETHER_LIVE") == "1", "set TETHER_LIVE=1 to run against this machine's daemon")
class SessionsLiveTest(unittest.TestCase):
    def setUp(self):
        os.makedirs(LIVE_DIR, exist_ok=True)
        self.sid = self.short = None
        self.follow = None

    def tearDown(self):
        if self.follow:
            self.follow.close()
        if not self.sid:
            return
        helper("rm", self.sid)
        try:
            h.daemon_kill(self.short, evict=True)
        except h.DaemonError:
            pass
        jd = os.path.join(h.JOBS_DIR, self.short)
        if os.path.isdir(jd):
            shutil.rmtree(jd, ignore_errors=True)
        pdir = os.path.join(h.CLAUDE_PROJECTS, h.project_dir_name(LIVE_DIR))
        for p in glob.glob(os.path.join(pdir, self.sid + "*")):
            shutil.rmtree(p, ignore_errors=True) if os.path.isdir(p) else os.remove(p)
        removed = h.read_removed()
        if removed.pop(self.sid, None) is not None:
            h.write_json_atomic(h.REMOVED_PATH, removed)
        left = [a for a in claude_agents() if a.get("id") == self.short or a.get("sessionId") == self.sid]
        assert not left, "throwaway session %s still listed: %s" % (self.short, left)

    def session(self):
        rc, out, err = helper("sessions", "--cwd", LIVE_DIR, "--limit", "50")
        self.assertEqual(rc, 0, err)
        mine = [s for s in out["sessions"] if s["sessionId"] == self.sid]
        return mine[0] if mine else None

    def wait_session(self, pred, seconds):
        end = time.time() + seconds
        s = None
        while time.time() < end:
            s = self.session()
            if s and pred(s):
                return s
            time.sleep(1)
        return s

    def test_new_follow_stop_wake_key_rm(self):
        if not h.folder_trusted(LIVE_DIR):
            rc, out, _e = helper("new", stdin=json.dumps({"cwd": LIVE_DIR, "prompt": "x", "model": "haiku"}))
            self.assertEqual((rc, out["code"]), (1, "EUNTRUSTED"))
        rc, s, err = helper("new", stdin=json.dumps({"cwd": LIVE_DIR, "prompt": "Reply with exactly: ALPHA-ONE. No tools.",
                                                     "model": "haiku", "trust": True, "name": "tether live sessions"}))
        self.assertEqual(rc, 0, (s, err))
        self.sid, self.short = s["sessionId"], s["short"]
        print("\nnew ->", json.dumps({k: s[k] for k in ("short", "state", "process", "heldBy", "model", "cwd")}))
        self.assertEqual((s["process"], s["heldBy"], s["short"]), ("live", "daemon", self.sid[:8]))
        self.assertEqual(s["cwd"], LIVE_DIR)

        s = self.wait_session(lambda x: x["state"] in ("idle", "needs_you") and x["lastText"], 120)
        if s and s["state"] == "needs_you":  # a startup dialog (e.g. project MCP servers): Esc answers it
            helper("key", self.sid, stdin=json.dumps({"keys": ["esc"]}))
            s = self.wait_session(lambda x: x["state"] == "idle" and x["lastText"], 120)
        self.assertEqual(s["lastText"], "ALPHA-ONE")
        self.assertEqual(s["model"], "claude-haiku-4-5-20251001")

        # follow: history, caughtUp, then a streamed reply: drafts grow, the final line lands, the draft clears.
        self.follow = f = Follow(self.sid)
        self.assertIsNotNone(f.wait_for(lambda e: e.get("e") == "caughtUp", 30))
        rc, out, err = helper("send", self.sid, stdin=json.dumps({
            "text": "Write one plain paragraph of about 150 words about lighthouses (no tools, no lists), "
                    "then on a new line write exactly: BETA-END"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": False}), err)
        final = f.wait_for(lambda e: assistant_text(e, "BETA-END"), 150)
        self.assertIsNotNone(final, [e.get("e") for e in f.events][-30:])
        clear = f.wait_for(lambda e: e.get("e") == "draftClear", 10)
        evs = list(f.events)
        cu = [i for i, e in enumerate(evs) if e.get("e") == "caughtUp"][0]
        drafts = [e["text"] for e in evs[cu:final] if e.get("e") == "draft"]
        statuses = [e for e in evs[cu:final] if e.get("e") == "status" and e.get("verb")]
        print("follow -> %d drafts before the final line, %d status updates, last draft %d chars, first status %s"
              % (len(drafts), len(statuses), len(drafts[-1]) if drafts else 0, statuses[0] if statuses else None))
        self.assertGreaterEqual(len(drafts), 2)
        keys = [h.text_key(d) for d in drafts]
        self.assertTrue(all(b.startswith(a[:max(0, len(a) - 40)]) for a, b in zip(keys, keys[1:])))
        self.assertTrue(statuses)
        self.assertIsNotNone(clear)
        self.assertGreater(clear, final)
        landed = [b["text"] for b in evs[final]["line"]["message"]["content"] if b.get("type") == "text"][0]
        self.assertIn(h.text_key(drafts[-1])[:200], h.text_key(landed))

        # stop: the session retires; follow reports it.
        rc, out, err = helper("stop", self.sid)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertFalse(h.daemon_has(self.short).get("alive"))
        s = self.wait_session(lambda x: x["process"] == "retired", 20)
        self.assertEqual((s["process"], s["heldBy"]), ("retired", "none"))
        self.assertIsNotNone(f.wait_for(lambda e: e.get("e") == "state" and e["session"]["process"] == "retired", 20))
        tpath = os.path.join(h.CLAUDE_PROJECTS, h.project_dir_name(LIVE_DIR), self.sid + ".jsonl")
        size = os.path.getsize(tpath)

        # send to the retired session: it wakes under the same short, same transcript, no fork.
        rc, out, err = helper("send", self.sid, stdin=json.dumps({"text": "Reply with exactly: GAMMA-THREE. No tools."}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        self.assertIsNotNone(f.wait_for(lambda e: assistant_text(e, "GAMMA-THREE"), 120))
        self.assertGreater(os.path.getsize(tpath), size)
        mine = [a for a in claude_agents() if a.get("sessionId") == self.sid]
        print("wake -> claude agents:", [(a.get("id"), a.get("state")) for a in mine])
        self.assertEqual([a["id"] for a in mine], [self.short])
        self.assertEqual(glob.glob(os.path.join(os.path.dirname(tpath), "*.jsonl")), [tpath])
        s = self.wait_session(lambda x: x["process"] == "live" and x["state"] == "idle", 30)
        self.assertEqual((s["short"], s["process"]), (self.short, "live"))

        # key shift-tab: the mode cycles, as from an attached terminal.
        before = screen_of(self.short)
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"keys": ["shift-tab"]}))
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        after = screen_of(self.short)
        print("key -> footer before: %r / after: %r" % (h.screen_mode(h._norm(before)), h.screen_mode(h._norm(after))))
        self.assertNotEqual(h.screen_mode(h._norm(before)), h.screen_mode(h._norm(after)))

        # rm: gone from the daemon, from `claude agents` and from `sessions`.
        f.close()
        self.follow = None
        rc, out, err = helper("rm", self.sid)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertFalse(h.daemon_has(self.short).get("present"))
        self.assertFalse(os.path.isdir(os.path.join(h.JOBS_DIR, self.short)))
        self.assertEqual([a for a in claude_agents() if a.get("id") == self.short], [])
        self.assertIsNone(self.session())


if __name__ == "__main__":
    unittest.main()
