"""Blocking dialogs (decision 5), daemon blips, startedAt across wakes, stale answers and error codes.
Screens are subscribe streams recorded live from 2.1.287 (throwaway haiku sessions): the project MCP-servers
dialog (cursor on a server, and moved down to "Enable selected") and the "Switch model?" confirmation.
Run: python3 -m unittest discover -s tools/helper_tests"""

import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import attach_bytes, fixture_jsonl, fixture_path, load_helper  # noqa
from fake_home import FakeHome  # noqa

h = load_helper()

SID = "9d1a0c4b-1111-4222-8333-944455556666"
SHORT = SID[:8]


def screen_of(name):
    t = h.ScreenTracker()
    if name.endswith(".bin"):
        with open(fixture_path(name), "rb") as f:
            t.feed(f.read().decode("utf-8", "replace"))
        return t.screen.lines()
    for ev in fixture_jsonl(name):
        if ev.get("type") == "snapshot":
            t.feed_tail(ev.get("streamTail") or [])
        elif ev.get("type") == "stream":
            t.feed(ev.get("line") or "")
    return t.screen.lines()


def user_line(text, ts="2026-10-01T10:00:00.000Z", cwd="/x"):
    return {"type": "user", "isSidechain": False, "message": {"role": "user", "content": text}, "uuid": "u-" + text[:8],
            "timestamp": ts, "cwd": cwd}


def assistant_line(blocks, ts="2026-10-01T10:00:01.000Z"):
    if isinstance(blocks, str):
        blocks = [{"type": "text", "text": blocks}]
    return {"type": "assistant", "isSidechain": False, "uuid": "a-%d" % id(blocks), "timestamp": ts,
            "message": {"model": "claude-haiku-4-5-20251001", "role": "assistant", "content": blocks}}


