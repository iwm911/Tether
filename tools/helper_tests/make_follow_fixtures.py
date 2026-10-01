"""Writes the follow-events fixtures (a transcript with peers, background tasks, subagents and todos, in the exact
line shapes Claude Code 2.1.286/2.1.287 writes them; sanitized). Re-run to regenerate:

    python3 tools/helper_tests/make_follow_fixtures.py
"""

import json
import os

F = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "fixtures", "daemon")
SID = "5a1e0c4b-1111-4222-8333-944455556666"
SLUG = "-home-user-proj"
OUT = "/tmp/claude-1000/%s/%s/tasks/" % (SLUG, SID)
_n = [0]


def ts():
    _n[0] += 1
    return "2026-10-01T10:00:%02d.000Z" % _n[0]


def user(uuid, content, **kw):
    d = {"parentUuid": None, "isSidechain": False, "type": "user", "message": {"role": "user", "content": content},
         "uuid": uuid, "timestamp": ts(), "sessionId": SID, "cwd": "/home/user/proj", "gitBranch": "main"}
    d.update(kw)
    return d


def assistant(uuid, blocks, model="claude-haiku-4-5-20251001"):
    return {"parentUuid": None, "isSidechain": False, "type": "assistant",
            "message": {"model": model, "id": "msg_" + uuid, "type": "message", "role": "assistant", "content": blocks},
            "uuid": uuid, "timestamp": ts(), "sessionId": SID, "cwd": "/home/user/proj"}


TN_BASH = ("<task-notification>\n<task-id>b66dr19lq</task-id>\n<tool-use-id>toolu_bg1</tool-use-id>\n"
           "<output-file>%sb66dr19lq.output</output-file>\n<status>completed</status>\n"
           "<summary>Background command \"npm ci\" completed (exit code 0)</summary>\n</task-notification>") % OUT
TN_AGENT = ("<task-notification>\n<task-id>a16a79700d860b0d3</task-id>\n<tool-use-id>toolu_ag1</tool-use-id>\n"
            "<output-file>%sa16a79700d860b0d3.output</output-file>\n<status>completed</status>\n"
            "<summary>Agent \"Product brief\" finished</summary>\n<note>A task-notification fires each time this agent "
            "stops.</note>\n<result>Brief written.</result>\n</task-notification>") % OUT
PEER = ("<cross-session-message from=\"uds:/run/user/1000/cc-socks/PEERPID.sock\" from-name=\"review 3504 reports\" "
        "from-mode=\"prompting\">\nHeads-up: I plan to refresh the shared Docker stack.\n</cross-session-message>")


