"""Which tool_use a needs_you session is waiting on (pending_tool_block / pending_tool_use).
Claude Code 2.1.32x also writes "api-request-blob" lines: wire copies of earlier assistant messages, tool_use blocks
included. They are not conversation entries, and their tool_use ids are answered further up the file, possibly
before the tail the helper reads.
Run: python3 -m unittest discover -s tools/helper_tests"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper_at  # noqa

HOME = tempfile.mkdtemp(prefix="tether-pending-")
h = load_helper_at(HOME)

SID = "a8db1bad-e53c-489a-b7e1-de9abefb850a"
OLD_ID = "toolu_01VkSjUQdSB9Fdn6DchUfEBG"
CMD = "grep -n reviews services/backend/src/config/rbac-matrix.js | head -30"


def tool_use(tid, command):
    return {"type": "tool_use", "id": tid, "name": "Bash", "input": {"command": command}, "caller": {"type": "direct"}}


def assistant(content):
    return {"type": "assistant", "isSidechain": False, "sessionId": SID,
            "message": {"role": "assistant", "content": content}}


def result(tid, text):
    return {"type": "user", "isSidechain": False, "sessionId": SID,
            "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": text}]}}


def blob(content):
    """An api-request-blob line, as 2.1.326 writes it after each request."""
    return {"type": "api-request-blob", "sessionId": SID, "hash": "cf08", "message": {"role": "assistant",
            "content": content}}


class PendingToolTest(unittest.TestCase):
    def write(self, entries):
        fd, path = tempfile.mkstemp(suffix=".jsonl", dir=HOME)
        with os.fdopen(fd, "w") as f:
            for e in entries:
                f.write(json.dumps(e) + "\n")
        return path

    def test_request_blob_copy_of_an_answered_tool_use_is_not_pending(self):
        # The real tool_use and its (large) result fall before the 512 KiB tail; the blob's wire copy of the same
        # tool_use (with a "cd <worktree> &&" prefix) falls inside it. The turn then ended with a text hand-off.
        big = "x" * (600 * 1024)
        path = self.write([
            assistant([tool_use(OLD_ID, CMD)]),
            result(OLD_ID, big),
            blob([{"type": "thinking", "thinking": ""}, tool_use(OLD_ID, "cd /wt && " + CMD)]),
            assistant([{"type": "text", "text": "Want me to post the comment on the epic?"}]),
        ])
        self.assertIsNone(h.pending_tool_block(path))
        self.assertIsNone(h.pending_tool_use(path, "/wt"))

    def test_real_unanswered_tool_use_is_still_pending(self):
        path = self.write([
            assistant([tool_use(OLD_ID, CMD)]),
            result(OLD_ID, "ok"),
            blob([tool_use(OLD_ID, "cd /wt && " + CMD)]),
            assistant([tool_use("toolu_new", "gh issue comment 3649 --body-file c.md")]),
        ])
        self.assertEqual(h.pending_tool_block(path)["id"], "toolu_new")


if __name__ == "__main__":
    unittest.main()
