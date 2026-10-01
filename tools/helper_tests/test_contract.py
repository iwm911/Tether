"""The helper's real protocol output, captured as fixtures for the app's contract test
(app/src/test/java/app/tether/remote/HelperContractTest.kt): `sessions`, `watch`, `follow` (history, live drafts),
writes and error replies, produced by running the helper against a fake ~ and a scripted daemon.

    python3 -m unittest tools/helper_tests/test_contract.py                   # checks the fixtures still match
    TETHER_WRITE_CONTRACT=1 python3 -m unittest tools/helper_tests/test_contract.py   # rewrites them

"Match" is the shape: every Session's keys, every follow event kind with its keys, every error code. Values that
depend on the run (temp paths, mtimes) are normalised before writing.
"""

import io
import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import test_sessions as ts  # noqa
from helper_loader import fixture_jsonl  # noqa

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "fixtures", "contract")
FAKE_ROOT = "/home/user"


def normalise(obj, root):
    """Temp-dir paths -> /home/user/... so the fixtures are stable."""
    s = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    return json.loads(s.replace(root, FAKE_ROOT))


def shape(obj):
    """Keys (recursively for Session-like dicts) of an output object."""
    if isinstance(obj, dict):
        return dict((k, shape(v) if isinstance(v, dict) and k in ("session", "pending", "inFlight") else None)
                    for k, v in obj.items())
    return None


def capture_sessions():
    w = ts.World()
    try:
        rc, out, err = w.home.run("sessions")
        assert rc == 0, err
        rc2, st, err = w.home.run("daemon-status")
        assert rc2 == 0, err
        hm = w.home  # as SessionsTest.test_permission_pending_from_the_transcript
        hm.transcript(w.proj, ts.SID_B, [ts.user_line("rm it", cwd=w.proj), ts.assistant_line(
            [{"type": "tool_use", "id": "toolu_p1", "name": "Bash", "input": {"command": "rm -rf build"}}])])
        hm.job("bbbb2222", sessionId=ts.SID_B, cwd=w.proj, state="working", tempo="blocked", needs="")
        hm.registry(os.getpid(), kind="bg", status="waiting", waitingFor="permission prompt", sessionId=ts.SID_B,
                    jobId="bbbb2222")
        rc3, again, err = hm.run("sessions")
        assert rc3 == 0, err
        perm = [s for s in again["sessions"] if s["sessionId"] == ts.SID_B][0]
        return normalise(out, w.home.root), normalise(st, w.home.root), normalise(perm, w.home.root)
    finally:
        w.close()


def capture_watch():
    w = ts.World()
    lines = []
    p = w.home.popen("watch")
    try:
        lines.append(json.loads(p.stdout.readline()))
        time.sleep(1.2)
        w.home.job("cccc3333", sessionId=ts.SID_C, cwd=w.proj, state="done", tempo="idle", name="renamed job",
                   updatedAt="2026-10-01T11:00:00.000Z")
        lines.append(json.loads(p.stdout.readline()))
        os.remove(os.path.join(w.home.claude, "projects", w.home.h.project_dir_name(w.other), ts.SID_D + ".jsonl"))
        lines.append(json.loads(p.stdout.readline()))
        return [normalise(l, w.home.root) for l in lines]
    finally:
        p.kill()
        p.wait()
        w.close()


def capture_follow_history():
    t = ts.FollowTest("test_history_then_caught_up_for_a_retired_session")
    t.setUp()
    try:
        t.follow_fixture()
        t.home.serve()
        out = io.StringIO()
        f = t.hh.Follower(t.hh.SessionRef(ts.FOLLOW_SID), 0, out,
                          ts.GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        f.run()
        return [normalise(e, t.home.root) for e in ts.parse(out)]
    finally:
        t.tearDown()


def capture_follow_live():
    """The recorded streaming reply replayed through the scripted daemon: drafts, status, the line, draftClear."""
    t = ts.FollowTest("test_live_stream_drafts_then_the_line_lands")
    t.setUp()
    try:
        sid = "d7e55bca-9939-4f52-8d5d-0133b3c9d471"
        hm = t.home
        tlines = fixture_jsonl("streaming_reply_transcript.jsonl")
        last_a = max(i for i, l in enumerate(tlines) if l.get("type") == "assistant")
        tp = hm.transcript(t.proj, sid, tlines[:last_a - 1])
        model = hm.serve()
        hm.job("d7e55bca", sessionId=sid, cwd=t.proj, state="working", tempo="active", name="streamer")
        model.add("d7e55bca", sid, cwd=t.proj, tempo="active", state="working")

        def land():
            with open(tp, "a") as fh:
                for l in tlines[last_a - 1:]:
                    fh.write(json.dumps(l) + "\n")

        evs = []
        for ev in fixture_jsonl("subscribe_streaming_reply.jsonl"):
            evs.append(ev)
            if ev["type"] == "stream":
                evs.append(("sleep", 0.03))
        evs += [("sleep", 0.5), land, ("sleep", 1.0), {"type": "state", "patch": {"state": "done", "tempo": "idle"}}]
        model.subscribe_events = evs
        out = io.StringIO()
        f = t.hh.Follower(t.hh.SessionRef(sid), 0, out, ts.GoneAfter(lambda: '"draftClear"' in out.getvalue(), 20))
        f.run()
        return [normalise(e, t.home.root) for e in ts.parse(out)]
    finally:
        t.tearDown()


def capture_writes():
    """Replies of the write commands, and every error code the app handles."""
    w = ts.World()
    hm = w.home
    out = []
    try:
        def run(label, *args, **kw):
            rc, res, err = hm.run(*args, **kw)
            out.append({"cmd": label, "rc": rc, "out": normalise(res, hm.root)})

        run("send", "send", ts.SID_A, stdin=json.dumps({"text": "more please"}))
        run("send-wake", "send", "cccc3333", stdin=json.dumps({"text": "again"}))
        run("key", "key", ts.SID_A, stdin=json.dumps({"keys": ["shift-tab", {"text": "/model haiku"}, "enter"]}))
        run("interrupt", "interrupt", ts.SID_A)
        run("stop", "stop", ts.SID_A)
        d = hm.folder("fresh", trusted=False)
        run("new-untrusted", "new", stdin=json.dumps({"cwd": d, "prompt": "hi"}))
        run("new", "new", stdin=json.dumps({"cwd": d, "prompt": "hi", "model": "haiku", "trust": True}))
        run("send-nosession", "send", "12345678", stdin=json.dumps({"text": "x"}))
        w.terminal()
        run("send-held", "send", ts.SID_E, stdin=json.dumps({"text": "x"}))
        run("rm", "rm", "cccc3333")
        return out
    finally:
        w.close()


def capture_all():
    sessions, status, perm = capture_sessions()
    return {
        "sessions.json": sessions,
        "session_permission.json": perm,
        "daemon_status.json": status,
        "watch.jsonl": capture_watch(),
        "follow_history.jsonl": capture_follow_history(),
        "follow_live.jsonl": capture_follow_live(),
        "writes.jsonl": capture_writes(),
    }


def write_fixture(name, data):
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, name), "w") as f:
        if name.endswith(".jsonl"):
            for l in data:
                f.write(json.dumps(l, ensure_ascii=False, separators=(",", ":")) + "\n")
        else:
            json.dump(data, f, ensure_ascii=False, indent=1)
            f.write("\n")