class CutDialogTest(unittest.TestCase):
    def test_mcp_servers_checklist(self):
        d = h.cut_dialog(screen_of("subscribe_dialog_mcp_dialog.jsonl"))
        self.assertEqual((d["kind"], d["dialog"], d["title"]), ("dialog", "mcp_servers", "4 new MCP servers found in this project"))
        self.assertTrue(d["body"].startswith("Select any you wish to enable.\n\nMCP servers may execute code"))
        self.assertIn("Learn more in the MCP documentation.", d["body"])  # soft-wrapped line joined again
        self.assertEqual([(o["label"], o["checked"]) for o in d["options"]],
                         [("ghidra", True), ("mempalace", True), ("tether-dummy-one", True), ("tether-dummy-two", True)])
        self.assertTrue(all("key" not in o for o in d["options"]))  # rows are toggled with Space, not a key of their own
        self.assertEqual(d["keys"], ["up", "down", "space", "enter", "esc"])

    def test_mcp_dialog_with_the_cursor_on_enable_selected(self):
        d = h.cut_dialog(screen_of("subscribe_dialog_nav_down10.jsonl"))
        self.assertEqual(d["dialog"], "mcp_servers")
        self.assertEqual(len(d["options"]), 4)

    def test_older_mcp_recordings(self):
        for name in ("subscribe_new_session_mcp_dialog.jsonl", "attach_raw_mcp_dialog.bin"):
            d = h.cut_dialog(screen_of(name))
            self.assertEqual((d["dialog"], [o["label"] for o in d["options"]]), ("mcp_servers", ["ghidra", "mempalace"]), name)

    def test_switch_model_confirmation(self):
        d = h.cut_dialog(screen_of("subscribe_dialog_switch_model.jsonl"))
        self.assertEqual((d["dialog"], d["title"]), ("other", "Switch model?"))
        self.assertTrue(d["body"].startswith("Your next response will be slower"))
        self.assertEqual(d["options"], [{"label": "Yes, switch to Sonnet 5.5", "key": "1"}, {"label": "No, go back", "key": "2"}])
        self.assertEqual(d["keys"], ["up", "down", "enter", "esc"])

    def test_bash_permission_prompt_drops_the_dashed_fences(self):
        # Recorded live (2.1.288): the command sits between ╌╌╌ rules. They were kept in the body, and the rule row
        # was joined to the command as a soft wrap, so the phone showed a wall of dashes.
        d = h.cut_dialog(screen_of("subscribe_dialog_bash_permission.jsonl"))
        self.assertEqual(d["title"], "Bash command")
        self.assertEqual(d["body"], "Create and delete temp file\n\ntouch /tmp/tether_probe_file && rm /tmp/tether_probe_file"
                                    "\n\nDo you want to proceed?")
        self.assertEqual([o["key"] for o in d["options"]], ["1", "2", "3"])
        self.assertNotIn(u"╌", h.screen_fallback(screen_of("subscribe_dialog_bash_permission.jsonl")))

    def test_no_dialog_on_ordinary_screens(self):
        for name in ("subscribe_streaming_reply.jsonl", "subscribe_reply.jsonl", "subscribe_kill_settled.jsonl",
                     "attach_raw_after_esc.bin", "attach_raw_before_shifttab.bin", "attach_raw_after_shifttab.bin"):
            self.assertIsNone(h.cut_dialog(screen_of(name)), name)

    def test_trust_dialog(self):
        lines = [u"─" * 80, u" Do you trust the files in this folder?", u"", u" /home/user/proj", u"",
                 u" Claude Code may read, write, or execute files contained in this directory.", u"",
                 u" ❯ 1. Yes, proceed", u"   2. No, exit", u"", u" Enter to confirm · Esc to cancel"]
        d = h.cut_dialog(lines)
        self.assertEqual((d["dialog"], d["title"]), ("trust", "Do you trust the files in this folder?"))
        self.assertEqual([o["key"] for o in d["options"]], ["1", "2"])
        self.assertNotIn("Enter to confirm", d["body"])

    HELD = [u"● Held peer message — from uds:/run/user/1000/cc-socks/699377.sock (peer claims name: 3550 hold",
            u"  lifecycle)", u"", u"─" * 80, u" Held message from another session", u"",
            u" Another Claude session sent a message: from uds:/run/user/1000/cc-socks/699377.sock (peer claims",
            u" name: 3550 hold lifecycle)", u" The sending session's permission mode class doesn't match this",
            u" session's, so it wasn't delivered automatically.", u" Message body (this is what will be delivered):",
            u"  │ #3550 bundle hold lifecycle is taking the shared stack", u"",
            u" ❯ Deny — drop it and tell the sender it was declined", u"   Deliver this message to Claude", u"",
            u"  1 tasks (0 done, 1 open)", u"  ◻ Track epic #3495 to completion"]

    def test_held_peer_message_reads_its_unnumbered_options(self):
        # Seen live (2.1.288): the held-message select has no numbers and no "Enter to …" hint, so the phone fell
        # back to the raw screen under "Claude Code is asking something".
        d = h.cut_dialog(self.HELD)
        self.assertEqual((d["dialog"], d["title"]), ("other", "Held message from another session"))
        self.assertIn("so it wasn't delivered automatically.", d["body"])
        self.assertIn(u"│ #3550 bundle hold lifecycle", d["body"])
        self.assertNotIn("Deny", d["body"])
        self.assertNotIn("tasks", d["body"])
        self.assertEqual(d["options"], [{"label": u"Deny — drop it and tell the sender it was declined"},
                                        {"label": "Deliver this message to Claude"}])
        self.assertEqual(d["keys"], ["up", "down", "enter", "esc"])

    def test_held_peer_message_with_the_cursor_moved_gives_only_the_key_pad(self):
        lines = list(self.HELD)
        i = lines.index(u" ❯ Deny — drop it and tell the sender it was declined")
        lines[i], lines[i + 1] = u"   Deny — drop it and tell the sender it was declined", u" ❯ Deliver this message to Claude"
        d = h.cut_dialog(lines)
        self.assertEqual((d["title"], d["options"]), ("Held message from another session", []))
        self.assertEqual(d["keys"], ["up", "down", "enter", "esc"])

    def test_echoed_prompt_and_its_result_are_not_a_list(self):
        lines = [u"─" * 80, u"❯ /model sonnet", u"  ⎿  Kept model", u"  ⎿  Something else"]
        self.assertIsNone(h.cut_dialog(lines))

    def test_prompt_box_hides_older_dialogs_above(self):
        lines = [u"─" * 80, u" Switch model?", u" ❯ 1. Yes", u"   2. No", u"", u"❯ /model sonnet", u"  ⎿  Kept model",
                 u"─" * 60 + u" name ─", u"❯ ", u"─" * 80, u"  ⏸ manual mode on"]
        self.assertIsNone(h.cut_dialog(lines))


