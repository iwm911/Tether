"""Live test of blocking dialogs (decision 5) and mode cycling (TETHER_LIVE=1) against this machine's Claude Code
daemon, with the real helper commands and the key sequences the app sends. One throwaway haiku session in
~/tether-exp/live/mcp-dialog, whose project .mcp.json gets two servers with fresh names each run (so they are "new"
and the dialog shows, without a new trusted folder in ~/.claude.json every run); the session, its job and its
transcripts are always removed:

  new -> blocked on "N new MCP servers found" (needs_you, pending dialog mcp_servers, no reply yet)
      -> every server unchecked from the phone (Down x (n+1), Up x (n-i), Space) -> Continue (Down x (n+1), Enter)
      -> the first turn runs; "/model sonnet" -> the "Switch model?" dialog -> "2" (No, go back)
      -> key {mode: "plan"} lands on plan; {mode: "auto"} is refused on haiku (EINVAL); -> rm

    TETHER_LIVE=1 python3 -m unittest tools/helper_tests/test_dialogs_live.py
"""

import json
import os
import shutil
import sys
import time
import unittest
import uuid

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper  # noqa
from test_sessions_live import LIVE_DIR, claude_agents, helper  # noqa

h = load_helper()


def toggle_keys(n, i):
    return ["down"] * (n + 1) + ["up"] * (n - i) + ["space"]


def continue_keys(n):
    return ["down"] * (n + 1) + ["enter"]


