"""Finding a question's tab on the machine's screen (goto_question / ensure_multi).
The TUI repaints only the characters that changed, so switching between two similar questions writes little more
than scattered letters: the tab must be recognised from the replayed screen, not from the output since the press.
Run: python3 -m unittest discover -s tools/helper_tests"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper_at  # noqa

HOME = tempfile.mkdtemp(prefix="tether-questions-")  # ensure_multi caches under ~/.tether
h = load_helper_at(HOME)

ROWS, COLS = h.KEY_ROWS, h.KEY_COLS

QS = [
    {"question": "Goal: a stuck bundle warns people, then expires. Build it this way?",
     "options": [{"label": "Approve - build it"}, {"label": "Smaller first slice"}, {"label": "Change the approach"}]},
    {"question": "When an admin overrides a zip that could not be opened, nothing inside it was judged. The issue says "
                 "the override forwards the zip as received. The routing rules, though, only move a bundle when some "
                 "file in it has a verdict, so today it would stay inside marked 'no policy judged'. What should happen?",
     "options": [{"label": "Route it as clean (Recommended)"}, {"label": "Keep it inside"}]},
    {"question": "The expiry clock starts when a bundle first gets stuck. Suppose an analyst approves it after 70 of 72 "
                 "hours, and it then waits on an open alert. Under the strict rule it would expire about 2 hours later, "
                 "possibly before anyone is warned. Should approving or overriding restart the clock?",
     "options": [{"label": "Restart on approve/override (Recommended)"}, {"label": "Never restart"}]},
]
TABS = "←  ☐ Build plan  ☐ Unread zip  ☐ Clock reset  ✔ Submit  →"


def tab_screen(i, multi=()):
    """The screen with question i (or the Submit review, i == len(QS)) showing, as ROWS lines of COLS cells.
    Questions in multi draw a box on each option."""
    lines = ["Earlier output from Claude, e.g. a checklist: [x] done", "", TABS, ""]
    if i < len(QS):
        q = QS[i]
        import textwrap
        lines += textwrap.wrap(q["question"], 116) + [""]
        box = "[ ] " if i in multi else ""
        for n, o in enumerate(q["options"], 1):
            lines.append(("❯ " if n == 1 else "  ") + "%d. %s%s" % (n, box, o["label"]))
        lines += ["  %d. Type something." % (len(q["options"]) + 1), "─" * COLS,
                  "  %d. Chat about this" % (len(q["options"]) + 2)]
    else:
        lines += ["Review your answers", "", "Ready to submit your answers?", "", "❯ 1. Submit answers",
                  "  2. Cancel"]
    lines += [""] * (ROWS - len(lines))
    return [l[:COLS].ljust(COLS) for l in lines[:ROWS]]


class DiffTui(object):
    """A DaemonTui stand-in: ←/→ move between the tabs (no wrap), and each move writes only the changed cells."""

    def __init__(self, at=0, multi=()):
        self.at, self.multi = at, multi
        self.sent = []
        self.buf = bytearray()
        self.screen, self.screen_fed = h.Screen(ROWS, COLS), 0
        import codecs
        self.screen_dec = codecs.getincrementaldecoder("utf-8")("replace")
        self._write("\x1b[2J\x1b[H" + "\r\n".join(tab_screen(at, multi)).rstrip())

    def _write(self, s):
        self.buf.extend(s.encode("utf-8"))

    def send(self, data, wait=0.8):
        self.sent.append(data)
        before = tab_screen(self.at, self.multi)
        if data == b"\x1b[C":
            self.at = min(self.at + 1, len(QS))
        elif data == b"\x1b[D":
            self.at = max(self.at - 1, 0)
        after = tab_screen(self.at, self.multi)
        out = []
        for r in range(ROWS):
            for c in range(COLS):
                if before[r][c] != after[r][c]:
                    out.append("\x1b[%d;%dH%s" % (r + 1, c + 1, after[r][c]))
        self._write("".join(out))

    def mark(self):
        return len(self.buf)

    text = h.DaemonTui.text
    lines = h.DaemonTui.lines
    screen_text = h.DaemonTui.screen_text

    def close(self):
        pass


class GotoQuestionTest(unittest.TestCase):
    def test_reaches_every_tab_across_diff_repaints(self):
        tui = DiffTui(0)
        seen = {}
        for i in range(len(QS)):
            self.assertTrue(h.goto_question(tui, QS, i, seen), "tab %d" % i)
            self.assertEqual(tui.at, i)
        self.assertTrue(h.goto_question(tui, QS, 0, seen))
        self.assertEqual(tui.at, 0)
        self.assertEqual(seen, {0: False, 1: False, 2: False})

    def test_steps_back_from_the_submit_review(self):
        tui = DiffTui(len(QS))
        self.assertEqual(h.current_question(tui.screen_text(), QS), (None, None))
        self.assertTrue(h.goto_question(tui, QS, 1))
        self.assertEqual(tui.at, 1)

    def test_ensure_multi_reads_all_three(self):
        st = {"needs": "q", "block": {"questions": [{"question": q["question"], "options": q["options"]} for q in QS]}}
        qs, multi = h.ensure_multi("nosuchagent", st, lambda: DiffTui(0))
        self.assertEqual(len(qs), 3)
        self.assertEqual(multi, [False, False, False])


class ToolInputTest(unittest.TestCase):
    """The daemon's block drops header and multiSelect (2.1.289): they come from the pending tool_use."""
    BLOCK = {"questions": [
        {"question": "Which pets do you like?", "options": [{"label": "Cats", "description": ""}, {"label": "Dogs", "description": ""}]},
        {"question": "Which color?", "options": [{"label": "Red", "description": ""}, {"label": "Blue", "description": ""}]}]}
    INPUT = {"questions": [
        {"question": "Which pets do you like?", "header": "Pets", "multiSelect": True,
         "options": [{"label": "Cats", "description": ""}, {"label": "Dogs", "description": ""}]},
        {"question": "Which color?", "header": "Color",
         "options": [{"label": "Red", "description": ""}, {"label": "Blue", "description": ""}]}]}

    def transcript(self, inp, answered=False):
        import json
        path = os.path.join(tempfile.mkdtemp(dir=HOME), "t.jsonl")
        lines = [{"type": "assistant", "message": {"role": "assistant", "content": [
            {"type": "tool_use", "id": "toolu_ASK", "name": "AskUserQuestion", "input": inp}]}}]
        if answered:
            lines.append({"type": "user", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "toolu_ASK", "content": "ok"}]}})
        with open(path, "w") as f:
            f.write("".join(json.dumps(l) + "\n" for l in lines))
        return path

    def test_pending_question_takes_multi_select_and_header_from_the_transcript(self):
        import json
        st = {"needs": "answer: Which pets do you like?", "block": self.BLOCK}
        p = h.question_pending("nosuchagent", st, self.transcript(self.INPUT))
        inp = json.loads(p["inputJson"])
        self.assertTrue(inp["multiSelectKnown"])
        self.assertEqual([(q["header"], q["multiSelect"]) for q in inp["questions"]], [("Pets", True), ("Color", False)])
        # the request id stays the one the block alone gives, before the transcript has the tool_use
        self.assertEqual(p["toolUseId"], h.question_pending("nosuchagent", st)["toolUseId"])

    def test_unknown_without_a_matching_pending_tool_use(self):
        import json
        st = {"needs": "q", "block": self.BLOCK}
        other = {"questions": [dict(self.INPUT["questions"][0], question="Something else?")]}
        for tpath in (None, self.transcript(self.INPUT, answered=True), self.transcript(other)):
            inp = json.loads(h.question_pending("nosuchagent", st, tpath)["inputJson"])
            self.assertFalse(inp["multiSelectKnown"])

    def screen(self, tab):
        """The live screen with question tab (0 or 1) of BLOCK showing, as 2.1.292 draws it."""
        q = self.BLOCK["questions"][tab]
        box = "[ ] " if tab == 0 else ""
        return (["Earlier output: [x] done", "─" * 80, "←  ☐ Pets  ☐ Color  ✔ Submit  →", "│ " + q["question"]] +
                ["%s%d. %s%s" % ("❯ " if n == 1 else "  ", n, box, o["label"]) for n, o in enumerate(q["options"], 1)] +
                ["  3. %sType something" % box, "─" * 80, "  4. Chat about this", "Enter to select"])

    def test_pending_question_reads_multi_select_off_the_screen(self):
        # 2.1.292 writes the AskUserQuestion tool_use only once answered: the transcript can't tell.
        import json
        st = {"needs": "q-screen", "block": self.BLOCK}
        reads = []

        def screen(tab):
            return lambda: reads.append(tab) or self.screen(tab)
        inp = json.loads(h.question_pending("screenagent", st, None, screen(0))["inputJson"])
        self.assertEqual([(q["header"], q.get("multiSelect")) for q in inp["questions"]], [("Pets", True), ("", None)])
        self.assertFalse(inp["multiSelectKnown"])
        # the terminal moves to the second tab: what the first read saw is kept
        p = h.question_pending("screenagent", st, None, screen(1))
        inp = json.loads(p["inputJson"])
        self.assertTrue(inp["multiSelectKnown"])
        self.assertEqual([(q["header"], q["multiSelect"]) for q in inp["questions"]], [("Pets", True), ("Color", False)])
        # once every question is known, the screen is not read again; the request id never changes
        p2 = h.question_pending("screenagent", st, None, screen(0))
        self.assertEqual(reads, [0, 1])
        self.assertEqual(p2["inputJson"], p["inputJson"])
        self.assertEqual(p["toolUseId"], h.question_pending("screenagent", st)["toolUseId"])

    def test_transcript_wins_over_the_screen(self):
        def no_screen():
            raise AssertionError("read the screen")
        import json
        st = {"needs": "q", "block": self.BLOCK}
        inp = json.loads(h.question_pending("nosuchagent", st, self.transcript(self.INPUT), no_screen)["inputJson"])
        self.assertTrue(inp["multiSelectKnown"])

    def test_ensure_multi_reads_the_transcript_before_the_screen(self):
        def no_tui():
            raise AssertionError("pressed keys")
        qs, multi = h.ensure_multi("nosuchagent", {"needs": "q", "block": self.BLOCK}, no_tui, self.transcript(self.INPUT))
        self.assertEqual(multi, [True, False])


