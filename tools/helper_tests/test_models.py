"""`models`: Claude Code's /model list, asked from its initialize reply (a fake `claude` here), cached per Claude
version and account, with a fallback of the aliases plus the account's cached extra models.

    python3 -m unittest tools/helper_tests/test_models.py
"""

import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from fake_home import FakeHome  # noqa

# A stand-in `claude`: --version, and the stream-json initialize handshake. Every initialize is logged so the
# tests can tell a cached answer from a fresh one. FAKE_CLAUDE_MODE=silent never answers; =fail exits.
FAKE_CLAUDE = r'''#!%(python)s
import json, os, sys, time
log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calls.log")
mode = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mode")).read().strip() \
    if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mode")) else ""
if sys.argv[1:] == ["--version"]:
    print("%(version)s (Claude Code)")
    sys.exit(0)
with open(log, "a") as f:
    f.write(json.dumps(sys.argv[1:]) + "\n")
if mode == "fail":
    sys.exit(1)
req = json.loads(sys.stdin.readline())
if mode == "silent":
    time.sleep(30)
    sys.exit(0)
print(json.dumps({"type": "system", "subtype": "noise"}))
print(json.dumps({"type": "control_response", "response": {"subtype": "success", "request_id": req["request_id"],
    "response": {"commands": [], "models": [
        {"value": "default", "resolvedModel": "claude-opus-5-5", "displayName": "Default (recommended)",
         "description": "Opus 5.5", "supportsEffort": True},
        {"value": "fable", "resolvedModel": "claude-fable-5-2", "displayName": "Fable 5.2",
         "description": "For your toughest challenges"},
        {"value": "claude-opus-4-8", "displayName": "Opus 4.8"},
        {"displayName": "no value: dropped"},
    ]}}}))
sys.stdout.flush()
time.sleep(30)  # like the CLI, stays up until killed
'''


class ModelsTest(unittest.TestCase):
    def setUp(self):
        self.home = FakeHome()
        self.bin = self.home.path("bin")
        os.makedirs(self.bin)
        self.claude = os.path.join(self.bin, "claude")
        self.write_claude("2.1.329")

    def tearDown(self):
        self.home.close()

    def write_claude(self, version):
        with open(self.claude, "w") as f:
            f.write(FAKE_CLAUDE % {"python": sys.executable, "version": version})
        os.chmod(self.claude, 0o755)

    def mode(self, m):
        with open(os.path.join(self.bin, "mode"), "w") as f:
            f.write(m)

    def calls(self):
        p = os.path.join(self.bin, "calls.log")
        if not os.path.exists(p):
            return []
        with open(p) as f:
            return [json.loads(l) for l in f if l.strip()]

    def account(self, extra):
        p = self.home.path(".claude.json")
        data = {}
        if os.path.exists(p):
            with open(p) as f:
                data = json.load(f)
        data["oauthAccount"] = {"accountUuid": "acct-1", "organizationUuid": "org-1"}
        data["additionalModelOptionsCache"] = extra
        with open(p, "w") as f:
            json.dump(data, f)

    def models(self):
        rc, out, err = self.home.run("models", "--claude", self.claude)
        self.assertEqual(0, rc, err)
        return out

    def test_lists_what_initialize_reports_and_asks_without_a_session(self):
        out = self.models()
        self.assertEqual("claude", out["source"])
        self.assertEqual(["default", "fable", "claude-opus-4-8"], [m["value"] for m in out["models"]])
        fable = out["models"][1]
        self.assertEqual({"value": "fable", "displayName": "Fable 5.2", "description": "For your toughest challenges",
                          "resolvedModel": "claude-fable-5-2"}, fable)
        self.assertNotIn("resolvedModel", out["models"][2])
        argv = self.calls()[0]
        for flag in ("-p", "--no-session-persistence", "--strict-mcp-config"):
            self.assertIn(flag, argv)
        self.assertIn('{"disableAllHooks":true}', argv)

    def test_cached_until_claude_version_or_account_models_change(self):
        self.models()
        self.models()
        self.assertEqual(1, len(self.calls()))
        self.write_claude("2.1.330")
        self.models()
        self.assertEqual(2, len(self.calls()))
        self.account([{"value": "claude-fable-5-2[1m]", "label": "Fable", "description": "Fable 5.2"}])
        self.models()
        self.assertEqual(3, len(self.calls()))

    def test_falls_back_to_aliases_and_account_models_when_claude_cannot_answer(self):
        self.mode("fail")
        self.account([{"value": "claude-fable-5-2[1m]", "label": "Fable", "description": "Fable 5.2 · Most capable"},
                      {"value": "opus", "label": "dup"}])
        out = self.models()
        self.assertEqual("fallback", out["source"])
        self.assertEqual(["default", "opus", "sonnet", "haiku", "claude-fable-5-2[1m]"], [m["value"] for m in out["models"]])
        self.assertEqual("Fable", out["models"][-1]["displayName"])
        self.assertFalse(os.path.exists(self.home.path(".tether", "cache", "models.json")))  # a fallback isn't cached

    def test_gives_up_on_a_claude_that_never_answers(self):
        self.mode("silent")
        t = time.time()
        self.assertIsNone(self.home.h.initialize_models(self.claude, None, timeout=1))
        self.assertLess(time.time() - t, 8)


if __name__ == "__main__":
    unittest.main()
