"""Unit tests for the one-session protocol (H2 sessions/watch/probe, H3 follow events, H4 writes): a fake ~ with
Claude Code's files and a scripted fake daemon, plus streams and transcripts captured live from 2.1.287.
Run: python3 -m unittest discover -s tools/helper_tests"""

import io
import json
import os
import shutil
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import fixture_json, fixture_jsonl, fixture_path, load_helper  # noqa
from fake_home import DEAD_PID, FakeHome  # noqa

h = load_helper()

SID_A = "aaaa1111-1111-4111-8111-111111111111"  # live, working
SID_B = "bbbb2222-2222-4222-8222-222222222222"  # live, blocked on a question
SID_C = "cccc3333-3333-4333-8333-333333333333"  # retired job (killed)
SID_D = "dddd4444-4444-4444-8444-444444444444"  # transcript only
SID_E = "eeee5555-5555-4555-8555-555555555555"  # held by a terminal
SID_F = "ffff6666-6666-4666-8666-666666666666"  # failed job
SID_S = "0828ea44-3031-439e-b280-835ec4538034"  # a spare worker: not a session
FOLLOW_SID = "5a1e0c4b-1111-4222-8333-944455556666"


def user_line(text, ts="2026-10-01T10:00:00.000Z", cwd="/x", **kw):
    d = {"type": "user", "isSidechain": False, "message": {"role": "user", "content": text}, "uuid": "u-" + str(text)[:8],
         "timestamp": ts, "cwd": cwd, "gitBranch": "main"}
    d.update(kw)
    return d


def assistant_line(blocks, ts="2026-10-01T10:00:01.000Z", model="claude-haiku-4-5-20251001"):
    if isinstance(blocks, str):
        blocks = [{"type": "text", "text": blocks}]
    return {"type": "assistant", "isSidechain": False, "uuid": "a-%d" % id(blocks), "timestamp": ts,
            "message": {"model": model, "role": "assistant", "content": blocks}}


class World(object):
    """A machine with one session of every kind."""

    def __init__(self):
        self.home = FakeHome()
        hm = self.home
        self.proj = hm.folder("proj")
        self.other = hm.folder("other")
        t = time.time()
        hm.transcript(self.proj, SID_A, [user_line("build it", cwd=self.proj), assistant_line("Building now.")], mtime=t - 10)
        hm.job("aaaa1111", sessionId=SID_A, cwd=self.proj, state="working", tempo="active", name="builder",
               intent="build it", respawnFlags=["--model", "haiku", "-n", "builder", "build it"], tokens=1200,
               inFlight={"tasks": 1, "queued": 0, "kinds": ["shell"], "drainableMonitors": 0},
               children=[{"id": "12", "href": "https://github.com/x/y/pull/12", "kind": "pr"}],
               updatedAt="2026-10-01T10:00:05.000Z")
        hm.transcript(self.proj, SID_B, [user_line("ask me", cwd=self.proj)], mtime=t - 20)
        hm.job("bbbb2222", sessionId=SID_B, cwd=self.proj, state="working", tempo="blocked",
               needs="answer: Which color?", name="asker",
               block={"questions": [{"question": "Which color?", "header": "Color", "multiSelect": False,
                                     "options": [{"label": "Red", "description": ""}, {"label": "Blue", "description": ""}]}]})
        hm.registry(os.getpid(), kind="bg", status="waiting", waitingFor="input needed", sessionId=SID_B, jobId="bbbb2222")
        hm.transcript(self.proj, SID_C, [user_line("old job", cwd=self.proj), assistant_line("Done, PONG.")], mtime=t - 300)
        hm.job("cccc3333", sessionId=SID_C, cwd=self.proj, state="done", tempo="idle", name="old job",
               respawnFlags=["--model", "sonnet", "--permission-mode", "acceptEdits"],
               output={"result": "PONG"}, linkScanPath=os.path.join(hm.claude, "projects", hm.h.project_dir_name(self.proj), SID_C + ".jsonl"))
        hm.transcript(self.other, SID_D, [{"type": "permission-mode", "permissionMode": "plan", "sessionId": SID_D},
                                          user_line("plain old session", cwd=self.other),
                                          assistant_line("Hello from the past.", model="claude-opus-5-5"),
                                          {"type": "pr-link", "prNumber": 7, "prUrl": "https://github.com/x/y/pull/7"},
                                          {"type": "custom-title", "customTitle": "the past"}], mtime=t - 3600)
        hm.transcript(self.other, SID_E, [user_line("in a terminal", cwd=self.other)], mtime=t - 5)
        hm.registry(DEAD_PID, kind="interactive", status="busy", sessionId=SID_D)  # stale entry: pid gone
        self.term_pid = os.getpid() + 0
        hm.job("ffff6666", sessionId=SID_F, cwd=self.proj, state="failed", tempo="idle", name="crashed one",
               updatedAt="2026-09-01T10:00:00.000Z")
        model = hm.serve()
        model.add("aaaa1111", SID_A, cwd=self.proj, tempo="active", state="working")
        model.add("bbbb2222", SID_B, cwd=self.proj, tempo="blocked", state="working")
        model.add("cccc3333", SID_C, cwd=self.proj, tempo="idle", state="done", dying=True, outcome="killed")
        model.add("0828ea44", SID_S, cwd=hm.root, tempo="active", state="adopted", source="spare", intent="")
        self.model = model

    def terminal(self):
        """Makes SID_E held by a terminal (registry entry kind interactive of a live pid: this test process)."""
        os.remove(os.path.join(self.home.claude, "sessions", "%d.json" % os.getpid()))
        self.home.registry(os.getpid(), kind="interactive", status="busy", sessionId=SID_E, name="other")

    def close(self):
        self.home.close()


