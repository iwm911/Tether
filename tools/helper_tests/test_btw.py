"""/btw side questions: the panel read from the screen, the answer from the panel's "c to copy" (OSC 52), and the
key sequence ask_btw types. Screens are raw attach streams recorded live from 2.1.289 (a throwaway haiku session):
the panel with one question in its history and with several, each while answering and once answered.
Run: python3 -m unittest discover -s tools/helper_tests"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import fixture_path, load_helper  # noqa

h = load_helper()


def raw(name):
    with open(fixture_path(name), "rb") as f:
        return f.read()


def screen_of(name):
    s = h.Screen(h.KEY_ROWS, h.KEY_COLS)
    s.feed(raw(name).decode("utf-8", "replace"))
    return s.lines()


PROMPT = ["  some history", "", "─" * 60, u"❯ ", "─" * 60, u"  ⏸ manual mode on"]


def panel(*body, hint=u"↑/↓ to scroll · c to copy · f to fork · Esc to close"):
    return ["  some history", "", u"▔" * 60, ""] + ["    " + b if b else "" for b in body] + ["", "    " + hint]


class BtwPanelTest(unittest.TestCase):
    def test_one_question_answering_then_answered(self):
        p = h.btw_panel(screen_of("btw_single_answering.bin"))
        self.assertEqual(p["question"], "what is the code word? one word")
        self.assertEqual(p["state"], "answering")
        p = h.btw_panel(screen_of("btw_single_answered.bin"))
        self.assertEqual(p, {"question": "what is the code word? one word", "state": "answered", "text": "PELICAN"})

    def test_newest_of_several_questions(self):
        q = "list two facts about pelicans as a markdown list, bold one word in each"
        p = h.btw_panel(screen_of("btw_multi_answering.bin"))
        self.assertEqual((p["question"], p["state"]), (q, "answering"))
        p = h.btw_panel(screen_of("btw_multi_answered.bin"))
        self.assertEqual((p["question"], p["state"]), (q, "answered"))
        self.assertTrue(p["text"].startswith("- Pelicans have a large pouch"))
        self.assertEqual(p["text"].count("\n- "), 1)

    def test_no_panel_on_the_prompt_or_a_dialog(self):
        self.assertIsNone(h.btw_panel(screen_of("attach_raw_after_esc.bin")))
        self.assertIsNone(h.btw_panel(screen_of("attach_raw_mcp_dialog.bin")))
        self.assertIsNone(h.btw_panel(PROMPT))

    def test_an_error_in_the_panel_is_a_failure(self):
        p = h.btw_panel(panel("/btw why?", "  API Error: overloaded", hint=u"Esc to close"))
        self.assertEqual((p["state"], p["text"]), ("failed", "API Error: overloaded"))

    def test_soft_wrapped_answer_is_joined(self):
        long = "word " * 10 + "x" * 30
        p = h.btw_panel(panel("/btw q", "  " + long.strip(), "  continues here"))
        self.assertEqual(p["text"], long.strip() + " continues here")

    def test_cut_question_still_matches(self):
        self.assertTrue(h.btw_same_question(u"what is the code word, and say two sen…",
                                            "what is the code word, and say two sentences"))
        self.assertTrue(h.btw_same_question("a  b", "a b"))
        self.assertFalse(h.btw_same_question("an earlier question", "the question"))
        self.assertFalse(h.btw_same_question("", "q"))


class PromptAndCopyTest(unittest.TestCase):
    def test_prompt_box_text(self):
        self.assertEqual(h.prompt_box_text(screen_of("attach_raw_after_esc.bin")), "")
        self.assertIsNone(h.prompt_box_text(screen_of("attach_raw_mcp_dialog.bin")))
        typed = list(PROMPT)
        typed[3] = u"❯ /btw hi"
        self.assertEqual(h.prompt_box_text(typed), "/btw hi")

    def test_answer_is_the_osc52_copy(self):
        self.assertEqual(h.osc52_text(raw("btw_single_copy.bin")), "PELICAN")
        md = h.osc52_text(raw("btw_multi_copy.bin"))
        self.assertIn("**pouch**", md)
        self.assertEqual(md.count("\n"), 1)
        self.assertIsNone(h.osc52_text(b"\x1b[2Jplain"))
        self.assertEqual(h.osc52_text(b"\x1b]52;c;aGk=\x1b\\"), "hi")


class FakeTui(object):
    """A terminal that behaves like the session: prompt box, then the panel once /btw is entered."""

    def __init__(self, stale=False, answer="**hi**", typed_late=0):
        self.buf = bytearray()
        self.sent = []
        self.typed = ""
        self.stage = "stale" if stale else "prompt"
        self.answer = answer
        self.typed_late = typed_late  # keystrokes dropped while the worker still starts
        self.closed = False

    def lines(self):
        if self.stage == "stale":
            return panel("/btw old", "  old answer")
        if self.stage == "prompt":
            out = list(PROMPT)
            out[3] = u"❯ " + self.typed
            return out
        if self.stage == "answering":
            return panel("/btw " + self.q, u"  ✽ Answering…", hint="Esc to close")
        return panel("/btw " + self.q, "  " + self.answer)

    def send(self, data, wait=0.8):
        self.sent.append(data)
        if data == h.KEY_BYTES["esc"]:
            if self.stage == "prompt":
                raise AssertionError("Esc at the prompt would interrupt Claude")
            self.stage = "prompt"
        elif data == h.KEY_BYTES["enter"]:
            self.q, self.typed, self.stage = self.typed[5:], "", "answering"
        elif data == b"c" and self.stage == "answered":
            import base64
            self.buf.extend(b"\x1b]52;c;" + base64.b64encode(self.answer.encode()) + b"\x07")
        elif self.stage == "prompt":
            if self.typed_late:
                self.typed_late -= 1
            else:
                self.typed += data.decode()

    def pump(self, seconds, until=None):
        if self.stage == "answering":
            self.stage = "answered"
        return bool(until and (until(self) if callable(until) else until in self.buf))

    def mark(self):
        return len(self.buf)

    def close(self):
        self.closed = True


class AskBtwTest(unittest.TestCase):
    def run_with(self, tui, question="what now?"):
        real, mark = h.DaemonTui, h.btw_mark
        h.DaemonTui = lambda short, **kw: tui
        h.btw_mark = lambda short, seconds: None  # never in the real ~/.tether
        try:
            return h.ask_btw("abcd1234", question)
        finally:
            h.DaemonTui, h.btw_mark = real, mark

    def test_types_the_question_and_returns_the_copied_markdown(self):
        tui = FakeTui()
        self.assertEqual(self.run_with(tui), "**hi**")
        self.assertEqual(tui.sent[0], b"/btw what now?")
        self.assertEqual(tui.sent[1], b"\r")
        self.assertEqual(tui.sent[-1], h.KEY_BYTES["esc"])  # the panel is closed again
        self.assertEqual(tui.stage, "prompt")
        self.assertTrue(tui.closed)

    def test_a_stale_panel_is_put_away_first(self):
        tui = FakeTui(stale=True)
        self.assertEqual(self.run_with(tui), "**hi**")
        self.assertEqual(tui.sent[0], h.KEY_BYTES["esc"])
        self.assertEqual(tui.sent[1], b"/btw what now?")

    def test_keys_lost_while_waking_are_typed_again(self):
        tui = FakeTui(typed_late=1)
        self.assertEqual(self.run_with(tui), "**hi**")
        self.assertEqual(tui.sent[:3], [b"/btw what now?", b"/btw what now?", b"\r"])

    def test_refuses_over_a_dialog_or_unsent_text(self):
        tui = FakeTui()
        tui.lines = lambda: screen_of("attach_raw_mcp_dialog.bin")
        with self.assertRaises(h.HelperError) as e:
            self.run_with(tui)
        self.assertEqual(getattr(e.exception, "code", None), "EINVAL")
        self.assertEqual(tui.sent, [])
        tui = FakeTui()
        tui.typed = "half a message"
        with self.assertRaises(h.HelperError):
            self.run_with(tui)
        self.assertEqual(tui.sent, [])


if __name__ == "__main__":
    unittest.main()
