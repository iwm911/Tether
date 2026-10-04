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


def tab_screen(i):
    """The screen with question i (or the Submit review, i == len(QS)) showing, as ROWS lines of COLS cells."""
    lines = ["Earlier output from Claude, e.g. a checklist: [x] done", "", TABS, ""]
    if i < len(QS):
        q = QS[i]
        import textwrap
        lines += textwrap.wrap(q["question"], 116) + [""]
        for n, o in enumerate(q["options"], 1):
            lines.append(("❯ " if n == 1 else "  ") + "%d. %s" % (n, o["label"]))
        lines += ["  %d. Type something." % (len(q["options"]) + 1), "─" * COLS,
                  "  %d. Chat about this" % (len(q["options"]) + 2)]
    else:
        lines += ["Review your answers", "", "Ready to submit your answers?", "", "❯ 1. Submit answers",
                  "  2. Cancel"]
    lines += [""] * (ROWS - len(lines))
    return [l[:COLS].ljust(COLS) for l in lines[:ROWS]]


class DiffTui(object):
    """A DaemonTui stand-in: ←/→ move between the tabs (no wrap), and each move writes only the changed cells."""

    def __init__(self, at=0):
        self.at = at
        self.buf = bytearray()
        self.screen, self.screen_fed = h.Screen(ROWS, COLS), 0
        import codecs
        self.screen_dec = codecs.getincrementaldecoder("utf-8")("replace")
        self._write("\x1b[2J\x1b[H" + "\r\n".join(tab_screen(at)).rstrip())

    def _write(self, s):
        self.buf.extend(s.encode("utf-8"))

    def send(self, data, wait=0.8):
        before = tab_screen(self.at)
        if data == b"\x1b[C":
            self.at = min(self.at + 1, len(QS))
        elif data == b"\x1b[D":
            self.at = max(self.at - 1, 0)
        after = tab_screen(self.at)
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

    def test_ensure_multi_reads_the_transcript_before_the_screen(self):
        def no_tui():
            raise AssertionError("pressed keys")
        qs, multi = h.ensure_multi("nosuchagent", {"needs": "q", "block": self.BLOCK}, no_tui, self.transcript(self.INPUT))
        self.assertEqual(multi, [True, False])


if __name__ == "__main__":
    unittest.main()