@unittest.skipUnless(os.environ.get("TETHER_LIVE") == "1", "set TETHER_LIVE=1 to run against this machine's daemon")
class DialogsLiveTest(unittest.TestCase):
    def setUp(self):
        self.dir = os.path.join(LIVE_DIR, "mcp-dialog")
        os.makedirs(self.dir, exist_ok=True)
        tag = uuid.uuid4().hex[:6]
        self.servers = ["tether-%s-one" % tag, "tether-%s-two" % tag]
        with open(os.path.join(self.dir, ".mcp.json"), "w") as f:
            json.dump({"mcpServers": dict((n, {"command": "/bin/true"}) for n in self.servers)}, f)
        self.sid = self.short = None

    def tearDown(self):
        try:
            if self.sid:
                helper("rm", self.sid)
                try:
                    h.daemon_kill(self.short, evict=True)
                except h.DaemonError:
                    pass
                jd = os.path.join(h.JOBS_DIR, self.short)
                if os.path.isdir(jd):
                    shutil.rmtree(jd, ignore_errors=True)
                removed = h.read_removed()
                if removed.pop(self.sid, None) is not None:
                    h.write_json_atomic(h.REMOVED_PATH, removed)
        finally:
            shutil.rmtree(os.path.join(h.CLAUDE_PROJECTS, h.project_dir_name(self.dir)), ignore_errors=True)
            try:
                os.remove(os.path.join(self.dir, ".mcp.json"))
            except OSError:
                pass
        if self.sid:
            left = [a for a in claude_agents() if a.get("id") == self.short or a.get("sessionId") == self.sid]
            assert not left, "throwaway session %s still listed: %s" % (self.short, left)

    def session(self):
        rc, out, err = helper("sessions", "--cwd", self.dir, "--limit", "10")
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

    def key(self, keys):
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"keys": keys}))
        self.assertEqual((rc, out), (0, {"ok": True}), err)

    def test_mcp_dialog_switch_model_dialog_and_mode(self):
        rc, s, err = helper("new", stdin=json.dumps({"cwd": self.dir, "prompt": "Reply with exactly: DIALOG-PONG. No tools.",
                                                     "model": "haiku", "trust": True, "name": "tether live dialogs"}))
        self.assertEqual(rc, 0, (s, err))
        self.sid, self.short = s["sessionId"], s["short"]

        # Blocked on the project's MCP servers until the phone answers.
        s = self.wait_session(lambda x: (x.get("pending") or {}).get("dialog") == "mcp_servers", 60)
        p = s["pending"]
        print("\nmcp dialog ->", json.dumps({k: p[k] for k in ("title", "options", "keys")}))
        self.assertEqual((s["state"], p["kind"]), ("needs_you", "dialog"))
        labels = [o["label"] for o in p["options"]]
        for n in self.servers:
            self.assertIn(n, labels)
        time.sleep(3)
        self.assertIsNone(self.session()["lastText"], "the first turn must wait for the dialog")
        # A message now would reach Claude before the session's own first prompt (review round 2): refused.
        rc, out, err = helper("send", self.sid, stdin=json.dumps({"text": "Reply with exactly: TOO-EARLY"}))
        print("send during the startup dialog ->", out)
        self.assertEqual((rc, out.get("code")), (1, "EINVAL"), (out, err))

        # Uncheck every server from the phone, checking the screen after each toggle.
        n = len(p["options"])
        for i in range(n):
            if p["options"][i]["checked"]:
                self.key(toggle_keys(n, i))
                s = self.wait_session(lambda x, i=i: (x.get("pending") or {}).get("options", [{}] * n)[i].get("checked") is False, 20)
                p = s["pending"]
        self.assertEqual([o["checked"] for o in p["options"]], [False] * n)

        # Continue: the session starts its first turn.
        self.key(continue_keys(n))
        s = self.wait_session(lambda x: x["state"] == "idle" and x["lastText"], 120)
        self.assertEqual((s["state"], s["lastText"]), ("idle", "DIALOG-PONG"))

        # /model mid-conversation opens a blocking confirmation: answered with its option key.
        rc, out, err = helper("send", self.sid, stdin=json.dumps({"text": "/model sonnet"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": False}), err)
        s = self.wait_session(lambda x: (x.get("pending") or {}).get("title") == "Switch model?", 30)
        p = s["pending"]
        print("model dialog ->", json.dumps({k: p[k] for k in ("dialog", "title", "options")}))
        self.assertEqual((s["state"], p["title"]), ("needs_you", "Switch model?"))
        self.assertEqual([o.get("key") for o in p["options"]], ["1", "2"])
        self.key(["2"])  # No, go back
        s = self.wait_session(lambda x: x["state"] == "idle" and x["pending"] is None, 30)
        self.assertEqual(s["state"], "idle")

        # Mode: Shift+Tab read back from the footer. Haiku has no auto mode: asking for it is refused.
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"mode": "plan"}))
        print("mode plan ->", out)
        self.assertEqual((rc, out), (0, {"ok": True, "permissionMode": "plan"}), err)
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"mode": "auto"}), timeout=120)
        print("mode auto ->", out)
        self.assertEqual((rc, out.get("code")), (1, "EINVAL"), (out, err))
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"mode": "default"}))
        self.assertEqual((rc, out), (0, {"ok": True, "permissionMode": "default"}), err)
        rc, out, err = helper("key", self.sid, stdin=json.dumps({"mode": ""}))
        print("mode once ->", out)
        self.assertEqual((rc, out.get("permissionMode")), (0, "acceptEdits"), err)

        rc, out, err = helper("rm", self.sid)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertEqual([a for a in claude_agents() if a.get("id") == self.short], [])

    def test_a_session_stopped_on_its_startup_dialog_continues_under_its_id(self):
        # Review round 2: stopped before its first turn (no transcript), it was listed done but `send` failed.
        rc, s, err = helper("new", stdin=json.dumps({"cwd": self.dir, "prompt": "Reply with exactly: FIRST-PONG. No tools.",
                                                     "model": "haiku", "trust": True, "name": "tether live relaunch"}))
        self.assertEqual(rc, 0, (s, err))
        self.sid, self.short = s["sessionId"], s["short"]
        s = self.wait_session(lambda x: (x.get("pending") or {}).get("dialog") == "mcp_servers", 60)
        self.assertEqual(s["state"], "needs_you")
        rc, out, err = helper("stop", self.sid)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        s = self.wait_session(lambda x: x["process"] == "retired", 30)
        print("\nstopped on the dialog ->", {k: s[k] for k in ("state", "process", "heldBy")})
        self.assertEqual((s["state"], s["process"]), ("done", "retired"))

        rc, out, err = helper("send", self.sid, stdin=json.dumps({"text": "Reply with exactly: RELAUNCH-PONG. No tools."}))
        print("send ->", out, err.strip()[-300:])
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        # The servers are still new: the dialog comes back, answered as before; then the message runs, same id.
        s = self.wait_session(lambda x: (x.get("pending") or {}).get("dialog") == "mcp_servers" or x["lastText"], 60)
        print("after the wake ->", {k: s[k] for k in ("state", "process", "lastText", "waitingFor")})
        if (s.get("pending") or {}).get("dialog") == "mcp_servers":
            self.key(continue_keys(len(s["pending"]["options"])))
        s = self.wait_session(lambda x: x["state"] == "idle" and x["lastText"], 120)
        print("then ->", {k: s[k] for k in ("state", "process", "lastText", "waitingFor", "pending")})
        self.assertEqual((s["sessionId"], s["lastText"]), (self.sid, "RELAUNCH-PONG"))
        print("relaunched ->", {k: s[k] for k in ("sessionId", "state", "process", "lastText", "model")})
        self.assertIn("haiku", s["model"] or "")  # launched again with its own flags, not the settings default

        rc, out, err = helper("rm", self.sid)
        self.assertEqual((rc, out), (0, {"ok": True}), err)


if __name__ == "__main__":
    unittest.main()