class SessionsTest(unittest.TestCase):
    def setUp(self):
        self.w = World()
        self.h = self.w.home.h

    def tearDown(self):
        self.w.close()

    def sessions(self, **kw):
        return self.h.build_sessions(self.h.Sources(), self.h.TranscriptFacts(), **kw)

    def by_sid(self, **kw):
        return dict((s["sessionId"], s) for s in self.sessions(**kw))

    def test_one_session_per_id_newest_first_spare_excluded(self):
        ss = self.sessions()
        sids = [s["sessionId"] for s in ss]
        self.assertEqual(len(sids), len(set(sids)))
        self.assertEqual(set(sids), {SID_A, SID_B, SID_C, SID_D, SID_E, SID_F})
        self.assertNotIn(SID_S, sids)
        ups = [s["updatedAt"] for s in ss]
        self.assertEqual(ups, sorted(ups, reverse=True))
        for s in ss:
            self.assertEqual(s["short"], s["sessionId"][:8])
            for k in ("sessionId", "short", "cwd", "name", "intent", "state", "waitingFor", "pending", "process",
                      "heldBy", "terminalPid", "startedAt", "updatedAt", "lastText", "tokens", "model",
                      "permissionMode", "inFlight", "children", "gitBranch"):
                self.assertIn(k, s)

    def test_live_working_job(self):
        a = self.by_sid()[SID_A]
        self.assertEqual((a["state"], a["process"], a["heldBy"]), ("working", "live", "daemon"))
        # The model the transcript last ran on wins over the job's --model flag (the CLI rewrites that flag).
        self.assertEqual((a["name"], a["intent"], a["model"], a["tokens"]),
                         ("builder", "build it", "claude-haiku-4-5-20251001", 1200))
        self.assertEqual(a["inFlight"], {"tasks": 1, "queued": 0, "kinds": ["shell"]})
        self.assertEqual(a["children"], [{"id": "12", "href": "https://github.com/x/y/pull/12", "kind": "pr"}])
        self.assertEqual(a["lastText"], "Building now.")
        self.assertEqual(a["cwd"], self.w.proj)
        self.assertIsNone(a["pending"])

    def woken_by_slash_command(self, then_prompt=False):
        """Seen live (2.1.287): a stopped session woken by `/model haiku` runs no model turn. Its worker registers
        idle and its state.json says idle, but the daemon's record stays tempo "active" for good."""
        sid, short = "9999aaaa-1111-4111-8111-111111111111", "9999aaaa"
        lines = [user_line("Reply with just OK.", cwd=self.w.proj), assistant_line("OK"),
                 user_line("<local-command-caveat>Caveat: The messages below were generated by the user while "
                           "running local commands.</local-command-caveat>", cwd=self.w.proj),
                 user_line("<command-name>/model</command-name>\n<command-message>model</command-message>\n"
                           "<command-args>haiku</command-args>", cwd=self.w.proj),
                 user_line("<local-command-stdout>Set model to Haiku 4.5</local-command-stdout>", cwd=self.w.proj)]
        if then_prompt:
            lines.append(user_line("now a real question", cwd=self.w.proj))
        self.w.home.transcript(self.w.proj, sid, lines)
        self.w.home.job(short, sessionId=sid, cwd=self.w.proj, state="done", tempo="idle",
                        inFlight={"tasks": 0, "queued": 0, "kinds": []})
        self.w.home.registry(os.getppid(), kind="bg", status="idle", sessionId=sid, jobId=short)
        self.w.model.add(short, sid, cwd=self.w.proj, tempo="active", state="running")
        return self.by_sid()[sid]

    def test_woken_by_a_slash_command_settles_to_idle(self):
        s = self.woken_by_slash_command()
        self.assertEqual((s["state"], s["process"], s["heldBy"]), ("idle", "live", "daemon"))

    def test_a_prompt_after_the_slash_command_is_still_working(self):
        self.assertEqual(self.woken_by_slash_command(then_prompt=True)["state"], "working")

    def test_tail_facts_last_entry(self):
        def last(lines):
            return self.h.scan_tail_facts("\n".join(json.dumps(l, separators=(",", ":")) for l in lines).encode())["lastEntry"]
        self.assertEqual(last([user_line("hi"), assistant_line("yo")]), "assistant")
        self.assertEqual(last([assistant_line("yo"), user_line("<local-command-stdout>x</local-command-stdout>")]), "local")
        self.assertEqual(last([assistant_line("yo"), user_line([{"type": "tool_result", "tool_use_id": "t", "content": "<local-command-stdout>"}], uuid="u-tool")]), "user")
        self.assertEqual(last([user_line("hi")]), "user")

    def test_blocked_question_is_needs_you_with_pending_question(self):
        b = self.by_sid()[SID_B]
        self.assertEqual((b["state"], b["waitingFor"]), ("needs_you", "input needed"))
        p = b["pending"]
        self.assertEqual((p["kind"], p["toolName"]), ("question", "AskUserQuestion"))
        self.assertEqual(json.loads(p["inputJson"])["questions"][0]["options"][1]["label"], "Blue")

    def test_permission_pending_from_the_transcript(self):
        hm = self.w.home
        hm.transcript(self.w.proj, SID_B, [user_line("rm it", cwd=self.w.proj), assistant_line(
            [{"type": "tool_use", "id": "toolu_p1", "name": "Bash", "input": {"command": "rm -rf build"}}])])
        hm.job("bbbb2222", sessionId=SID_B, cwd=self.w.proj, state="working", tempo="blocked", needs="")
        hm.registry(os.getpid(), kind="bg", status="waiting", waitingFor="permission prompt", sessionId=SID_B,
                    jobId="bbbb2222")
        p = self.by_sid()[SID_B]["pending"]
        self.assertEqual((p["kind"], p["toolName"], p["toolUseId"]), ("permission", "Bash", "toolu_p1"))
        self.assertIn("rm -rf build", p["inputJson"])

    def test_handoff_is_needs_you_without_a_key_pad(self):
        # Claude ended its turn handing work back: the job says blocked with a needs note while the worker sits
        # idle at its prompt. Regression: the phone showed an empty "Claude is asking" key pad for it.
        hm = self.w.home
        hm.transcript(self.w.proj, SID_B, [user_line("fix it", cwd=self.w.proj),
                                           assistant_line("Fixed. Rebuild the app and test on the phone.")])
        hm.job("bbbb2222", sessionId=SID_B, cwd=self.w.proj, state="blocked", tempo="blocked",
               needs="rebuild app and test on the phone", block=None, suggestedReply="! ./gradlew installDebug")
        hm.registry(os.getpid(), kind="bg", status="idle", sessionId=SID_B, jobId="bbbb2222")
        b = self.by_sid()[SID_B]
        self.assertEqual((b["state"], b["handoff"], b["pending"]), ("needs_you", True, None))
        self.assertEqual(b["waitingFor"], "rebuild app and test on the phone")
        self.assertEqual(b["suggestedReply"], "! ./gradlew installDebug")

    def test_handoff_with_a_background_task_running(self):
        # Same hand-off while a Monitor still runs: the worker registers as "shell", not "idle" (2.1.289).
        # Regression: the phone showed "Waiting for say "merge it"…" with an empty key pad.
        hm = self.w.home
        hm.transcript(self.w.proj, SID_B, [user_line("ship it", cwd=self.w.proj),
                                           assistant_line('PR is ready. Say "merge it" when you want it merged.')])
        hm.job("bbbb2222", sessionId=SID_B, cwd=self.w.proj, state="blocked", tempo="blocked",
               needs='say "merge it" when you want it merged', block=None, suggestedReply="merge it",
               inFlight={"tasks": 1, "queued": 0, "kinds": ["monitor"]})
        hm.registry(os.getpid(), kind="bg", status="shell", sessionId=SID_B, jobId="bbbb2222")
        b = self.by_sid()[SID_B]
        self.assertEqual((b["state"], b["handoff"], b["pending"]), ("needs_you", True, None))
        self.assertEqual(b["suggestedReply"], "merge it")

    def test_real_waits_are_not_handoffs(self):
        self.assertFalse(self.by_sid()[SID_B]["handoff"])  # an AskUserQuestion block
        self.assertIsNone(self.by_sid()[SID_B]["suggestedReply"])
        self.assertFalse(self.by_sid()[SID_A]["handoff"])  # working

    def test_retired_job_keeps_its_facts(self):
        c = self.by_sid()[SID_C]
        self.assertEqual((c["state"], c["process"], c["heldBy"]), ("done", "retired", "none"))
        self.assertEqual((c["model"], c["permissionMode"], c["lastText"]),
                         ("claude-haiku-4-5-20251001", "acceptEdits", "PONG"))

    def test_shift_tab_mode_from_respawn_flags_beats_the_transcript(self):
        # Shift+Tab: the daemon rewrites the job's --permission-mode at once; the transcript still says
        # "default" until the next turn writes a permission-mode line.
        t = time.time()
        sid = "99990000-1111-4222-8333-944455556666"
        self.w.home.transcript(self.w.proj, sid, [{"type": "permission-mode", "permissionMode": "default", "sessionId": sid},
                                                user_line("hi", cwd=self.w.proj), assistant_line("OK")], mtime=t - 30)
        self.w.home.job("99990000", sessionId=sid, cwd=self.w.proj, state="done", tempo="idle",
                      respawnFlags=["--model", "haiku", "--permission-mode", "plan"])
        self.assertEqual(self.by_sid()[sid]["permissionMode"], "plan")

    def test_failed_job(self):
        self.assertEqual(self.by_sid()[SID_F]["state"], "failed")

    def test_transcript_only_session(self):
        d = self.by_sid()[SID_D]
        self.assertEqual((d["state"], d["process"], d["heldBy"], d["terminalPid"]), ("done", "retired", "none", None))
        self.assertEqual((d["name"], d["model"], d["permissionMode"]), ("the past", "claude-opus-5-5", "plan"))
        self.assertEqual(d["intent"], "plain old session")
        self.assertEqual(d["children"], [{"id": "7", "href": "https://github.com/x/y/pull/7", "kind": "pr"}])
        self.assertEqual(d["gitBranch"], "main")
        self.assertEqual(d["cwd"], self.w.other)

    def test_terminal_held_session(self):
        self.w.terminal()
        e = self.by_sid()[SID_E]
        self.assertEqual((e["state"], e["process"], e["heldBy"], e["terminalPid"]),
                         ("working", "live", "terminal", os.getpid()))

    def test_parked_terminal_is_not_held(self):
        os.remove(os.path.join(self.w.home.claude, "sessions", "%d.json" % os.getpid()))
        self.w.home.registry(os.getpid(), kind="interactive", status="idle", sessionId=SID_A, parkedJobId="aaaa1111")
        self.assertEqual(self.by_sid()[SID_A]["heldBy"], "daemon")

    def test_daemon_down_means_retired(self):
        self.w.home.server.close()
        ss = self.h.build_sessions(self.h.Sources(), self.h.TranscriptFacts())
        a = [s for s in ss if s["sessionId"] == SID_A][0]
        self.assertEqual((a["process"], a["heldBy"]), ("retired", "none"))

    def test_cwd_limit_before(self):
        self.assertEqual(set(self.by_sid(want_cwd=self.w.other)), {SID_D, SID_E})
        self.assertEqual(len(self.sessions(limit=2)), 2)
        all_ = self.sessions()
        cut = all_[2]["updatedAt"]
        older = self.sessions(before=cut)
        self.assertTrue(all(s["updatedAt"] < cut for s in older))
        self.assertEqual([s["sessionId"] for s in older], [s["sessionId"] for s in all_ if s["updatedAt"] < cut])

    def test_removed_transcript_hides_until_it_grows(self):
        p = os.path.join(self.w.home.claude, "projects", self.h.project_dir_name(self.w.other), SID_D + ".jsonl")
        os.makedirs(self.w.home.path(".tether"), exist_ok=True)
        with open(self.h.REMOVED_PATH, "w") as fh:
            json.dump({SID_D: os.path.getsize(p)}, fh)
        self.assertNotIn(SID_D, self.by_sid())
        with open(p, "a") as f:
            f.write(json.dumps(user_line("resumed elsewhere")) + "\n")
        self.assertIn(SID_D, self.by_sid())

    def test_resolve_by_short_and_errors(self):
        r = self.h.SessionRef("cccc3333")
        self.assertEqual((r.sid, r.job_short, r.short), (SID_C, "cccc3333", "cccc3333"))
        self.assertEqual(self.h.SessionRef(SID_D[:8]).sid, SID_D)
        with self.assertRaises(self.h.DaemonError) as cm:
            self.h.SessionRef("12345678")
        self.assertEqual(cm.exception.code, "ENOSESSION")
        with self.assertRaises(self.h.HelperError):
            self.h.SessionRef("../../etc")

    def test_sessions_command(self):
        rc, out, err = self.w.home.run("sessions", "--limit", "3")
        self.assertEqual(rc, 0, err)
        self.assertEqual(len(out["sessions"]), 3)
        rc, out, err = self.w.home.run("sessions", "--cwd", self.w.other)
        self.assertEqual(set(s["sessionId"] for s in out["sessions"]), {SID_D, SID_E})

    def test_removed_live_run_commands_are_unknown(self):
        for cmd in ("start", "runs", "input", "history", "delete", "rewind", "native-list", "native-start"):
            rc, out, err = self.w.home.run(cmd)
            self.assertEqual((rc, out["error"]), (1, "Unknown command: %s" % cmd))

    def test_probe_reports_the_daemon(self):
        rc, out, err = self.w.home.run("probe")
        self.assertEqual(rc, 0, err)
        self.assertEqual(out["helperVersion"], "2.0.0")
        self.assertEqual((out["daemon"]["running"], out["daemon"]["proto"], out["daemon"]["version"]), (True, 1, "2.1.287"))
        self.assertIn(out["daemon"]["auth"], ("ok", "needs_login", "unknown"))


class WatchTest(unittest.TestCase):
    def setUp(self):
        self.w = World()

    def tearDown(self):
        self.w.close()

    def test_snapshot_then_changed_and_removed(self):
        p = self.w.home.popen("watch")
        try:
            first = json.loads(p.stdout.readline())
            self.assertIn("snapshot", first)
            self.assertEqual(len(first["snapshot"]), 6)
            time.sleep(1.2)
            self.w.home.job("cccc3333", sessionId=SID_C, cwd=self.w.proj, state="done", tempo="idle",
                            name="renamed job", updatedAt="2026-10-01T11:00:00.000Z")
            line = json.loads(p.stdout.readline())
            self.assertEqual([s["name"] for s in line["changed"]], ["renamed job"])
            self.assertEqual(line["removed"], [])
            os.remove(os.path.join(self.w.home.claude, "projects", self.w.home.h.project_dir_name(self.w.other), SID_D + ".jsonl"))
            line = json.loads(p.stdout.readline())
            self.assertEqual((line["changed"], line["removed"]), ([], [SID_D]))
        finally:
            p.kill()
            p.wait()

    def test_signature_moves_only_on_changes(self):
        h_ = self.w.home.h
        a = h_.watch_signature()
        self.assertEqual(a, h_.watch_signature())
        with open(os.path.join(self.w.home.claude, "projects", h_.project_dir_name(self.w.other), SID_D + ".jsonl"), "a") as f:
            f.write("{}\n")
        self.assertNotEqual(a, h_.watch_signature())