class DialogSessionTest(unittest.TestCase):
    """A needs_you session with no question / permission gets its dialog from the live worker's screen."""

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")
        self.model = self.home.serve()

    def tearDown(self):
        self.home.close()

    def session(self):
        ss = self.hh.build_sessions(self.hh.Sources(), self.hh.TranscriptFacts())
        return [s for s in ss if s["sessionId"] == SID][0]

    def test_startup_mcp_dialog_blocks_a_new_session(self):
        # Seen live: state.json blocked ("send a prompt to start"), the daemon's record still active, no registry
        # entry yet (the worker registers after its startup dialogs).
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="blocked", needs="send a prompt to start")
        self.model.add(SHORT, SID, cwd=self.proj, tempo="active", state="running")
        self.model.subscribe_events = fixture_jsonl("subscribe_dialog_mcp_dialog.jsonl")
        s = self.session()
        self.assertEqual((s["state"], s["process"]), ("needs_you", "live"))
        self.assertEqual((s["pending"]["kind"], s["pending"]["dialog"]), ("dialog", "mcp_servers"))
        self.assertEqual(len(s["pending"]["options"]), 4)

    def test_a_relaunch_on_its_startup_dialog_with_last_runs_state_json(self):
        # Live: relaunched into the same job dir, state.json still said "stopped" / idle and the daemon's record
        # active, no registry entry: only the screen shows the MCP-servers dialog.
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="stopped", detail="stopped", tempo="idle")
        self.model.add(SHORT, SID, cwd=self.proj, tempo="active", state="running")
        self.model.subscribe_events = fixture_jsonl("subscribe_dialog_mcp_dialog.jsonl")
        s = self.session()
        self.assertEqual((s["state"], s["process"]), ("needs_you", "live"))
        self.assertEqual((s["pending"]["kind"], s["pending"]["dialog"]), ("dialog", "mcp_servers"))

    def test_a_new_session_before_its_first_prompt_is_working_not_needs_you(self):
        # Seen on every new session (E2E): state.json says blocked for a moment after dispatch, no dialog on screen.
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="blocked", needs="send a prompt to start")
        self.model.add(SHORT, SID, cwd=self.proj, tempo="active", state="running")
        self.model.subscribe_events = [{"type": "snapshot", "record": {}, "streamTail": [
            u"\x1b[2J\x1b[H ✻ Welcome to Claude Code!\r\n\r\n   /help for help\r\n"]}]
        s = self.session()
        self.assertEqual((s["state"], s["pending"], s["waitingFor"]), ("working", None, None))

    def test_dialog_open_skips_a_stale_tool_use(self):
        # The "Switch model?" confirmation: registry waiting / "dialog open"; the transcript's last tool_use has no
        # result, but the open prompt is the dialog, not a permission.
        self.home.transcript(self.proj, SID, [user_line("go", cwd=self.proj), assistant_line(
            [{"type": "tool_use", "id": "toolu_x", "name": "Bash", "input": {"command": "ls"}}])])
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="dialog open", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        self.model.subscribe_events = fixture_jsonl("subscribe_dialog_switch_model.jsonl")
        s = self.session()
        self.assertEqual((s["state"], s["waitingFor"]), ("needs_you", "dialog open"))
        self.assertEqual((s["pending"]["kind"], s["pending"]["title"]), ("dialog", "Switch model?"))
        self.assertEqual([o["key"] for o in s["pending"]["options"]], ["1", "2"])

    def test_unreadable_dialog_falls_back_to_the_screen_text(self):
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="dialog open", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        self.model.subscribe_events = [{"type": "snapshot", "record": {}, "streamTail": [
            u"\x1b[2J\x1b[H  Something new happened\r\n  Press any key\r\n"]}]
        p = self.session()["pending"]
        self.assertEqual((p["kind"], p["dialog"], p["options"]), ("dialog", "other", []))
        self.assertIn("Something new happened", p["body"])
        self.assertIn("enter", p["keys"])

    def test_any_wait_falls_back_to_the_screen_text(self):
        # Claude Code also waits for "goal proposal" and "sandbox request": the phone showed an empty "Claude Code is
        # asking something" key pad with nothing in it.
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="goal proposal", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        self.model.subscribe_events = [{"type": "snapshot", "record": {}, "streamTail": [
            u"\x1b[2J\x1b[H  Claude proposed a session goal\r\n  Ship the fix\r\n"]}]
        s = self.session()
        self.assertEqual((s["state"], s["waitingFor"]), ("needs_you", "goal proposal"))
        self.assertEqual((s["pending"]["kind"], s["pending"]["dialog"]), ("dialog", "other"))
        self.assertIn("Ship the fix", s["pending"]["body"])

    def test_no_fallback_while_the_prompt_box_is_still_up(self):
        # The registry says "dialog open" a moment before the dialog replaces the prompt box.
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="dialog open", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        self.model.subscribe_events = [
            {"type": "snapshot", "record": {}, "streamTail": [
                u"\x1b[2J\x1b[H● PONG.\r\n\r\n" + u"─" * 60 + u"\r\n❯ /model sonnet\r\n" + u"─" * 60 + u"\r\n  ⏸ manual mode on\r\n"]}]
        s = self.session()
        self.assertEqual(s["state"], "needs_you")
        self.assertIsNone(s["pending"])

    def btw_idle(self, screen):
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="dialog open", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        self.model.subscribe_events = [{"type": "snapshot", "record": {}, "streamTail": [screen]}]
        return self.session()

    def test_the_btw_panel_is_not_a_prompt(self):
        # Claude Code registers its /btw side-question panel as "dialog open": the session is idle, not needs_you
        # (the phone showed "Claude Code is asking something" and notified).
        with open(fixture_path("btw_multi_answered.bin"), "rb") as f:
            s = self.btw_idle(f.read().decode("utf-8", "replace"))
        self.assertEqual((s["state"], s["pending"]), ("idle", None))

    def test_the_registry_lags_the_closed_btw_panel(self):
        prompt = u"\x1b[2J\x1b[H● PONG.\r\n\r\n" + u"─" * 60 + u"\r\n❯ \r\n" + u"─" * 60 + u"\r\n  ⏸ manual mode on\r\n"
        self.assertEqual(self.btw_idle(prompt)["state"], "needs_you")  # anything else that said "dialog open"
        self.hh._dialog_screens.clear()
        self.hh.btw_mark(SHORT, 4)  # the helper's own /btw just closed its panel
        self.assertEqual(self.btw_idle(prompt)["state"], "idle")

    def blocked_new_session(self):
        # A new session blocked on the project MCP-servers dialog: its first prompt is still in the launch args.
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="blocked", needs="send a prompt to start",
                      respawnFlags=["--model", "haiku", "--permission-mode", "default"], intent="Reply with exactly: alpha")
        self.model.add(SHORT, SID, cwd=self.proj, tempo="active", state="running")
        self.model.subscribe_events = fixture_jsonl("subscribe_dialog_mcp_dialog.jsonl")

    def test_send_waits_for_the_startup_dialog(self):
        # Live (review round 2): a message sent now was answered before the session's own first prompt.
        self.blocked_new_session()
        rc, out, err = self.home.run("send", SID, stdin=json.dumps({"text": "Reply with exactly: bravo"}))
        self.assertEqual((rc, out.get("code")), (1, "EINVAL"), (out, err))
        self.assertEqual(self.model.replies, [])

    def test_send_goes_through_once_the_first_prompt_landed(self):
        self.blocked_new_session()
        self.home.transcript(self.proj, SID, [user_line("Reply with exactly: alpha", cwd=self.proj)])
        rc, out, err = self.home.run("send", SID, stdin=json.dumps({"text": "bravo"}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(self.model.replies, [(SHORT, "bravo")])

    def test_a_session_stopped_on_its_startup_dialog_starts_again_with_the_message(self):
        # Stopped before any turn ran: no transcript. Sending starts it again under its own id, the message as the
        # first prompt, with its replayable flags.
        self.blocked_new_session()
        self.model.records[SHORT].update(dying=True, outcome="killed", tempo="idle")
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle", needs=None,
                      respawnFlags=["--model", "haiku", "--permission-mode", "default"], intent="Reply with exactly: alpha")
        s = self.session()
        self.assertEqual((s["state"], s["process"]), ("done", "retired"))
        rc, out, err = self.home.run("send", SID, stdin=json.dumps({"text": "Reply with exactly: bravo"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        d = self.model.dispatched[-1]
        self.assertEqual((d["short"], d["sessionId"], d["cwd"], d["launch"]["mode"]), (SHORT, SID, self.proj, "prompt"))
        self.assertEqual(d["launch"]["args"], ["--session-id", SID, "--model", "haiku", "--permission-mode", "default",
                                               "--", "Reply with exactly: bravo"])
        self.assertEqual(self.model.replies, [])  # the text went in at launch, not twice

    def test_the_relaunch_keeps_the_flags_new_started_it_with(self):
        # Live: the CLI rewrote respawnFlags to [] while it sat on the dialog and the roster forgot the stopped
        # worker, so the relaunch came up on the settings default model and mode.
        rc, s, err = self.home.run("new", stdin=json.dumps({"cwd": self.proj, "prompt": "Reply with exactly: alpha",
                                                             "model": "haiku", "permissionMode": "default"}))
        self.assertEqual(rc, 0, (s, err))
        sid, short = s["sessionId"], s["short"]
        self.model.records[short].update(dying=True, outcome="killed", tempo="idle")
        self.home.job(short, sessionId=sid, cwd=self.proj, state="stopped", tempo="idle", respawnFlags=[],
                      intent="Reply with exactly: alpha")
        rc, out, err = self.home.run("send", sid, stdin=json.dumps({"text": "bravo"}))
        self.assertEqual((rc, out), (0, {"ok": True, "woke": True}), err)
        d = self.model.dispatched[-1]
        self.assertEqual((d["sessionId"], d["launch"]["args"]),
                         (sid, ["--session-id", sid, "--model", "haiku", "--permission-mode", "default", "--", "bravo"]))
        rc, out, err = self.home.run("rm", sid)
        self.assertEqual(rc, 0, err)
        self.assertNotIn(sid, self.hh.read_launch_flags())

    def test_follow_cuts_the_dialog_from_its_own_screen(self):
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="done", tempo="idle")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="dialog open", sessionId=SID, jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="done")
        ref = self.hh.SessionRef(SID)
        lines = screen_of("subscribe_dialog_switch_model.jsonl")
        s = ref.session(screen=lambda: lines)
        self.assertEqual(s["pending"]["title"], "Switch model?")
        self.assertEqual(self.model.dispatched, [])


class DaemonBlipTest(unittest.TestCase):
    """The daemon can't be asked for a moment (restart, self-upgrade, busy): live workers stay live."""

    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")
        self.home.transcript(self.proj, SID, [user_line("go", cwd=self.proj)])
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="active")
        self.home.registry(os.getpid(), kind="bg", status="busy", sessionId=SID, jobId=SHORT, startedAt=1790870000000)

    def tearDown(self):
        self.home.close()

    def test_registry_stands_in_for_the_daemon(self):
        src = self.hh.Sources(daemon=False)
        s = [x for x in self.hh.build_sessions(src, self.hh.TranscriptFacts()) if x["sessionId"] == SID][0]
        self.assertEqual((s["state"], s["process"], s["heldBy"]), ("working", "live", "daemon"))

    def test_no_daemon_at_all_is_quick_and_still_live(self):
        t = time.time()
        src = self.hh.Sources()  # nothing listens: ENODAEMON, no socket or lock, no retry
        self.assertLess(time.time() - t, 2.0)
        s = [x for x in self.hh.build_sessions(src, self.hh.TranscriptFacts()) if x["sessionId"] == SID][0]
        self.assertEqual(s["process"], "live")

    def test_requests_ride_out_a_daemon_restart(self):
        import socket
        import threading
        sock_dir = os.path.join(self.home.run_root, self.hh.daemon_socket_hash(self.home.claude))
        os.makedirs(sock_dir)
        os.chmod(self.home.run_root, 0o700)
        os.chmod(sock_dir, 0o700)
        path = os.path.join(sock_dir, "control.sock")
        dead = socket.socket(socket.AF_UNIX)
        dead.bind(path)  # the old daemon's socket file, nobody listening: connection refused
        dead.close()

        def come_back():
            time.sleep(1.0)
            os.remove(path)
            self.home.serve()

        t = threading.Thread(target=come_back)
        t.start()
        try:
            started = time.time()
            self.assertEqual(self.hh.daemon_ping()["proto"], 1)
            self.assertGreater(time.time() - started, 0.8)
        finally:
            t.join()

    def test_a_dead_daemon_gives_up_after_the_grace(self):
        import socket
        sock_dir = os.path.join(self.home.run_root, self.hh.daemon_socket_hash(self.home.claude))
        os.makedirs(sock_dir)
        os.chmod(self.home.run_root, 0o700)
        os.chmod(sock_dir, 0o700)
        dead = socket.socket(socket.AF_UNIX)
        dead.bind(os.path.join(sock_dir, "control.sock"))
        dead.close()
        self.hh.DAEMON_RESTART_GRACE = 1.0
        started = time.time()
        with self.assertRaises(self.hh.DaemonError) as cm:
            self.hh.daemon_ping()
        self.assertEqual(cm.exception.code, "ENODAEMON")
        self.assertLess(time.time() - started, 3.0)

    def test_send_does_not_wake_a_live_session_during_a_blip(self):
        rc, out, err = self.home.run("send", SID, stdin=json.dumps({"text": "hi"}))
        self.assertEqual((rc, out.get("code")), (1, "ENODAEMON"), (out, err))


class StartedAtTest(unittest.TestCase):
    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")
        self.model = self.home.serve()

    def tearDown(self):
        self.home.close()

    def test_a_wake_does_not_reset_startedAt(self):
        self.home.transcript(self.proj, SID, [user_line("first", ts="2026-09-30T08:00:00.000Z", cwd=self.proj),
                                              assistant_line("ok", ts="2026-09-30T08:00:02.000Z")])
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="idle",
                      createdAt="2026-10-01T09:00:00.000Z")
        self.model.add(SHORT, SID, cwd=self.proj, tempo="idle", state="running", createdAt=1790883570642)  # the wake
        s = [x for x in self.hh.build_sessions(self.hh.Sources(), self.hh.TranscriptFacts()) if x["sessionId"] == SID][0]
        self.assertEqual(s["startedAt"], self.hh.iso_to_ms("2026-09-30T08:00:00.000Z"))