def transcript():
    return [
        {"type": "permission-mode", "permissionMode": "acceptEdits", "sessionId": SID},
        user("u1", "Build it and brief me."),
        assistant("a1", [{"type": "text", "text": "I'll start the build in the background."},
                         {"type": "tool_use", "id": "toolu_bg1", "name": "Bash",
                          "input": {"command": "npm ci", "description": "Install deps", "run_in_background": True}}]),
        user("u2", [{"tool_use_id": "toolu_bg1", "type": "tool_result", "is_error": False,
                     "content": "Command running in background with ID: b66dr19lq. Output is being written to: "
                                "%sb66dr19lq.output. You will be notified when it completes." % OUT}],
             toolUseResult={"stdout": "", "stderr": "", "interrupted": False, "isImage": False,
                            "noOutputExpected": False, "backgroundTaskId": "b66dr19lq"}),
        assistant("a2", [{"type": "tool_use", "id": "toolu_ag1", "name": "Agent",
                          "input": {"description": "Product brief", "subagent_type": "Explore", "model": "opus",
                                    "prompt": "Read the wiki.", "run_in_background": True}}]),
        user("u3", [{"tool_use_id": "toolu_ag1", "type": "tool_result",
                     "content": [{"type": "text", "text": "Async agent launched successfully.\nagentId: a16a79700d860b0d3"}]}],
             toolUseResult={"isAsync": True, "status": "async_launched", "agentId": "a16a79700d860b0d3",
                            "description": "Product brief", "prompt": "Read the wiki."}),
        assistant("a3", [{"type": "tool_use", "id": "toolu_ag2", "name": "Agent",
                          "input": {"description": "Scan issues", "subagent_type": "general-purpose", "prompt": "Scan."}}]),
        user("u4", [{"tool_use_id": "toolu_ag2", "type": "tool_result", "content": [{"type": "text", "text": "No related issues."}]}],
             toolUseResult={"status": "completed", "agentId": "a37c067ef6ed1aec1",
                            "content": [{"type": "text", "text": "No related issues."}]}),
        assistant("a4", [{"type": "tool_use", "id": "toolu_td", "name": "TodoWrite",
                          "input": {"todos": [{"content": "Write tests", "status": "in_progress", "activeForm": "Writing tests"},
                                              {"content": "Ship", "status": "pending", "activeForm": "Shipping"}]}}]),
        user("u5", [{"tool_use_id": "toolu_td", "type": "tool_result", "content": "Todos have been modified successfully."}]),
        {"type": "queue-operation", "operation": "enqueue", "timestamp": ts(), "sessionId": SID, "content": TN_BASH},
        {"type": "queue-operation", "operation": "remove", "timestamp": ts(), "sessionId": SID, "content": TN_BASH,
         "reason": "absorbed_mid_turn"},
        user("u6", TN_BASH, origin={"kind": "task-notification", "producer": "session-task"}),
        {"type": "queue-operation", "operation": "enqueue", "timestamp": ts(), "sessionId": SID, "content": PEER},
        {"type": "queue-operation", "operation": "dequeue", "timestamp": ts(), "sessionId": SID},
        user("u7", "Another Claude session sent a message:\n" + PEER + "\n\nThis came from another Claude session.",
             isMeta=True, origin={"kind": "peer", "from": "uds:/run/user/1000/cc-socks/PEERPID.sock",
                                  "name": "review 3504 reports"}),
        {"type": "queue-operation", "operation": "enqueue", "timestamp": ts(), "sessionId": SID, "content": TN_AGENT},
        {"type": "pr-link", "sessionId": SID, "prNumber": 3532, "prUrl": "https://github.com/example/proj/pull/3532",
         "prRepository": "example/proj", "timestamp": ts()},
        assistant("a5", [{"type": "text", "text": "All done: build passed and the brief is in."}]),
        {"type": "custom-title", "customTitle": "build and brief", "sessionId": SID},
    ]


METAS = {
    "a16a79700d860b0d3": {"agentType": "Explore", "description": "Product brief", "toolUseId": "toolu_ag1", "spawnDepth": 1,
                          "requestShape": "background", "requestNonInteractive": True, "model": "opus"},
    "a37c067ef6ed1aec1": {"agentType": "general-purpose", "description": "Scan issues", "toolUseId": "toolu_ag2",
                          "spawnDepth": 1, "requestShape": "foreground", "requestNonInteractive": False},
}
SUBAGENT = [
    {"parentUuid": None, "isSidechain": True, "agentId": "a37c067ef6ed1aec1", "type": "user",
     "message": {"role": "user", "content": "Scan."}, "uuid": "s1", "timestamp": "2026-10-01T10:00:07.500Z", "sessionId": SID},
    {"parentUuid": "s1", "isSidechain": True, "agentId": "a37c067ef6ed1aec1", "type": "assistant",
     "message": {"model": "claude-haiku-4-5-20251001", "id": "msg_s2", "type": "message", "role": "assistant",
                 "content": [{"type": "text", "text": "No related issues."}]},
     "uuid": "s2", "timestamp": "2026-10-01T10:00:07.900Z", "sessionId": SID},
]
TASKLIST = [
    {"id": "1", "subject": "Write tests", "description": "", "activeForm": "Writing tests", "status": "completed",
     "blocks": [], "blockedBy": []},
    {"id": "2", "subject": "Ship", "description": "", "activeForm": "Shipping", "status": "in_progress",
     "blocks": [], "blockedBy": []},
]


def main():
    with open(os.path.join(F, "follow_events_transcript.jsonl"), "w") as f:
        for l in transcript():
            f.write(json.dumps(l, ensure_ascii=False, separators=(",", ":")) + "\n")
    with open(os.path.join(F, "follow_events_subagents.json"), "w") as f:
        json.dump(METAS, f, indent=1)
    with open(os.path.join(F, "follow_events_subagent_a37c067ef6ed1aec1.jsonl"), "w") as f:
        for l in SUBAGENT:
            f.write(json.dumps(l, separators=(",", ":")) + "\n")
    with open(os.path.join(F, "follow_events_tasklist.json"), "w") as f:
        json.dump(TASKLIST, f, indent=1)


if __name__ == "__main__":
    main()