def read_fixture(name):
    with open(os.path.join(OUT_DIR, name)) as f:
        if name.endswith(".jsonl"):
            return [json.loads(l) for l in f if l.strip()]
        return json.load(f)


def session_shapes(sessions):
    return sorted(set(json.dumps(shape(s), sort_keys=True) for s in sessions))


def event_shapes(evs):
    return sorted(set(json.dumps({"e": e.get("e"), "keys": shape(e)}, sort_keys=True) for e in evs))


class ContractTest(unittest.TestCase):
    """The app's contract fixtures are what this helper prints today."""

    @classmethod
    def setUpClass(cls):
        cls.fresh = capture_all()
        if os.environ.get("TETHER_WRITE_CONTRACT") == "1":
            for name, data in cls.fresh.items():
                write_fixture(name, data)

    def test_sessions(self):
        fresh, saved = self.fresh["sessions.json"]["sessions"], read_fixture("sessions.json")["sessions"]
        self.assertEqual(session_shapes(fresh), session_shapes(saved))
        self.assertEqual(sorted(s["sessionId"] for s in fresh), sorted(s["sessionId"] for s in saved))
        self.assertEqual(sorted(set(s["state"] for s in fresh)), ["done", "failed", "needs_you", "working"])
        self.assertEqual(self.fresh["daemon_status.json"].keys(), read_fixture("daemon_status.json").keys())
        perm = self.fresh["session_permission.json"]
        self.assertEqual(session_shapes([perm]), session_shapes([read_fixture("session_permission.json")]))
        self.assertEqual((perm["state"], perm["pending"]["kind"], perm["pending"]["toolName"]), ("needs_you", "permission", "Bash"))

    def test_watch(self):
        fresh, saved = self.fresh["watch.jsonl"], read_fixture("watch.jsonl")
        self.assertEqual([sorted(l) for l in fresh], [sorted(l) for l in saved])
        self.assertEqual(session_shapes(fresh[0]["snapshot"]), session_shapes(saved[0]["snapshot"]))

    def test_follow(self):
        for name in ("follow_history.jsonl", "follow_live.jsonl"):
            fresh, saved = self.fresh[name], read_fixture(name)
            self.assertEqual(event_shapes(fresh), event_shapes(saved), name)
            kinds = [e["e"] for e in fresh]
            self.assertIn("caughtUp", kinds, name)
        live = [e["e"] for e in self.fresh["follow_live.jsonl"]]
        for k in ("draft", "draftClear", "status", "state", "line"):
            self.assertIn(k, live)
        hist = set(e["e"] for e in self.fresh["follow_history.jsonl"])
        self.assertEqual(hist, {"line", "peer", "task", "subagent", "todos", "state", "caughtUp"})

    def test_writes(self):
        fresh, saved = self.fresh["writes.jsonl"], read_fixture("writes.jsonl")
        self.assertEqual([(w["cmd"], w["rc"], sorted(w["out"])) for w in fresh],
                         [(w["cmd"], w["rc"], sorted(w["out"])) for w in saved])
        codes = dict((w["cmd"], w["out"].get("code")) for w in fresh if w["rc"])
        self.assertEqual(codes, {"new-untrusted": "EUNTRUSTED", "send-nosession": "ENOSESSION", "send-held": "EHELD"})


if __name__ == "__main__":
    unittest.main()