class TabWalkTest(unittest.TestCase):
    """2.1.292 writes the tool_use only once answered and a passive read sees one tab: the rest come from a walk."""
    ST = {"needs": "q-walk", "block": {"questions": [{"question": q["question"], "options": q["options"]} for q in QS]}}

    def test_pending_question_walks_the_tabs_it_could_not_see(self):
        import json
        tui = DiffTui(0, multi=(0, 1, 2))
        walks = []

        def walk(key, qs):
            walks.append(key)
            h.walk_question_tabs("walkagent", key, qs, lambda: tui)
        p = h.question_pending("walkagent", self.ST, None, tui.lines, walk)
        self.assertFalse(json.loads(p["inputJson"])["multiSelectKnown"])  # this read saw only the first tab
        self.assertEqual(tui.at, 0)  # back where it was
        p2 = h.question_pending("walkagent", self.ST, None, tui.lines, walk)
        inp = json.loads(p2["inputJson"])
        self.assertTrue(inp["multiSelectKnown"])
        self.assertEqual([(q["header"], q["multiSelect"]) for q in inp["questions"]],
                         [("Build plan", True), ("Unread zip", True), ("Clock reset", True)])
        self.assertEqual(len(walks), 1)
        self.assertEqual(p2["toolUseId"], p["toolUseId"])

    def test_walk_returns_to_the_tab_that_was_showing(self):
        tui = DiffTui(1, multi=(2,))
        seen = h.walk_question_tabs("walkagent2", "k", QS, lambda: tui)
        self.assertEqual(tui.at, 1)
        self.assertEqual(dict((i, f["multiSelect"]) for i, f in seen.items()), {"0": False, "1": False, "2": True})
        self.assertNotIn(b"\r", tui.sent)  # nothing chosen

    def test_only_what_is_below_the_tab_bar_counts(self):
        # the prompt above may quote every question and option
        above = [" ".join(q["question"] + " " + " ".join(o["label"] for o in q["options"]) for q in QS)]
        for i in range(len(QS)):
            text = "".join(above + tab_screen(i, multi=(2,))).replace(" ", "")
            self.assertEqual(h.current_question(text, QS), (i, i == 2))

    def test_one_walk_per_question_block(self):
        self.assertTrue(h.claim_tab_walk("walkagent3", "k1"))
        self.assertFalse(h.claim_tab_walk("walkagent3", "k1"))
        self.assertTrue(h.claim_tab_walk("walkagent3", "k2"))


if __name__ == "__main__":
    unittest.main()