class ScreenDraftTest(unittest.TestCase):
    """Drafts and status cut from the screen stream recorded live (haiku writing a 180-word paragraph)."""

    def replay(self, every=1):
        evs = fixture_jsonl("subscribe_streaming_reply.jsonl")
        t = h.ScreenTracker()
        drafts, statuses = [], []
        n = 0
        for ev in evs:
            if ev["type"] == "snapshot":
                t.feed_tail(ev["streamTail"])
            elif ev["type"] == "stream":
                t.feed(ev["line"])
                n += 1
                if n % every == 0:
                    d, s = t.read()
                    if d and (not drafts or drafts[-1] != d):
                        drafts.append(d)
                    if s:
                        statuses.append(s)
        return drafts, statuses

    def test_drafts_grow_word_by_word_and_unwrap(self):
        drafts, _ = self.replay()
        self.assertGreaterEqual(len(drafts), 4)
        keys = [h.text_key(d) for d in drafts]
        for a, b in zip(keys, keys[1:]):
            self.assertTrue(b.startswith(a), (a[-40:], b[-40:]))
        final = drafts[-1]
        # The screen wrapped "...gravitational pull of the" / "Moon and, ..." at 100 columns: one paragraph again.
        self.assertIn("caused primarily by the gravitational pull of the Moon and, to a lesser extent, the Sun", final)
        para = final.split("\n")[0]
        self.assertGreater(len(para), 600)
        self.assertTrue(final.rstrip().endswith("END-OF-REPLY"))

    def test_final_draft_matches_the_transcript_text(self):
        drafts, _ = self.replay()
        texts = [b["text"] for l in fixture_jsonl("streaming_reply_transcript.jsonl") if l.get("type") == "assistant"
                 for b in l["message"]["content"] if b.get("type") == "text"]
        landed = h.text_key(texts[-1])
        self.assertIn("ENDOFREPLY".lower(), landed)
        self.assertEqual(h.text_key(drafts[-1]), landed)
        para = texts[-1].split("\n")[0]
        self.assertEqual(drafts[-1].split("\n")[0], para)

    def test_status_line(self):
        _, statuses = self.replay()
        self.assertTrue(statuses)
        self.assertEqual(set(s["verb"] for s in statuses), {u"Ideating…"})
        toks = [s["tokens"] for s in statuses if s["tokens"] is not None]
        self.assertEqual(toks, sorted(toks))
        self.assertGreaterEqual(max(toks), 363)
        els = [s["elapsedS"] for s in statuses]
        self.assertEqual(els, sorted(els))

    def test_split_escape_sequences_are_held_back(self):
        t = h.ScreenTracker(rows=10, cols=40)
        t.feed("\x1b[2J\x1b[H\x1b[3")
        t.feed("1mhello\x1b")
        t.feed("[2;1Hworld")
        lines = [l for l in t.screen.lines() if l.strip()]
        self.assertEqual(lines, ["hello", "world"])

    def lines(self, text):
        return text.split("\n")

    def test_spinner_parsing(self):
        self.assertEqual(h.parse_spinner(u"1m 5s · ↑ 1.2k tokens · thinking"), (65, 1200))
        self.assertEqual(h.parse_spinner(u"0s"), (0, None))
        self.assertEqual(h.parse_spinner(u"esc to interrupt"), (None, None))
        d, s = h.screen_draft(self.lines(u"✳ Improvising… (12s · ↓ 340 tokens)"))
        self.assertEqual((d, s), (None, {"verb": u"Improvising…", "elapsedS": 12, "tokens": 340}))
        self.assertEqual(h.screen_draft(self.lines(u"✻ Worked for 1s · done 8:17 PM\n❯")), (None, None))

    def test_tool_blocks_and_one_word_replies(self):
        tool = u"❯ do it\n\n● Bash(ls -la)\n  ⎿  Running…\n\n✶ Herding… (3s)\n"
        self.assertEqual(h.screen_draft(self.lines(tool))[0], None)
        grouped = u"● Read 3 files (ctrl+o to expand)\n\n✶ Herding… (3s)"
        self.assertEqual(h.screen_draft(self.lines(grouped))[0], None)
        word = u"❯ say ready\n\n● READY\n\n✶ Herding… (1s)"
        self.assertEqual(h.screen_draft(self.lines(word))[0], "READY")
        nothing = u"● old reply\n❯ new prompt\n\n✶ Herding… (1s)"
        self.assertEqual(h.screen_draft(self.lines(nothing))[0], None)

    def test_tool_header_still_drawing_is_not_a_draft(self):
        # Recorded live (2.1.288): while the input streams the header is the bare name, then a long command wraps
        # over rows before any "⎿" line; both showed on the phone as reply text for a second.
        bare = u"❯ run it\n\n● Bash\n· Topsy-turvying… (2s · thinking)"
        self.assertEqual(h.screen_draft(self.lines(bare)), (None, {"verb": u"Topsy-turvying…", "elapsedS": 2, "tokens": None}))
        wrapped = (u"❯ run it\n\n● Bash(ls -la /tmp/aaaaaaaaaaaaaaaaaaaa\n  /tmp/bbbbbbbbbbbbbbbbbbbb 2>&1\n  | head -5)\n\n"
                   u"✶ Topsy-turvying… (3s)")
        self.assertEqual(h.screen_draft(self.lines(wrapped))[0], None)
        unclosed = u"❯ run it\n\n● Bash(git commit -m \"$(cat <<'EOF'\n  Fix the thing\n\n✶ Herding… (3s)"
        self.assertEqual(h.screen_draft(self.lines(unclosed))[0], None)
        for name in (u"TodoWrite", u"Web Search(\"kotlin\")", u"context7 - resolve-library-id (MCP)(libraryName: \"x\")"):
            self.assertEqual(h.screen_draft(self.lines(u"● %s\n\n✶ Herding… (1s)" % name))[0], None, name)
        # Replies that merely open with a tool's word still stream.
        for reply in (u"Read the file first, then edit it.", u"Done", u"Bash scripts are fine here.\n  More text."):
            self.assertEqual(h.screen_draft(self.lines(u"● %s\n\n✶ Herding… (1s)" % reply))[0], reply.replace(u"\n  ", u"\n"))

    def test_lists_are_not_joined(self):
        w = "x" * 90
        screen = u"● Steps:\n  - %s\n  - second\n  1. third\n\n✶ Herding… (1s)" % w
        self.assertEqual(h.screen_draft(self.lines(screen))[0], "Steps:\n- %s\n- second\n1. third" % w)

    def test_list_streams_without_a_spinner_while_working(self):
        # Recorded live (2.1.287): while haiku streams a numbered list the CLI drops the spinner line.
        rule = u"─" * 110
        screen = u"\n".join([u" ▐▛███▛█   Claude Code v2.1.287", u"", u"❯ write a numbered list of 40 facts", u"",
                             u"● 1. Ocean covers 71% of Earth's surface.", u"  2. Average ocean depth is 3,688 meters.",
                             u"  3.", u"", u" " * 110, rule, u"❯", rule, u"  ⏸ manual mode on · ← for agents"])
        self.assertEqual(h.screen_draft(self.lines(screen)), (None, None))  # not mid-turn: the reply on screen is old
        d, s = h.screen_draft(self.lines(screen), working=True)
        self.assertEqual(s, None)
        self.assertEqual(d, u"1. Ocean covers 71% of Earth's surface.\n2. Average ocean depth is 3,688 meters.\n3.")
        # Only the prompt echo since the box: nothing streamed yet.
        echo = u"\n".join([u"● old reply", u"❯ new prompt", u"", rule, u"❯", rule])
        self.assertEqual(h.screen_draft(self.lines(echo), working=True), (None, None))
        # A spinner still wins (and gives the status).
        spun = screen.replace(u"  3.\n", u"  3.\n\n✶ Herding… (2s)\n")
        self.assertEqual(h.screen_draft(self.lines(spun), working=True)[1]["verb"], u"Herding…")

    def test_turn_closing_line_is_not_part_of_the_draft(self):
        # Seen on the emulator: the state still said working after the turn ended, so the box anchored the block
        # and the "✻ Crunched for 10s" line came along; that draft never matched the landed reply and stuck.
        rule = u"─" * 110
        screen = u"\n".join([u"❯ write 3 facts", u"", u"● 1. One.", u"  2. Two.", u"  3. Three.", u"",
                             u"✻ Crunched for 10s · done 9:41 PM", u"", rule, u"❯", rule])
        self.assertEqual(h.screen_draft(self.lines(screen), working=True), (u"1. One.\n2. Two.\n3. Three.", None))
        # A reply line that merely says "for 5s" (indented under the ●) is kept.
        keep = screen.replace(u"  3. Three.", u"  3. Wait for 5s then retry.")
        self.assertIn(u"Wait for 5s", h.screen_draft(self.lines(keep), working=True)[0])

    def test_token_counter_above_the_box_is_not_part_of_the_draft(self):
        # Recorded live (2.1.287, a session started like the phone does): mid-list, the right-aligned token
        # counter sits right above the prompt box; it came through as an indented line (a code block on the phone).
        rule = u"─" * 120
        screen = u"\n".join([u"❯ write 30 facts", u"", u"● 1. Stars are hot.", u"  2. Sun is old.", u"  3."]
                            + [u""] * 6 + [u" " * 106 + u"34781 tokens", rule, u"❯", rule])
        self.assertEqual(h.screen_draft(self.lines(screen), working=True), (u"1. Stars are hot.\n2. Sun is old.\n3.", None))
        self.assertFalse(h.right_aligned_chrome(u"  2. Sun is old."))
        self.assertTrue(h.right_aligned_chrome(u" " * 60 + u"1.2k tokens"))

    def test_wide_characters_take_two_cells(self):
        s = h.WideScreen(rows=3, cols=20)
        s.feed(u"你好\x1b[5Gab")
        self.assertEqual(s.lines()[0], u"你好ab")


