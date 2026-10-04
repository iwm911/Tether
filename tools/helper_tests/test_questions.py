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


if __name__ == "__main__":
    unittest.main()
