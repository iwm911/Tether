"""Editing a sent message: the /rewind panel read off the screen, the current branch of a rewound transcript, and
the tether-rewind line `follow` sends when a prompt starts a new branch. Screens are as drawn by Claude Code
2.1.332 (a throwaway haiku session).
Run: python3 -m unittest discover -s tools/helper_tests"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper  # noqa

h = load_helper()

LIST = [
    u"❯ Say OK", "", u"● OK", "",
    u"▔" * 60,
    "  Rewind", "",
    u"  Restore the code and/or conversation to the point before…", "",
    "    Say just the word ONE",
    "    No code changes", "",
    u"  ❯ Create a file named a.txt containing hello. Use the Write tool.…",
    "    a.txt +1", "",
    "    (current)", "", "",
    u"  Enter to continue · Esc to cancel",
]

CONFIRM = [
    u"▔" * 60,
    "  Rewind", "",
    "  Restore to the point before you sent this message:", "",
    u"  │ Create a file named a.txt containing hello. Use the Write tool.",
    u"  │ Then say DONE.",
    u"  │ (35s ago)", "",
    "  The conversation goes back to before this message.",
    "  The code will be restored -1 in a.txt.", "",
    u"  ❯ 1. Restore code and conversation",
    "    2. Restore conversation",
    "    3. Restore code",
    "    4. Summarize from here",
    "    5. Summarize up to here",
    "    6. Never mind",
]

PROMPT = ["  some history", "", u"─" * 60, u"❯ ", u"─" * 60, u"  ⏵⏵ auto mode on"]


class RewindScreenTest(unittest.TestCase):
    def test_list_cursor(self):
        self.assertEqual(h.rewind_screen(LIST), {
            "kind": "list", "cursor": "Create a file named a.txt containing hello. Use the Write tool.…", "at": 2})

    def test_list_on_current(self):
        rows = [r.replace(u"  ❯ Create", "    Create").replace("    (current)", u"  ❯ (current)") for r in LIST]
        self.assertEqual(h.rewind_screen(rows)["cursor"], "(current)")

    def test_confirm(self):
        r = h.rewind_screen(CONFIRM)
        self.assertEqual(r["kind"], "confirm")
        self.assertEqual(r["quote"], "Create a file named a.txt containing hello. Use the Write tool.\nThen say DONE.")
        self.assertEqual([o["label"] for o in r["options"]][:2], ["Restore code and conversation", "Restore conversation"])
        self.assertEqual(r["options"][5], {"n": "6", "label": "Never mind"})

    def test_not_open(self):
        self.assertIsNone(h.rewind_screen(PROMPT))
        self.assertTrue(h.prompt_box_empty(PROMPT))
        self.assertFalse(h.prompt_box_empty(["x", u"─" * 60, u"❯ old text", u"─" * 60]))
        self.assertFalse(h.prompt_box_empty(["x", u"─" * 60, u"❯ first line", "  second line", u"─" * 60]))


class RewindMatchTest(unittest.TestCase):
    TEXT = "Create a file named a.txt containing hello. Use the Write tool.\nThen say DONE."

    def test_row(self):
        self.assertTrue(h.rewind_row_matches("Create a file named a.txt containing hello. Use the Write tool.…", self.TEXT))
        self.assertTrue(h.rewind_row_matches("Create a file named…", self.TEXT))
        self.assertTrue(h.rewind_row_matches("Say just the word ONE", "Say just the word ONE"))
        self.assertFalse(h.rewind_row_matches("Say just the word ONE", "Say just the word ONEs"))
        self.assertFalse(h.rewind_row_matches("(current)", self.TEXT))
        self.assertFalse(h.rewind_row_matches(None, self.TEXT))

    def test_quote(self):
        q = "Create a file named a.txt containing hello. Use the Write\ntool.\nThen say DONE."  # wrapped differently
        self.assertTrue(h.rewind_quote_matches(q, self.TEXT))
        self.assertFalse(h.rewind_quote_matches("Create a file", self.TEXT))
        self.assertTrue(h.rewind_quote_matches(u"Create a file named…", self.TEXT))


def line(uuid, parent, kind="assistant", text="", **extra):
    o = {"type": kind, "uuid": uuid, "parentUuid": parent, "sessionId": "s", "timestamp": "2026-11-25T10:00:00Z"}
    if kind == "user":
        o["message"] = {"role": "user", "content": text}
    elif kind == "assistant":
        o["message"] = {"role": "assistant", "id": "m-" + uuid, "content": [{"type": "text", "text": text or "ok"}]}
    elif kind == "system":
        o["subtype"] = "turn_duration"
    o.update(extra)
    return json.dumps(o)


def tool_turn(u, parent, text):
    """A prompt, a tool call and its result, an answer: [lines], the uuid of the last one."""
    return [
        line(u + "p", parent, "user", text),
        json.dumps({"type": "assistant", "uuid": u + "a", "parentUuid": u + "p", "message": {
            "role": "assistant", "id": "m" + u, "content": [{"type": "tool_use", "id": "t" + u, "name": "Bash", "input": {}}]}}),
        json.dumps({"type": "user", "uuid": u + "r", "parentUuid": u + "a", "message": {
            "role": "user", "content": [{"type": "tool_result", "tool_use_id": "t" + u, "content": "x"}]}}),
        line(u + "b", u + "r", "assistant", "done"),
        line(u + "s", u + "b", "system"),
    ], u + "s"


# ONE, TWO (a tool turn), THREE; then rewound to before TWO and FOUR sent from there.
T1, end1 = tool_turn("1", "h0", "Say ONE")
T2, end2 = tool_turn("2", end1, "Say TWO")
T3, end3 = tool_turn("3", end2, "Say THREE")
SIDE = [line("side", "x", "assistant", "subagent", isSidechain=True)]
T4, end4 = tool_turn("4", end1, "Say FOUR")
TRANSCRIPT = [line("h0", None, "attachment")] + T1 + T2 + SIDE + T3 + T4


def feed(lines, start_at=0):
    tev = h.TranscriptEvents("s", PATH)
    out, pos = [], 0
    for raw in lines:
        b = raw.encode() + b"\n"
        pos += len(b)
        if pos <= start_at:
            continue
        out += [e for e in tev.feed(b[:-1], pos) if isinstance(e, tuple) and e[0] == "line"]
    return out


PATH = None


class RewindFollowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global PATH
        fd, PATH = tempfile.mkstemp(suffix=".jsonl")
        with os.fdopen(fd, "w") as f:
            f.write("\n".join(TRANSCRIPT) + "\n")

    @classmethod
    def tearDownClass(cls):
        os.unlink(PATH)

    def test_rewind_line_before_the_new_prompt(self):
        evs = feed(TRANSCRIPT)
        rewinds = [e for e in evs if "tether-rewind" in e[1]]
        self.assertEqual(len(rewinds), 1)
        self.assertEqual(json.loads(rewinds[0][1]), {"type": "tether-rewind", "uuids": ["2p", "3p"]})
        self.assertIsNone(rewinds[0][2])  # no offset: a reconnect resumes from real lines only
        i = evs.index(rewinds[0])
        self.assertEqual(json.loads(evs[i + 1][1])["uuid"], "4p")

    def test_no_rewind_in_a_straight_conversation(self):
        self.assertFalse([e for e in feed([line("h0", None, "attachment")] + T1 + T2 + SIDE + T3) if "tether-rewind" in e[1]])

    def test_follow_resumed_past_the_branch_point(self):
        # --from: the lines before the resume point are read back only to place the rewind.
        start = sum(len(x.encode()) + 1 for x in TRANSCRIPT[:len(TRANSCRIPT) - len(T4)])
        rewinds = [e for e in feed(TRANSCRIPT, start) if "tether-rewind" in e[1]]
        self.assertEqual([json.loads(e[1])["uuids"] for e in rewinds], [["2p", "3p"]])

    def test_line_event_without_offset(self):
        self.assertEqual(h.line_event('{"a":1}', None), '{"e":"line","line":{"a":1}}')
        self.assertEqual(h.line_event('{"a":1}', 7), '{"e":"line","line":{"a":1},"offset":7}')

    def test_branch_prompts(self):
        self.assertEqual(h.branch_prompts(PATH), [("1p", "Say ONE"), ("4p", "Say FOUR")])


if __name__ == "__main__":
    unittest.main()