class TranscriptEventsTest(unittest.TestCase):
    """peer / task / subagent / todos from the follow-events fixture (shapes captured from real transcripts)."""

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h

    def tearDown(self):
        self.home.close()

    def events(self, peer_pid=None):
        tev = self.hh.TranscriptEvents(FOLLOW_SID, "/x/-home-user-proj/%s.jsonl" % FOLLOW_SID)
        out = []
        off = 0
        with open(fixture_path("follow_events_transcript.jsonl"), "rb") as f:
            for raw in f:
                if peer_pid:
                    raw = raw.replace(b"PEERPID", str(peer_pid).encode())
                off += len(raw)
                out.extend(tev.feed(raw.rstrip(b"\n"), off))
        return tev, out

    def test_lines_are_the_transcript_filter(self):
        _tev, evs = self.events()
        lines = [e for e in evs if isinstance(e, tuple) and e[0] == "line"]
        types = [json.loads(e[1])["type"] for e in lines]
        self.assertNotIn("queue-operation", types)
        self.assertNotIn("pr-link", types)
        self.assertEqual(types.count("assistant"), 5)
        offs = [e[2] for e in lines]
        self.assertEqual(offs, sorted(offs))

    def test_queued_prompt_attachment_is_kept(self):
        # A message typed while Claude works reaches it mid-turn as a queued_command attachment, never a user line.
        def att(**a):
            return json.dumps({"type": "attachment", "uuid": "a1", "isSidechain": False,
                               "timestamp": "2026-10-01T16:33:25.717Z", "attachment": a}).encode()
        human = att(type="queued_command", prompt="work with daemon only", commandMode="prompt", origin={"kind": "human"})
        self.assertEqual(json.loads(self.hh.transcript_line_out(human))["attachment"]["prompt"], "work with daemon only")
        self.assertIsNotNone(self.hh.transcript_line_out(att(type="queued_command", prompt=[{"type": "text", "text": "x"}],
                                                             commandMode="prompt")))
        for hidden in (att(type="queued_command", prompt="<task-notification>...", commandMode="task-notification"),
                       att(type="queued_command", prompt="<cross-session-message>...", commandMode="prompt",
                           origin={"kind": "peer"}, isMeta=True),
                       att(type="hook_additional_context", content=["CAVEMAN MODE"])):
            self.assertIsNone(self.hh.transcript_line_out(hidden))

    def test_peer_once_with_sender_session(self):
        self.home.registry(os.getpid(), kind="interactive", sessionId=SID_A)
        _tev, evs = self.events(peer_pid=os.getpid())
        peers = [e for e in evs if isinstance(e, dict) and e["e"] == "peer"]
        self.assertEqual(len(peers), 1)
        p = peers[0]
        self.assertEqual((p["dir"], p["fromName"], p["fromSessionId"]), ("in", "review 3504 reports", SID_A))
        self.assertEqual(p["text"], "Heads-up: I plan to refresh the shared Docker stack.")
        self.assertEqual(p["from"], "uds:/run/user/1000/cc-socks/%d.sock" % os.getpid())
        self.assertEqual(p["at"], self.hh.iso_to_ms("2026-10-01T10:00:13.000Z"))  # the enqueue, not the meta line

    def test_tasks(self):
        _tev, evs = self.events()
        tasks = [e for e in evs if isinstance(e, dict) and e["e"] == "task"]
        self.assertEqual([(t["taskId"], t["kind"], t["status"]) for t in tasks],
                         [("b66dr19lq", "shell", "running"), ("a16a79700d860b0d3", "agent", "running"),
                          ("b66dr19lq", "shell", "completed"), ("a16a79700d860b0d3", "agent", "completed")])
        self.assertEqual(tasks[0]["summary"], "Install deps")
        self.assertTrue(tasks[0]["outputFile"].endswith("/tasks/b66dr19lq.output"))
        self.assertEqual(tasks[2]["summary"], 'Background command "npm ci" completed (exit code 0)')
        self.assertEqual(tasks[0]["toolUseId"], "toolu_bg1")

    def test_todowrite(self):
        _tev, evs = self.events()
        todos = [e for e in evs if isinstance(e, dict) and e["e"] == "todos"]
        self.assertEqual(todos, [{"e": "todos", "listId": FOLLOW_SID, "items": [
            {"id": "1", "subject": "Write tests", "status": "in_progress"}, {"id": "2", "subject": "Ship", "status": "pending"}]}])

    def test_subagent_status(self):
        tev, _evs = self.events()
        tpath = self.home.transcript("/home/user/proj", FOLLOW_SID, [])
        f = self.hh.Follower.__new__(self.hh.Follower)
        f.tev = tev
        f.subagents = {}
        f.metas = fixture_json("follow_events_subagents.json")
        out = io.StringIO()
        f.out = out
        f.update_subagents()
        evs = dict((e["agentId"], e) for e in (json.loads(l) for l in out.getvalue().splitlines()))
        self.assertEqual(evs["a16a79700d860b0d3"], {"e": "subagent", "agentId": "a16a79700d860b0d3", "agentType": "Explore",
                                                    "description": "Product brief", "toolUseId": "toolu_ag1",
                                                    "model": "opus", "background": True, "status": "done"})
        self.assertEqual((evs["a37c067ef6ed1aec1"]["background"], evs["a37c067ef6ed1aec1"]["status"]), (False, "done"))
        self.assertTrue(tpath)

    def test_task_list_dir(self):
        d = os.path.join(self.hh.TASKS_DIR, FOLLOW_SID)
        os.makedirs(d)
        for t in fixture_json("follow_events_tasklist.json"):
            with open(os.path.join(d, t["id"] + ".json"), "w") as fh:
                json.dump(t, fh)
        open(os.path.join(d, ".lock"), "w").close()
        self.assertEqual(self.hh.read_task_list(FOLLOW_SID), [{"id": "1", "subject": "Write tests", "status": "completed"},
                                                              {"id": "2", "subject": "Ship", "status": "in_progress"}])
        self.assertIsNone(self.hh.read_task_list("nope"))


WF_SCRIPT = ("export const meta = {\n  name: 'audit',\n  phases: [{ title: 'Trace', description: 'not this' }],\n"
             "  description: 'Audit the helper',\n}\nphase('Trace')\n")


def workflow_lines(run="wf_9e8310d6-b94", task="w6i6uepat", tuid="toolu_wf"):
    """The Workflow call, its async_launched result and the completion notification, as 2.1.287 writes them."""
    return [
        assistant_line([{"type": "tool_use", "id": tuid, "name": "Workflow", "input": {"script": WF_SCRIPT}}]),
        {"type": "user", "isSidechain": False, "uuid": "u-wf", "timestamp": "2026-10-03T13:44:10.000Z",
         "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tuid,
                                                  "content": "Workflow launched in background. Task ID: %s" % task}]},
         "toolUseResult": {"status": "async_launched", "taskId": task, "taskType": "local_workflow",
                           "workflowName": "audit", "runId": run, "summary": "Audit the helper",
                           "transcriptDir": "/elsewhere/%s" % run, "scriptPath": "/elsewhere/audit-%s.js" % run}},
    ], {"type": "queue-operation", "operation": "enqueue", "timestamp": "2026-10-03T13:46:30.000Z",
        "content": "<task-notification>\n<task-id>%s</task-id>\n<tool-use-id>%s</tool-use-id>\n<output-file>/tmp/x/%s.output"
                   "</output-file>\n<status>completed</status>\n<summary>Dynamic workflow \"Audit the helper\" completed"
                   "</summary>\n<result>[]</result>\n</task-notification>" % (task, tuid, task)}