class AnswerAndCodesTest(unittest.TestCase):
    def setUp(self):
        self.home = FakeHome()
        self.hh = self.home.h
        self.proj = self.home.folder("proj")
        self.model = self.home.serve()
        self.home.transcript(self.proj, SID, [user_line("rm", cwd=self.proj), assistant_line(
            [{"type": "tool_use", "id": "toolu_new", "name": "Bash", "input": {"command": "rm -rf build"}}])])
        self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="blocked")
        self.home.registry(os.getpid(), kind="bg", status="waiting", waitingFor="permission prompt", sessionId=SID,
                           jobId=SHORT)
        self.model.add(SHORT, SID, cwd=self.proj, tempo="blocked", state="running")

    def tearDown(self):
        self.home.close()

    def test_a_stale_notification_never_answers_a_newer_prompt(self):
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow", "toolUseId": "toolu_old"}))
        self.assertEqual((rc, out.get("code")), (1, "ESTALE"), (out, err))
        self.assertEqual(bytes(self.model.keys), b"")

    def test_the_matching_prompt_is_answered(self):
        def unblock(keys):
            self.model.records[SHORT]["tempo"] = "active"
            self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="active")
            p = os.path.join(self.home.claude, "sessions", "%d.json" % os.getpid())
            if os.path.exists(p):
                os.remove(p)

        self.model.on_keys = unblock
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow", "toolUseId": "toolu_new"}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(bytes(self.model.keys), b"1")

    def screen(self, text):
        rule = u"─" * 60
        self.screen_events([
            {"type": "snapshot", "record": {}, "streamTail": []},
            {"type": "stream", "line": u"\x1b[2J\x1b[H" + rule + u"\r\n" + text.replace(u"\n", u"\r\n")}])

    def screen_events(self, events):
        """The same screen for a subscribe (watch, sessions) and an attach (answer reads it where it types)."""
        self.model.subscribe_events = events
        self.model.attach_screen = attach_bytes(events)

    def unblock_on_keys(self):
        def unblock(keys):
            self.model.records[SHORT]["tempo"] = "active"
            self.home.job(SHORT, sessionId=SID, cwd=self.proj, state="running", tempo="active")
            p = os.path.join(self.home.claude, "sessions", "%d.json" % os.getpid())
            if os.path.exists(p):
                os.remove(p)
        self.model.on_keys = unblock

    def test_allow_always_presses_the_prompts_own_always_row(self):
        # Live 2.1.287 Write prompt: option 2 is "Yes, and switch to accept edits ... for this session".
        self.screen_events(fixture_jsonl("subscribe_perm_write.jsonl"))
        self.unblock_on_keys()
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow_always"}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(bytes(self.model.keys), b"2")

    def test_allow_always_finds_the_row_wherever_it_is(self):
        self.screen(u" Do you want to proceed?\n ❯ 1. Yes\n   2. No, and tell Claude what to do\n"
                    u"   3. Yes, and don't ask again for make commands\n Esc to cancel\n")
        self.unblock_on_keys()
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow_always"}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(bytes(self.model.keys), b"3")

    def test_allow_always_never_presses_no_or_another_action(self):
        for text in (u" Do you want to proceed?\n ❯ 1. Yes\n   2. No\n Esc to cancel\n",
                     u" Would you like to proceed?\n ❯ 1. Yes, and use auto mode\n   2. Yes, manually approve edits\n"
                     u"   3. No, keep planning\n Esc to cancel\n"):
            self.screen(text)
            rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow_always"}))
            self.assertEqual((rc, out.get("code")), (1, "EINVAL"), (text, out, err))
            self.assertEqual(bytes(self.model.keys), b"")

    def test_allow_always_without_a_readable_screen_presses_nothing(self):
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow_always"}))
        self.assertEqual((rc, out.get("code")), (1, "ESTALE"), (out, err))
        self.assertEqual(bytes(self.model.keys), b"")

    def test_allow_presses_1_on_a_yes_no_prompt(self):
        self.screen(u" Do you want to proceed?\n ❯ 1. Yes\n   2. No\n Esc to cancel\n")
        self.unblock_on_keys()
        rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": "allow"}))
        self.assertEqual(rc, 0, (out, err))
        self.assertEqual(bytes(self.model.keys), b"1")

    def test_answer_types_nothing_while_the_message_box_is_up(self):
        # The phone showed a permission prompt the machine's screen didn't: each tap typed its digit into
        # Claude's message box ("11221") and the prompt was never answered.
        rule = u"─" * 60
        self.model.attach_screen = (u"\x1b[2J\x1b[H● Bash(make)\r\n" + rule + u"\r\n❯ \r\n" + rule +
                                    u"\r\n  ⏸ manual mode on\r\n").encode("utf-8")
        for decision in ("allow", "allow_always", "deny"):
            rc, out, err = self.home.run("answer", SID, stdin=json.dumps({"decision": decision, "toolUseId": "toolu_new"}))
            self.assertEqual((rc, out.get("code")), (1, "ESTALE"), (decision, out, err))
            self.assertIn("isn't showing the prompt", out.get("error"))
        self.assertEqual(bytes(self.model.keys), b"")

    def test_an_option_digit_is_not_typed_into_the_message_box(self):
        rule = u"─" * 60
        self.model.attach_screen = (u"\x1b[2J\x1b[H" + rule + u"\r\n❯ \r\n" + rule + u"\r\n").encode("utf-8")
        rc, out, err = self.home.run("key", SID, stdin=json.dumps({"keys": ["1"]}))
        self.assertEqual((rc, out.get("code")), (1, "ESTALE"), (out, err))
        self.assertEqual(bytes(self.model.keys), b"")

    def test_every_error_has_a_code(self):
        for args, stdin in ((("key", SID), {"keys": ["bogus"]}), (("key", SID), {"keys": []}),
                            (("send", SID), {"text": "  "}), (("answer", SID), {"decision": "maybe"}),
                            (("nonsense",), None)):
            rc, out, err = self.home.run(*args, stdin=json.dumps(stdin) if stdin is not None else "")
            self.assertEqual(rc, 1, (args, out, err))
            self.assertEqual(out.get("code"), "EINVAL", (args, out))


if __name__ == "__main__":
    unittest.main()
