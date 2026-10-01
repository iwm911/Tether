"""Live daemon test (TETHER_LIVE=1): a throwaway haiku session in ~/tether-exp/live, driven only through the
daemon control socket: dispatch -> subscribe -> reply -> kill -> resume (same short, no fork) -> attach +
Shift+Tab -> kill evict + `claude rm`. Always removes the session, even on failure.

    TETHER_LIVE=1 python3 -m unittest tools.helper_tests.test_daemon_live   (or discover with TETHER_LIVE=1)
"""

import glob
import json
import os
import subprocess
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper  # noqa

h = load_helper()
LIVE_DIR = os.path.expanduser("~/tether-exp/live")


def claude_agents():
    out = subprocess.run(["claude", "agents", "--json", "--all"], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, timeout=60).stdout
    d = json.loads(out.decode() or "[]")
    return d if isinstance(d, list) else []


def wait_idle(short, timeout=90):
    """Follow subscribe until the job settles into tempo idle/blocked after being active; returns the
    rendered screen text and the state patches."""
    patches, raw = [], ""
    seen_active = False
    end = time.time() + timeout
    for ev in h.daemon_subscribe(short, tail=0, timeout=timeout):
        if ev.get("type") == "stream":
            raw += ev["line"]
        elif ev.get("type") == "state":
            p = ev["patch"]
            patches.append(p)
            if p.get("tempo") == "active":
                seen_active = True
            if seen_active and p.get("tempo") in ("idle", "blocked"):
                break
        elif ev.get("type") == "settled":
            break
        if time.time() > end:
            break
    s = h.Screen(rows=40, cols=120)
    s.feed(raw)
    return "\n".join(s.lines()), patches


def attach_screen(short, keys=None, settle=2.5):
    with h.DaemonAttach(short, cols=120, rows=40) as a:
        s = h.Screen(rows=40, cols=120)

        def pump(sec):
            end = time.time() + sec
            while time.time() < end:
                d = a.read(0.3)
                if d is None:
                    return
                s.feed(d.decode("utf-8", "replace"))

        pump(settle)
        before = "\n".join(s.lines())
        if keys:
            a.send_keys(keys)
            pump(settle)
        return before, "\n".join(s.lines())


@unittest.skipUnless(os.environ.get("TETHER_LIVE") == "1", "set TETHER_LIVE=1 to run against this machine's daemon")
class DaemonLiveTest(unittest.TestCase):
    def setUp(self):
        os.makedirs(LIVE_DIR, exist_ok=True)
        self.short = None
        self.sid = None

    def tearDown(self):
        if not self.short:
            return
        try:
            h.daemon_kill(self.short, evict=True)
        except h.DaemonError:
            pass
        subprocess.run(["claude", "rm", self.short], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        for p in glob.glob(os.path.expanduser("~/.claude/projects/*/%s.jsonl" % self.sid)):
            if os.path.dirname(p).endswith("tether-exp-live"):
                os.remove(p)
        left = [a for a in claude_agents() if a.get("id") == self.short]
        assert not left, "throwaway session %s still listed: %s" % (self.short, left)

    def test_full_cycle(self):
        st = h.daemon_ping()
        self.assertEqual(st["proto"], 1)
        self.assertIsInstance(h.daemon_list(), list)

        d = h.daemon_prompt_spec(LIVE_DIR, "Reply with exactly: PONG-ONE. Do not use any tools.",
                                 flags=["--model", "haiku"], name="tether live test")
        self.short, self.sid = d["short"], d["sessionId"]
        r = h.daemon_dispatch(d)
        self.assertEqual(r["short"], self.short)
        listed = []
        end = time.time() + 20
        while time.time() < end and not listed:
            listed = [a for a in claude_agents() if a.get("id") == self.short]
            if not listed:
                time.sleep(1)
        self.assertEqual(len(listed), 1)
        self.assertEqual(listed[0]["sessionId"], self.sid)
        self.assertEqual(listed[0]["id"], self.sid[:8])

        time.sleep(3)
        _before, screen = attach_screen(self.short)
        if "MCP servers found" in screen:  # project .mcp.json consent dialog blocks the first prompt
            attach_screen(self.short, b"\x1b")
        tpath = os.path.join(os.path.expanduser("~/.claude/projects"), h.project_dir_name(LIVE_DIR),
                             self.sid + ".jsonl")
        end = time.time() + 90
        while time.time() < end and not (os.path.exists(tpath) and "PONG-ONE" in (h.read_text(tpath, "") or "")):
            time.sleep(1)
        self.assertIn("PONG-ONE", h.read_text(tpath, ""))

        h.daemon_reply(self.short, "Reply with exactly: PONG-TWO. No tools.")
        screen, patches = wait_idle(self.short)
        self.assertIn("PONG-TWO", screen)

        h.daemon_kill(self.short)
        end = time.time() + 15
        while time.time() < end and h.daemon_has(self.short).get("alive"):
            time.sleep(0.3)
        with self.assertRaises(h.DaemonError) as cm:
            h.daemon_reply(self.short, "x")
        self.assertEqual(cm.exception.code, "ENOSESSION")

        size = os.path.getsize(tpath)
        h.daemon_dispatch(h.daemon_resume_spec(self.sid, LIVE_DIR, flags=["--model", "haiku"], transcript_path=tpath))
        time.sleep(2)
        h.daemon_reply(self.short, "Reply with exactly: PONG-THREE. No tools.")
        screen, _p = wait_idle(self.short)
        self.assertIn("PONG-THREE", screen)
        self.assertGreater(os.path.getsize(tpath), size)
        mine = [a for a in claude_agents() if a.get("sessionId") == self.sid]
        self.assertEqual([a["id"] for a in mine], [self.short])  # same short, no fork
        self.assertEqual(glob.glob(os.path.join(os.path.dirname(tpath), "*.jsonl")), [tpath])

        before, after = attach_screen(self.short, b"\x1b[Z")
        self.assertIn("manual mode on", before)
        self.assertIn("accept edits on", after)


if __name__ == "__main__":
    unittest.main()