class WorkflowTest(unittest.TestCase):
    """Workflow (ultracode) runs: the task, its agents from the run dir and journal, their transcripts, the prompt."""

    RUN = "wf_9e8310d6-b94"

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")

    def tearDown(self):
        self.home.close()

    def run_dir(self, tp, agents, journal):
        rd = os.path.join(os.path.dirname(tp), FOLLOW_SID, "subagents", "workflows", self.RUN)
        os.makedirs(rd)
        for aid, phase in agents:
            with open(os.path.join(rd, "agent-%s.meta.json" % aid), "w") as fh:
                json.dump({"agentType": "workflow-subagent", "description": "meta-" + aid, "workflowPhase": phase,
                           "spawnDepth": 1, "requestShape": "foreground"}, fh)
            with open(os.path.join(rd, "agent-%s.jsonl" % aid), "w") as fh:
                fh.write(json.dumps(dict(user_line("work " + aid), isSidechain=True, agentId=aid)) + "\n")
        with open(os.path.join(rd, "journal.jsonl"), "w") as fh:
            for j in journal:
                fh.write(json.dumps(j) + "\n")
        return rd

    def feed(self, tev, lines):
        out = []
        for o in lines:
            out.extend(e for e in tev.feed(json.dumps(o).encode(), 0) if isinstance(e, dict))
        return out

    def test_task_runs_then_completes_with_its_name_and_run(self):
        launch, done = workflow_lines()
        tev = self.hh.TranscriptEvents(FOLLOW_SID, "/x/-p/%s.jsonl" % FOLLOW_SID)
        evs = [e for e in self.feed(tev, launch + [done]) if e["e"] == "task"]
        self.assertEqual([(e["kind"], e["status"]) for e in evs], [("workflow", "running"), ("workflow", "completed")])
        self.assertEqual((evs[0]["summary"], evs[0]["name"], evs[0]["runId"], evs[0]["toolUseId"]),
                         ("Audit the helper", "audit", self.RUN, "toolu_wf"))
        self.assertEqual((evs[1]["name"], evs[1]["runId"]), ("audit", self.RUN))

    def test_notification_alone_is_a_workflow(self):
        _launch, done = workflow_lines()
        tev = self.hh.TranscriptEvents(FOLLOW_SID, None)
        self.assertEqual(self.feed(tev, [done])[0]["kind"], "workflow")

    def test_agents_from_the_run_dir_and_journal(self):
        launch, done = workflow_lines()
        tp = self.home.transcript(self.proj, FOLLOW_SID, launch)
        self.run_dir(tp, [("a1", "Trace"), ("a2", "Verify"), ("a3", None)], [
            {"type": "launched"},
            {"type": "started", "key": "k1", "agentId": "a1", "label": "trace:helper", "phase": "Trace"},
            {"type": "started", "key": "k2", "agentId": "a2", "label": "verify:helper", "phase": "Verify"},
            {"type": "started", "key": "k3", "agentId": "a3"},
            {"type": "result", "key": "k1", "agentId": "a1", "result": {"ok": True}},
        ])
        f = self.hh.Follower.__new__(self.hh.Follower)
        f.tev = self.hh.TranscriptEvents(FOLLOW_SID, tp)
        self.feed(f.tev, launch)
        f.subagents, f.out = {}, io.StringIO()
        f.metas = self.hh.read_subagents(tp, FOLLOW_SID)
        f.update_subagents()
        evs = dict((e["agentId"], e) for e in parse(f.out))
        self.assertEqual(evs["a1"], {"e": "subagent", "agentId": "a1", "agentType": "workflow-subagent",
                                     "description": "trace:helper", "toolUseId": "toolu_wf", "model": "",
                                     "background": False, "status": "done", "workflowRunId": self.RUN, "phase": "Trace"})
        self.assertEqual((evs["a2"]["status"], evs["a2"]["phase"]), ("running", "Verify"))
        self.assertEqual((evs["a3"]["description"], evs["a3"]["status"]), ("meta-a3", "running"))
        self.assertNotIn("phase", evs["a3"])
        # The run ended (stopped mid-way too): every agent still without a result is done.
        self.feed(f.tev, [done])
        f.out = io.StringIO()
        f.update_subagents()
        self.assertEqual(sorted((e["agentId"], e["status"]) for e in parse(f.out)), [("a2", "done"), ("a3", "done")])

    def test_a_resumed_follow_still_knows_its_runs(self):
        launch, done = workflow_lines()
        tp = self.home.transcript(self.proj, FOLLOW_SID, launch + [done])
        self.run_dir(tp, [("a2", "Verify")], [{"type": "started", "key": "k2", "agentId": "a2", "label": "verify:helper",
                                               "phase": "Verify"}])
        self.home.serve()
        out = io.StringIO()
        f = self.hh.Follower(self.hh.SessionRef(FOLLOW_SID), os.path.getsize(tp), out,
                             GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        f.run()
        evs = parse(out)
        self.assertNotIn("line", [e["e"] for e in evs])
        sub = [e for e in evs if e["e"] == "subagent"][0]
        self.assertEqual((sub["toolUseId"], sub["status"]), ("toolu_wf", "done"))

    def test_agent_follow_opens_a_workflow_agent(self):
        launch, _done = workflow_lines()
        tp = self.home.transcript(self.proj, FOLLOW_SID, launch)
        self.run_dir(tp, [("a1", "Trace")], [{"type": "started", "key": "k1", "agentId": "a1", "label": "trace:helper",
                                              "phase": "Trace"}])
        self.home.serve()
        out = io.StringIO()
        self.hh.follow_agent(self.hh.SessionRef(FOLLOW_SID), "a1", 0, out, GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        evs = parse(out)
        self.assertEqual([e["e"] for e in evs], ["line", "subagent", "caughtUp"])
        self.assertEqual((evs[1]["description"], evs[1]["status"], evs[1]["workflowRunId"]), ("trace:helper", "running", self.RUN))

    def test_permission_summary(self):
        ps = self.hh.permission_summary
        self.assertEqual(ps("Workflow", {"script": WF_SCRIPT}, "/"), "Audit the helper")
        self.assertEqual(ps("Workflow", {"script": "export const meta = { name: 'only-name' }"}, "/"), "only-name")
        self.assertEqual(ps("Workflow", {"scriptPath": "/s/audit-wf_1.js", "description": "Re-run"}, "/"), "Re-run")
        self.assertEqual(ps("Workflow", {"scriptPath": "/s/audit-wf_1.js"}, "/"), "audit-wf_1")
        self.assertEqual(ps("Workflow", {}, "/"), "Workflow")


class GoneAfter(object):
    """ReaderGone stand-in: the reader 'goes' once until() is true or the deadline passes."""

    def __init__(self, until, seconds):
        self.until = until
        self.deadline = time.time() + seconds

    def wait(self, s):
        if s:
            time.sleep(min(s, 0.05))
        return self.until() or time.time() > self.deadline


def parse(out):
    return [json.loads(l) for l in out.getvalue().splitlines() if l.strip()]


class FollowTest(unittest.TestCase):
    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")

    def tearDown(self):
        self.home.close()

    def follow_fixture(self):
        hm = self.home
        raw = open(fixture_path("follow_events_transcript.jsonl")).read()
        tp = hm.transcript(self.proj, FOLLOW_SID, [], raw=raw)
        sd = os.path.join(os.path.dirname(tp), FOLLOW_SID, "subagents")
        os.makedirs(sd)
        for aid, meta in fixture_json("follow_events_subagents.json").items():
            with open(os.path.join(sd, "agent-%s.meta.json" % aid), "w") as fh:
                json.dump(meta, fh)
        shutil.copy(fixture_path("follow_events_subagent_a37c067ef6ed1aec1.jsonl"),
                    os.path.join(sd, "agent-a37c067ef6ed1aec1.jsonl"))
        td = os.path.join(self.hh.TASKS_DIR, FOLLOW_SID)
        os.makedirs(td)
        for t in fixture_json("follow_events_tasklist.json"):
            with open(os.path.join(td, t["id"] + ".json"), "w") as fh:
                json.dump(t, fh)
        return tp

    def test_history_then_caught_up_for_a_retired_session(self):
        tp = self.follow_fixture()
        self.home.serve()
        out = io.StringIO()
        ref = self.hh.SessionRef(FOLLOW_SID)
        f = self.hh.Follower(ref, 0, out, GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        f.run()
        evs = parse(out)
        kinds = [e["e"] for e in evs]
        cu = kinds.index("caughtUp")
        self.assertEqual(evs[cu]["offset"], os.path.getsize(tp))
        last_todos = max(i for i in range(cu) if kinds[i] == "todos")
        self.assertEqual(kinds[last_todos + 1:cu + 1], ["state", "caughtUp"])
        # the task list dir (TaskCreate) wins over the TodoWrite seen in the history
        self.assertEqual(evs[last_todos]["items"][1], {"id": "2", "subject": "Ship", "status": "in_progress"})
        st = [e for e in evs if e["e"] == "state"][0]["session"]
        self.assertEqual((st["sessionId"], st["process"], st["name"]), (FOLLOW_SID, "retired", "build and brief"))
        self.assertEqual(sorted(e["agentId"] for e in evs if e["e"] == "subagent"), ["a16a79700d860b0d3", "a37c067ef6ed1aec1"])
        self.assertEqual(kinds.count("peer"), 1)
        self.assertEqual(kinds.count("task"), 4)
        self.assertEqual(kinds.count("line"), 13)  # 7 user + 5 assistant + custom-title
        self.assertNotIn("draft", kinds)

    def test_from_offset_skips_history(self):
        tp = self.follow_fixture()
        self.home.serve()
        size = os.path.getsize(tp)
        out = io.StringIO()
        f = self.hh.Follower(self.hh.SessionRef(FOLLOW_SID), size, out, GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        f.run()
        kinds = [e["e"] for e in parse(out)]
        self.assertNotIn("line", kinds)
        self.assertEqual(parse(out)[-1], {"e": "caughtUp", "offset": size})

    def test_new_lines_after_caught_up(self):
        tp = self.follow_fixture()
        self.home.serve()
        out = io.StringIO()
        done = lambda: '"text":"late line"' in out.getvalue()  # noqa: E731

        def append():
            time.sleep(0.6)
            with open(tp, "a") as fh:
                fh.write(json.dumps(assistant_line("late line")) + "\n")

        threading.Thread(target=append).start()
        f = self.hh.Follower(self.hh.SessionRef(FOLLOW_SID), 0, out, GoneAfter(done, 10))
        f.run()
        evs = parse(out)
        kinds = [e["e"] for e in evs]
        self.assertGreater(len(kinds) - 1 - kinds[::-1].index("line"), kinds.index("caughtUp"))

    def test_agent_follow_keeps_sidechain_lines(self):
        self.follow_fixture()
        self.home.serve()
        out = io.StringIO()
        ref = self.hh.SessionRef(FOLLOW_SID)
        self.hh.follow_agent(ref, "a37c067ef6ed1aec1", 0, out, GoneAfter(lambda: "caughtUp" in out.getvalue(), 10))
        evs = parse(out)
        self.assertEqual([e["e"] for e in evs], ["line", "line", "subagent", "caughtUp"])
        self.assertTrue(all(e["line"]["isSidechain"] for e in evs[:2]))
        # The view learns which subagent it shows (review round 2: it only ever said "Subagent").
        self.assertEqual((evs[2]["agentId"], evs[2]["status"], evs[2]["background"]), ("a37c067ef6ed1aec1", "done", False))
        self.assertTrue(evs[2]["agentType"])
        with self.assertRaises(self.hh.DaemonError):
            self.hh.follow_agent(ref, "nope", 0, out, GoneAfter(lambda: True, 1))

    def test_agent_follow_reports_running_then_done(self):
        tp = self.follow_fixture()
        self.home.serve()
        with open(tp, "a") as fh:
            fh.write(json.dumps(assistant_line([{"type": "tool_use", "id": "toolu_live", "name": "Agent",
                                                 "input": {"description": "Live probe", "subagent_type": "Explore",
                                                           "prompt": "look"}}])) + "\n")
        sd = os.path.join(os.path.dirname(tp), FOLLOW_SID, "subagents")
        with open(os.path.join(sd, "agent-alive01.meta.json"), "w") as fh:
            json.dump({"agentType": "Explore", "description": "Live probe", "toolUseId": "toolu_live"}, fh)
        with open(os.path.join(sd, "agent-alive01.jsonl"), "w") as fh:
            fh.write(json.dumps(dict(user_line("look"), isSidechain=True, agentId="alive01")) + "\n")
        out = io.StringIO()
        done = lambda: '"status":"done"' in out.getvalue()  # noqa: E731

        def finish():
            time.sleep(0.8)
            with open(tp, "a") as fh:
                fh.write(json.dumps({"type": "user", "isSidechain": False, "uuid": "u-res", "timestamp": "2026-10-01T10:00:09.000Z",
                                     "message": {"role": "user", "content": [
                                         {"type": "tool_result", "tool_use_id": "toolu_live", "content": "found it"}]}}) + "\n")

        threading.Thread(target=finish).start()
        self.hh.follow_agent(self.hh.SessionRef(FOLLOW_SID), "alive01", 0, out, GoneAfter(done, 10))
        subs = [e for e in parse(out) if e["e"] == "subagent"]
        self.assertEqual([(e["agentId"], e["status"]) for e in subs], [("alive01", "running"), ("alive01", "done")])
        self.assertEqual((subs[0]["agentType"], subs[0]["description"]), ("Explore", "Live probe"))

    def test_live_stream_drafts_then_the_line_lands(self):
        """A live session: the recorded subscribe stream gives growing drafts and status; the transcript line
        lands; the draft is cleared and not shown again."""
        sid = "d7e55bca-9939-4f52-8d5d-0133b3c9d471"
        hm = self.home
        tlines = fixture_jsonl("streaming_reply_transcript.jsonl")
        last_a = max(i for i, l in enumerate(tlines) if l.get("type") == "assistant")
        tp = hm.transcript(self.proj, sid, tlines[:last_a - 1])
        model = hm.serve()
        hm.job("d7e55bca", sessionId=sid, cwd=self.proj, state="working", tempo="active", name="streamer")
        model.add("d7e55bca", sid, cwd=self.proj, tempo="active", state="working")

        def land():
            with open(tp, "a") as fh:
                for l in tlines[last_a - 1:]:
                    fh.write(json.dumps(l) + "\n")

        evs = []
        for ev in fixture_jsonl("subscribe_streaming_reply.jsonl"):
            evs.append(ev)
            if ev["type"] == "stream":
                evs.append(("sleep", 0.03))
        evs.append(("sleep", 0.5))
        evs.append(land)
        evs.append(("sleep", 1.0))
        evs.append({"type": "state", "patch": {"state": "done", "tempo": "idle"}})
        model.subscribe_events = evs
        out = io.StringIO()
        landed = lambda: '"draftClear"' in out.getvalue()  # noqa: E731
        f = self.hh.Follower(self.hh.SessionRef(sid), 0, out, GoneAfter(landed, 20))
        f.run()
        res = parse(out)
        kinds = [e["e"] for e in res]
        cu = kinds.index("caughtUp")
        drafts = [e["text"] for e in res if e["e"] == "draft"]
        self.assertGreaterEqual(len(drafts), 3, kinds)
        self.assertTrue(all(i > cu for i, k in enumerate(kinds) if k == "draft"))
        keys = [self.hh.text_key(d) for d in drafts]
        for a, b in zip(keys, keys[1:]):
            self.assertTrue(b.startswith(a))
        self.assertIn("END-OF-REPLY", drafts[-1])
        statuses = [e for e in res if e["e"] == "status" and "verb" in e]
        self.assertTrue(statuses)
        self.assertEqual(statuses[0]["verb"], u"Ideating…")
        final_line = max(i for i, e in enumerate(res) if e["e"] == "line" and e["line"]["type"] == "assistant")
        clear = kinds.index("draftClear")
        self.assertGreater(clear, final_line)
        self.assertNotIn("draft", kinds[clear:])

    def test_follow_command_takes_session_ids_only(self):
        self.follow_fixture()
        self.home.serve()
        rc, out, err = self.home.run("follow", "r1abcdefg", "0")
        self.assertEqual((rc, out["code"]), (1, "ENOSESSION"))
        rc, out, err = self.home.run("follow", "12345678")
        self.assertEqual((rc, out["code"]), (1, "ENOSESSION"))
        p = self.home.popen("follow", FOLLOW_SID[:8], "--from", "0")
        try:
            lines = []
            while True:
                l = json.loads(p.stdout.readline())
                lines.append(l)
                if l["e"] == "caughtUp":
                    break
            self.assertEqual(sum(1 for l in lines if l["e"] == "line"), 13)
        finally:
            p.kill()
            p.wait()


class WritesTest(unittest.TestCase):
    def setUp(self):
        self.w = World()
        self.hm = self.w.home
        self.model = self.w.model

    def tearDown(self):
        self.w.close()

    def test_send_to_a_live_session_replies(self):
        rc, out, err = self.hm.run("send", SID_A, stdin=json.dumps({"text": "more please"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": False}), err)
        self.assertEqual(self.model.replies, [("aaaa1111", "more please")])
        self.assertEqual(self.model.dispatched, [])

    def test_send_wakes_a_retired_session_under_the_same_short(self):
        rc, out, err = self.hm.run("send", "cccc3333", stdin=json.dumps({"text": "again"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        d = self.model.dispatched[0]
        self.assertEqual((d["short"], d["sessionId"], d["source"]), ("cccc3333", SID_C, "fleet"))
        self.assertEqual(d["launch"]["mode"], "resume")
        self.assertEqual((d["launch"]["sessionId"], d["launch"]["fork"]), (SID_C, False))
        self.assertTrue(d["launch"]["transcriptPath"].endswith(SID_C + ".jsonl"))
        # The flags are replayed, except --model: the transcript's last model replaces it.
        self.assertEqual(d["launch"]["flagArgs"],
                         ["--permission-mode", "acceptEdits", "--model", "claude-haiku-4-5-20251001"])
        self.assertEqual(d["cwd"], self.w.proj)
        self.assertEqual(self.model.replies, [("cccc3333", "again")])

    def test_send_wakes_a_transcript_only_session(self):
        rc, out, err = self.hm.run("send", SID_D, stdin=json.dumps({"text": "hi", "images": []}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        d = self.model.dispatched[0]
        self.assertEqual((d["short"], d["launch"]["flagArgs"], d["cwd"]),
                         (SID_D[:8], ["--permission-mode", "plan", "--model", "claude-opus-5-5"], self.w.other))

    def test_resume_flags_drop_launch_only_arguments(self):
        h_ = self.hm.h
        self.assertEqual(h_.resume_flags(["--model", "opus", "-n", "name", "-w", "wt", "--permission-mode=auto",
                                          "--dangerously-skip-permissions", "the first prompt"]),
                         ["--model", "opus", "--permission-mode=auto", "--dangerously-skip-permissions"])
        self.assertEqual(h_.resume_flags(None), [])

    def test_wake_flags_take_the_model_from_the_transcript(self):
        # Seen live: launched --model haiku, the CLI later rewrote respawnFlags to "--model opus" (the settings
        # default) while replies still came from haiku; replaying that flag would switch the session to opus.
        h_ = self.hm.h
        tp = self.hm.path("t.jsonl")
        with open(tp, "w") as fh:
            fh.write(json.dumps({"type": "assistant", "message": {"model": "claude-haiku-4-5-20251001",
                                                                  "content": [{"type": "text", "text": "hi"}]}}) + "\n")
            fh.write(json.dumps({"type": "assistant", "message": {"model": "<synthetic>", "content": []}}) + "\n")
        self.assertEqual(h_.wake_flags(["--permission-mode", "default", "--model", "opus"], tp),
                         ["--permission-mode", "default", "--model", "claude-haiku-4-5-20251001"])
        self.assertEqual(h_.wake_flags(["--model=opus", "--effort", "high"], tp),
                         ["--effort", "high", "--model", "claude-haiku-4-5-20251001"])
        empty = self.hm.path("e.jsonl")
        open(empty, "w").close()
        self.assertEqual(h_.wake_flags(["--model", "opus"], empty), ["--model", "opus"])
        self.assertEqual(h_.wake_flags(["--model", "opus"], None), ["--model", "opus"])

    def test_wake_keeps_the_sessions_mode_when_the_flags_lost_it(self):
        # Seen live: respawnFlags were [] right after a launch with --permission-mode default; a wake without the
        # flag started in the settings defaultMode ("auto"). The transcript's last mode fills it in.
        h_ = self.hm.h
        tp = self.hm.path("m.jsonl")
        with open(tp, "w") as fh:
            fh.write(json.dumps({"type": "permission-mode", "permissionMode": "acceptEdits", "sessionId": "x"}) + "\n")
            fh.write(json.dumps({"parentUuid": None, "type": "user", "permissionMode": "plan",  # compact, like the CLI
                                 "message": {"role": "user", "content": "hi"}}, separators=(",", ":")) + "\n")
        self.assertEqual(h_.wake_flags([], tp), ["--permission-mode", "plan"])
        # The flag, when there, wins (Shift+Tab rewrites it at once; the transcript lags).
        self.assertEqual(h_.wake_flags(["--permission-mode", "default"], tp), ["--permission-mode", "default"])

    def test_first_turn_model_comes_from_the_daemon_roster(self):
        # respawnFlags can be [] during the first turn and no reply has landed yet: the roster's launch args tell.
        hm = self.hm
        roster = {"proto": 1, "workers": {"aaaa1111": {"sessionId": SID_A, "dispatch": {
            "short": "aaaa1111", "sessionId": SID_A,
            "launch": {"mode": "prompt", "args": ["--session-id", SID_A, "--model", "haiku", "--", "go"]}}}}}
        os.makedirs(hm.path(".claude", "daemon"), exist_ok=True)
        with open(hm.path(".claude", "daemon", "roster.json"), "w") as fh:
            json.dump(roster, fh)
        self.assertEqual(hm.h.roster_launch_flags(), {SID_A: ["--session-id", SID_A, "--model", "haiku"]})

    def test_new_passes_the_default_mode_explicitly(self):
        # Without the flag the CLI starts in the user's settings defaultMode ("auto" on the test machine), not Ask.
        d = self.hm.folder("plain", trusted=True)
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": d, "prompt": "hi", "permissionMode": "default"}))
        self.assertEqual(rc, 0, err)
        args = self.model.dispatched[0]["launch"]["args"]
        self.assertEqual(args[2:4], ["--permission-mode", "default"])
        self.assertEqual(out["permissionMode"], "default")

    def test_images_are_appended_like_a_dropped_file(self):
        img = self.hm.path("up", "shot 1.png")
        os.makedirs(os.path.dirname(img))
        open(img, "wb").close()
        rc, out, err = self.hm.run("send", SID_A, stdin=json.dumps({"text": "look", "images": [img]}))
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.model.replies, [("aaaa1111", "look " + img.replace(" ", "\\ "))])
        rc, out, err = self.hm.run("send", SID_A, stdin=json.dumps({"text": "x", "images": ["/no/such.png"]}))
        self.assertEqual(rc, 1)

    def test_terminal_held_session_is_eheld(self):
        self.w.terminal()
        for cmd, body in (("send", {"text": "x"}), ("key", {"keys": ["esc"]}), ("stop", None), ("rm", None),
                          ("interrupt", None)):
            rc, out, err = self.hm.run(cmd, SID_E, stdin=json.dumps(body) if body else "")
            self.assertEqual((rc, out.get("code")), (1, "EHELD"), (cmd, out, err))
        self.assertEqual(self.model.replies, [])

    def test_key_types_raw_bytes_through_attach(self):
        keys = ["shift-tab", "esc", {"text": "/model haiku"}, "enter", "1", "up", "space"]
        rc, out, err = self.hm.run("key", SID_A, stdin=json.dumps({"keys": keys}))
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertEqual(bytes(self.model.keys), b"\x1b[Z\x1b/model haiku\r1\x1b[A ")
        rc, out, err = self.hm.run("key", SID_A, stdin=json.dumps({"keys": ["f13"]}))
        self.assertEqual(rc, 1)

    def test_key_on_a_retired_session_is_enosession(self):
        rc, out, err = self.hm.run("key", SID_C, stdin=json.dumps({"keys": ["esc"]}))
        self.assertEqual((rc, out["code"]), (1, "ENOSESSION"))

    def test_answer_permission(self):
        hm = self.hm
        hm.transcript(self.w.proj, SID_B, [user_line("rm", cwd=self.w.proj), assistant_line(
            [{"type": "tool_use", "id": "toolu_p1", "name": "Bash", "input": {"command": "rm -rf build"}}])])
        hm.job("bbbb2222", sessionId=SID_B, cwd=self.w.proj, state="working", tempo="blocked")
        hm.registry(os.getpid(), kind="bg", status="waiting", waitingFor="permission prompt", sessionId=SID_B,
                    jobId="bbbb2222")

        def unblock(keys):
            self.model.records["bbbb2222"]["tempo"] = "active"
            hm.job("bbbb2222", sessionId=SID_B, cwd=self.w.proj, state="working", tempo="active")
            os.remove(os.path.join(hm.claude, "sessions", "%d.json" % os.getpid()))

        self.model.on_keys = unblock
        rc, out, err = hm.run("answer", SID_B, stdin=json.dumps({"decision": "deny", "message": "use make clean"}))
        self.assertEqual(rc, 0, err)
        self.assertEqual(bytes(self.model.keys), b"\x1b")
        self.assertEqual(self.model.replies, [("bbbb2222", "use make clean")])
        self.assertEqual((out["sessionId"], out["state"]), (SID_B, "working"))

    def test_answer_allow_always(self):
        # The prompt's own "always" row (here option 2 of a live-recorded Bash prompt), read off the screen.
        self.model.subscribe_events = fixture_jsonl("subscribe_perm_bash.jsonl")
        self.model.on_keys = lambda k: self.model.records["bbbb2222"].update(tempo="active")
        rc, out, err = self.hm.run("answer", SID_B, stdin=json.dumps({"decision": "allow_always"}))
        self.assertEqual(rc, 0, err)
        self.assertEqual(bytes(self.model.keys), b"2")

    def test_ask_types_the_choice_on_the_question_screen(self):
        self.model.attach_screen = (u"\x1b[2J\x1b[HWhich color?\r\n❯ 1. Red\r\n  2. Blue\r\n  3. Type something.\r\n"
                                    ).encode("utf-8")

        def unblock(keys):
            self.model.records["bbbb2222"]["tempo"] = "active"
            st = self.hm.h.job_state("bbbb2222")
            self.hm.job("bbbb2222", **dict(st, tempo="active"))
            p = os.path.join(self.hm.claude, "sessions", "%d.json" % os.getpid())
            if os.path.exists(p):
                os.remove(p)

        self.model.on_keys = unblock
        rc, out, err = self.hm.run("ask", SID_B, stdin=json.dumps({"answers": [{"choices": [1], "other": None}]}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(bytes(self.model.keys), b"2")
        self.assertEqual(out["state"], "working")

    def test_question_plan(self):
        h_ = self.hm.h
        qs = [{"question": "A?", "options": [{"label": "x"}, {"label": "y"}], "multiSelect": False},
              {"question": "B?", "options": [{"label": "p"}, {"label": "q"}, {"label": "r"}], "multiSelect": True}]
        self.assertEqual(h_.question_plan(qs, [{"choices": [1]}, {"choices": [2, 0, 2]}]),
                         [("single", 2, 1), ("multi", 3, [0, 2])])
        self.assertEqual(h_.question_plan(qs[:1], [{"choices": [], "other": "z\nz"}]), [("other", 2, "z z")])
        # a typed answer on a multi-select question, alone or with choices
        self.assertEqual(h_.question_plan(qs, [{"choices": [0]}, {"choices": [], "other": "kiwi"}]),
                         [("single", 2, 0), ("multi_other", 3, ([], "kiwi"))])
        self.assertEqual(h_.question_plan(qs[1:], [{"choices": [2, 0], "other": " s "}]), [("multi_other", 3, ([0, 2], "s"))])
        with self.assertRaises(h_.HelperError):
            h_.question_plan(qs, [{"choices": [0]}])

    def test_interrupt_presses_esc(self):
        rc, out, err = self.hm.run("interrupt", SID_A)
        self.assertEqual(rc, 0, err)
        self.assertEqual(bytes(self.model.keys), b"\x1b")
        self.assertEqual(out["sessionId"], SID_A)

    def test_stop_kills_without_evicting(self):
        rc, out, err = self.hm.run("stop", SID_A)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertEqual(self.model.kills, [("aaaa1111", False)])
        s = self.hm.h.build_sessions(self.hm.h.Sources(), self.hm.h.TranscriptFacts())
        self.assertEqual([x["process"] for x in s if x["sessionId"] == SID_A], ["retired"])

    def test_stop_rejects_old_run_ids(self):
        rc, out, err = self.hm.run("stop", "r1abcdefg")
        self.assertEqual((rc, out["error"], out["code"]), (1, "Invalid session id.", "ENOSESSION"))
        self.assertEqual(self.model.kills, [])

    def test_rm_evicts_deletes_the_job_and_hides_the_session(self):
        rc, out, err = self.hm.run("rm", SID_C)
        self.assertEqual((rc, out), (0, {"ok": True}), err)
        self.assertEqual(self.model.kills, [("cccc3333", True)])
        self.assertFalse(os.path.exists(os.path.join(self.hm.claude, "jobs", "cccc3333")))
        self.assertTrue(os.path.exists(os.path.join(self.hm.claude, "projects", self.hm.h.project_dir_name(self.w.proj),
                                                    SID_C + ".jsonl")))  # the conversation itself is kept
        rc, out, err = self.hm.run("sessions")
        self.assertNotIn(SID_C, [s["sessionId"] for s in out["sessions"]])

    def test_new_needs_trust(self):
        d = self.hm.folder("untrusted", trusted=False)
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": d, "prompt": "hi"}))
        self.assertEqual((rc, out["code"]), (1, "EUNTRUSTED"))
        self.assertEqual(self.model.dispatched, [])

    def test_new_dispatches_a_prompt_launch(self):
        d = self.hm.folder("fresh", trusted=False)
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": d, "prompt": "-start here", "model": "haiku",
                                                            "permissionMode": "acceptEdits", "trust": True}))
        self.assertEqual(rc, 0, err)
        spec = self.model.dispatched[0]
        sid = spec["sessionId"]
        self.assertTrue(self.hm.h.UUID_RE.match(sid))
        self.assertEqual(spec["short"], sid[:8])
        self.assertEqual(spec["launch"], {"mode": "prompt", "args": ["--session-id", sid, "--model", "haiku",
                                                                     "--permission-mode", "acceptEdits", "--", "-start here"]})
        self.assertEqual((spec["source"], spec["cwd"], spec["seed"]["intent"]), ("fleet", d, "-start here"))
        self.assertEqual((out["sessionId"], out["process"], out["heldBy"], out["state"]), (sid, "live", "daemon", "working"))
        self.assertTrue(self.hm.h.read_json(self.hm.path(".claude.json"))["projects"][d]["hasTrustDialogAccepted"])

    def test_new_with_images_pastes_the_first_message(self):
        img = self.hm.path("img.png")
        open(img, "wb").close()
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": self.w.proj, "prompt": "what is this", "images": [img]}))
        self.assertEqual(rc, 0, err)
        spec = self.model.dispatched[0]
        self.assertEqual(spec["launch"]["args"], ["--session-id", spec["sessionId"]])
        self.assertEqual(self.model.replies, [(spec["short"], "what is this " + img)])

    def test_new_rejects_bad_settings(self):
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": self.w.proj, "prompt": "x", "permissionMode": "yolo"}))
        self.assertEqual(rc, 1)
        rc, out, err = self.hm.run("new", stdin=json.dumps({"cwd": self.w.proj, "prompt": "x", "model": "a b"}))
        self.assertEqual(rc, 1)

    def test_no_daemon_and_no_claude_is_enodaemon(self):
        self.hm.server.close()
        env_claude = self.hm.path("noclaude")
        rc, out, err = self.hm.run("--claude", "/nonexistent/claude", "send", SID_C, stdin=json.dumps({"text": "x"}))
        self.assertEqual(rc, 1)
        self.assertEqual(out.get("code"), "ENODAEMON", out)
        self.assertFalse(os.path.exists(env_claude))


FAKE_CLAUDE = r'''#!%s
# Stands in for `claude daemon run ...`: records its argv, then answers ping on the socket the helper expects.
import json, os, socket, sys, time
with open(os.environ["TETHER_FAKE_ARGS"], "w") as f:
    json.dump(sys.argv[1:], f)
path = os.environ["TETHER_FAKE_SOCK"]
os.makedirs(os.path.dirname(os.path.dirname(path)), mode=0o700, exist_ok=True)  # private, as the daemon makes them
os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
s = socket.socket(socket.AF_UNIX)
s.bind(path)
s.listen(4)
s.settimeout(15)
end = time.time() + 15
while time.time() < end:
    try:
        c, _ = s.accept()
    except OSError:
        break
    c.recv(65536)
    c.sendall(b'{"ok":true,"op":"ping","version":"2.1.287","proto":1}\n')
    c.close()
'''


def cli_settings(obj):
    """settings.json the way Claude Code writes it."""
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


class ModelGuardTest(unittest.TestCase):
    """The model chip changes the session's model only: Claude Code 2.1.287 also saves `/model X` as the machine
    default ("model" in ~/.claude/settings.json); the helper puts the key back exactly as it was."""

    ORIGINAL = {"permissions": {"defaultMode": "auto"}, "model": "opus", "hooks": {"Stop": []}, "theme": "dark ✓"}

    def setUp(self):
        self.w = World()
        self.hm = self.w.home
        self.h = self.hm.h
        self.path = self.h.claude_settings_path()

    def tearDown(self):
        self.w.close()

    def write(self, text):
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(text)

    def read(self):
        with open(self.path, "rb") as f:
            return f.read()

    def cli_saves(self, model, **extra):
        obj = json.loads(self.read()) if os.path.exists(self.path) else {}
        obj["model"] = model
        obj.update(extra)
        self.write(cli_settings(obj))

    def test_restores_the_original_bytes(self):
        self.write(cli_settings(self.ORIGINAL))
        before = self.read()
        token, _off = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("haiku")
        self.assertTrue(self.h.model_guard_restore())
        self.assertEqual(self.read(), before)
        self.assertFalse(self.h.model_guard_restore())  # nothing left to do
        self.h.model_guard_release(token)
        self.assertFalse(os.path.exists(self.h.MODEL_GUARD_PATH))

    def test_an_absent_key_is_removed_again(self):
        orig = dict(self.ORIGINAL)
        del orig["model"]
        self.write(cli_settings(orig))
        before = self.read()
        token, _off = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("haiku")
        self.h.model_guard_restore()
        self.assertEqual(self.read(), before)
        self.h.model_guard_release(token)

    def test_a_settings_file_created_for_the_model_is_removed(self):
        token, _off = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("haiku")
        self.h.model_guard_restore()
        self.assertFalse(os.path.exists(self.path))
        self.h.model_guard_release(token)

    def test_back_to_back_switches_restore_the_value_from_before_the_first(self):
        self.write(cli_settings(self.ORIGINAL))
        before = self.read()
        t1, _ = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("haiku")
        t2, _ = self.h.model_guard_begin(SID_B, None, "sonnet")
        self.cli_saves("sonnet")
        self.h.model_guard_release(t1)
        self.h.model_guard_restore()
        self.assertEqual(self.read(), before)
        self.h.model_guard_release(t2)

    def test_only_the_model_key_is_touched(self):
        self.write(cli_settings(self.ORIGINAL))
        token, _ = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("haiku", effortLevel="high")  # someone else changed another key meanwhile
        self.h.model_guard_restore()
        obj = json.loads(self.read())
        self.assertEqual(obj["model"], "opus")
        self.assertEqual(obj["effortLevel"], "high")
        self.assertEqual(list(obj.keys()), ["permissions", "model", "hooks", "theme", "effortLevel"])
        self.h.model_guard_release(token)

    def test_send_model_restores_once_the_session_applied_it(self):
        self.write(cli_settings(self.ORIGINAL))
        before = self.read()
        tpath = self.h.find_transcript(SID_A)

        def worker(short, text):  # what Claude Code does with `/model haiku`
            self.cli_saves("haiku")
            with open(tpath, "a") as f:
                for t in ("<command-name>/model</command-name>\n<command-args>haiku</command-args>",
                          "<local-command-stdout>Set model to Haiku 4.5 and saved as your default for new sessions"
                          "</local-command-stdout>"):
                    f.write(json.dumps(user_line(t), separators=(",", ":")) + "\n")
        self.w.model.on_reply = worker
        self.hm.extra_env["TETHER_MODEL_GUARD_INLINE"] = "8"
        t0 = time.time()
        rc, out, err = self.hm.run("send", SID_A, stdin=json.dumps({"text": "/model haiku"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": False}), err)
        self.assertLess(time.time() - t0, 7, "the guard stops once the transcript shows the switch")
        self.assertEqual(self.read(), before)
        self.assertFalse(os.path.exists(self.h.MODEL_GUARD_PATH))
        # Seen on the emulator: the phone then said "Model set to Haiku 4.5 and saved as your default for new
        # sessions" although the guard had undone the save. The guarded output reads "for this session only".
        with open(tpath, "rb") as f:
            raw = [l for l in f.read().splitlines() if b"local-command-stdout" in l][-1]
        line = json.loads(self.h.transcript_line_out(raw))
        self.assertEqual(line["message"]["content"],
                         "<local-command-stdout>Set model to Haiku 4.5 for this session only</local-command-stdout>")

    def test_unguarded_model_output_is_left_alone(self):
        # `/model` typed in a terminal really saves the default: its output stays as Claude Code wrote it.
        text = "<local-command-stdout>Set model to Haiku 4.5 and saved as your default for new sessions</local-command-stdout>"
        raw = json.dumps(user_line(text, uuid="u-terminal", sessionId=SID_C), separators=(",", ":")).encode()
        self.assertEqual(json.loads(self.h.transcript_line_out(raw))["message"]["content"], text)
        # ...but while a guard for that session runs (the follow stream sees the line before the guard is done),
        # it is the phone's switch.
        token, _ = self.h.model_guard_begin(SID_C, None, "haiku")
        try:
            self.assertIn("for this session only", self.h.transcript_line_out(raw))
        finally:
            self.h.model_guard_release(token)

    def test_a_default_the_user_changed_meanwhile_is_kept(self):
        # Regression: a guard whose switch never landed ("Switch model?" answered "No") kept writing the old value
        # back for 10 minutes and undid the user's own change of the default.
        self.write(cli_settings(self.ORIGINAL))
        token, _ = self.h.model_guard_begin(SID_A, None, "haiku")
        self.cli_saves("sonnet")  # the user, not the guarded switch
        self.assertFalse(self.h.model_guard_restore())
        self.assertEqual(json.loads(self.read())["model"], "sonnet")
        self.h.model_guard_release(token)

    def test_a_refused_switch_leaves_the_file_alone(self):
        self.write(cli_settings(self.ORIGINAL))
        before = self.read()
        token, _ = self.h.model_guard_begin(SID_A, None, "haiku")
        self.assertFalse(self.h.model_guard_restore())
        self.assertEqual(self.read(), before)
        self.h.model_guard_release(token)

    def test_plain_messages_are_not_guarded(self):
        self.write(cli_settings(self.ORIGINAL))
        self.w.model.on_reply = lambda short, text: self.cli_saves("haiku")
        rc, out, err = self.hm.run("send", SID_A, stdin=json.dumps({"text": "/model is a nice command, explain it"}))
        self.assertEqual(rc, 0, err)
        self.assertEqual(json.loads(self.read())["model"], "haiku")
        self.assertFalse(os.path.exists(self.h.MODEL_GUARD_PATH))


class EnsureDaemonTest(unittest.TestCase):
    """Decision 1's one exception: when the daemon is down, start it the way the CLI does."""

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.claude = self.home.path("bin", "claude")
        os.makedirs(os.path.dirname(self.claude))
        with open(self.claude, "w") as f:
            f.write(FAKE_CLAUDE % sys.executable)
        os.chmod(self.claude, 0o755)
        self.args = self.home.path("args.json")
        sock = os.path.join(self.home.run_root, self.hh.daemon_socket_hash(self.home.claude), "control.sock")
        self._env = dict(os.environ)
        os.environ["TETHER_FAKE_ARGS"] = self.args
        os.environ["TETHER_FAKE_SOCK"] = sock

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)
        self.home.close()

    def test_starts_a_transient_daemon_and_waits_for_it(self):
        r = self.hh.ensure_daemon({"claude": self.claude})
        self.assertEqual((r["ok"], r["proto"]), (True, 1))
        argv = json.load(open(self.args))
        self.assertEqual(argv[:5], ["daemon", "run", "--origin", "transient", "--spawned-by"])
        self.assertEqual(json.loads(argv[5])["label"], "tether")

    def test_running_daemon_is_left_alone(self):
        self.home.serve()
        self.assertEqual(self.hh.ensure_daemon({"claude": self.claude})["version"], "2.1.287")
        self.assertFalse(os.path.exists(self.args))


class CommandsTest(unittest.TestCase):
    """`commands` reads the slash commands from disk (decision 1: it never runs `claude -p`)."""

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")

    def tearDown(self):
        self.home.close()

    def write(self, path, text):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)

    def test_lists_project_user_plugin_and_builtin_commands(self):
        c = self.home.claude
        self.write(os.path.join(self.proj, ".claude", "commands", "deploy.md"),
                   "---\ndescription: Ship it\nargument-hint: <env>\n---\nDeploy $ARGUMENTS\n")
        self.write(os.path.join(self.proj, ".claude", "commands", "db", "reset.md"), "Reset the database\n")
        self.write(os.path.join(c, "skills", "triage", "SKILL.md"),
                   "---\nname: triage\ndescription: >\n  Sort the bug\n  reports\n---\nbody\n")
        self.write(os.path.join(c, "commands", "notes.md"), "---\ndescription: \"Take notes\"\n---\n")
        os.symlink(os.path.join(c, "nowhere.md"), os.path.join(c, "commands", "dangling.md"))
        plug = self.home.path("plugcache", "ralph", "1.0")
        self.write(os.path.join(plug, "commands", "loop-it.md"), "---\ndescription: Start a loop\n---\n")
        self.write(os.path.join(plug, "skills", "helper", "SKILL.md"), "---\nname: helper\ndescription: Helps\n---\n")
        self.write(os.path.join(c, "plugins", "installed_plugins.json"), json.dumps({"version": 2, "plugins": {
            "ralph@market": [{"scope": "user", "installPath": plug}],
            "off@market": [{"scope": "user", "installPath": plug}]}}))
        self.write(os.path.join(c, "settings.json"), json.dumps({"enabledPlugins": {"ralph@market": True, "off@market": False}}))
        rc, out, err = self.home.run("commands", "--cwd", self.proj)
        self.assertEqual(rc, 0, err)
        by = dict((x["name"], x) for x in out["commands"])
        self.assertEqual(by["deploy"], {"name": "deploy", "description": "Ship it", "argumentHint": "<env>"})
        self.assertEqual(by["db:reset"]["description"], "Reset the database")
        self.assertEqual(by["triage"]["description"], "Sort the bug reports")
        self.assertEqual(by["notes"]["description"], "Take notes")
        self.assertEqual(by["ralph:loop-it"]["description"], "(ralph) Start a loop")
        self.assertIn("ralph:helper", by)
        self.assertNotIn("off:loop-it", by)
        self.assertNotIn("dangling", by)
        for b in ("clear", "compact", "model", "context"):
            self.assertIn(b, by)
        self.assertEqual(len(by), len(out["commands"]))  # no duplicates

    def test_never_runs_claude(self):
        claude = self.home.path("bin", "claude")
        marker = self.home.path("ran")
        self.write(claude, "#!/bin/sh\ntouch %s\n" % marker)
        os.chmod(claude, 0o755)
        rc, out, err = self.home.run("--claude", claude, "commands", "--cwd", self.proj)
        self.assertEqual(rc, 0, err)
        self.assertTrue(out["commands"])
        self.assertFalse(os.path.exists(marker))


class StaleDraftTest(unittest.TestCase):
    """The screen still showing a message that landed is not a draft; a new reply that opens the same way is."""

    def follower(self, *landed):
        f = h.Follower.__new__(h.Follower)
        f.tev = h.TranscriptEvents("x")
        for t in landed:
            f.tev.landed.append(h.text_key(t))
        return f

    def test_whole_or_scrolled_message_is_stale(self):
        f = self.follower("Let me check the tests.\n\nAll **done**.")
        self.assertTrue(f.landed_draft("Let me check the tests. All done."))
        self.assertTrue(f.landed_draft("All done."))  # its top scrolled off the screen

    def test_a_new_reply_opening_like_an_earlier_one_streams(self):
        f = self.follower("Let me check the tests.", "Everything is done and the build passed.")
        self.assertFalse(f.landed_draft("Let me"))
        self.assertFalse(f.landed_draft("Let me look at"))
        self.assertFalse(f.landed_draft("Done"))


class KeyMapTest(unittest.TestCase):
    def test_key_chunks(self):
        self.assertEqual(h.key_chunks(["shift-tab", "esc", "enter", "up", "down", "left", "right", "tab", "space", "9",
                                       {"text": u"/model hé"}]),
                         [b"\x1b[Z", b"\x1b", b"\r", b"\x1b[A", b"\x1b[B", b"\x1b[D", b"\x1b[C", b"\t", b" ", b"9",
                          u"/model hé".encode("utf-8")])
        for bad in ([], None, ["0"], ["10"], [{"text": 5}], ["ctrl-c"]):
            with self.assertRaises(h.HelperError):
                h.key_chunks(bad)

    def test_session_arg_shapes(self):
        self.assertTrue(h.is_session_arg("7b9f8c4c"))
        self.assertTrue(h.is_session_arg("7b9f8c4c-6ac7-4d5a-9111-003abd24390e"))
        self.assertFalse(h.is_session_arg("r1abcdefg12345"))
        self.assertFalse(h.is_session_arg("term-123"))


if __name__ == "__main__":
    unittest.main()
