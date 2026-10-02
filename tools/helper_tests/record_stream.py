"""Records a daemon subscribe stream of a throwaway haiku session writing a long reply, as a fixture for the
draft/status tests. Removes the session afterwards, even on failure.

    python3 tools/helper_tests/record_stream.py OUT.jsonl "prompt"
Writes OUT.jsonl (subscribe events, streamTail cleared) and OUT.transcript.jsonl (the session's transcript).
"""

import glob
import json
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from helper_loader import load_helper  # noqa

h = load_helper()
LIVE_DIR = os.path.expanduser("~/tether-exp/live")


def main(out, prompt, cols=100, rows=30):
    os.makedirs(LIVE_DIR, exist_ok=True)
    d = h.daemon_prompt_spec(LIVE_DIR, "Say only: READY", flags=["--model", "haiku"], name="tether fixture recorder",
                             cols=cols, rows=rows)
    short, sid = d["short"], d["sessionId"]
    print("short", short)
    try:
        h.daemon_dispatch(d, timeout_ms=20000)
        tpath = os.path.join(h.CLAUDE_PROJECTS, h.project_dir_name(LIVE_DIR), sid + ".jsonl")
        end = time.time() + 90
        while time.time() < end:
            st = h.read_json(os.path.join(h.JOBS_DIR, short, "state.json"), {}) or {}
            if "MCP" in (st.get("needs") or "") or "MCP" in (st.get("detail") or ""):
                with h.DaemonAttach(short, cols=cols, rows=rows) as a:
                    a.read(1.0)
                    a.send_keys(b"\x1b")
                    a.read(1.0)
            if os.path.exists(tpath) and "READY" in (h.read_text(tpath, "") or ""):
                break
            time.sleep(1)
        time.sleep(2)
        events = []
        gen = h.daemon_subscribe(short, tail=0, timeout=120)
        first = next(gen)
        first["streamTail"] = []
        events.append(first)
        h.daemon_reply(short, prompt)
        seen_active = False
        for ev in gen:
            events.append(ev)
            if ev.get("type") == "state":
                p = ev.get("patch") or {}
                if p.get("tempo") == "active":
                    seen_active = True
                if seen_active and p.get("tempo") in ("idle", "blocked"):
                    break
            if ev.get("type") == "settled":
                break
        time.sleep(1.5)
        with open(out, "w") as f:
            for ev in events:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        shutil.copy(tpath, out.replace(".jsonl", ".transcript.jsonl"))
        print("events", len(events))
    finally:
        try:
            h.daemon_kill(short, evict=True)
        except h.DaemonError as e:
            print("kill:", e)
        time.sleep(1)
        jd = os.path.join(h.JOBS_DIR, short)
        if os.path.isdir(jd):
            shutil.rmtree(jd, ignore_errors=True)
        for p in glob.glob(os.path.join(h.CLAUDE_PROJECTS, h.project_dir_name(LIVE_DIR), sid + "*")):
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            else:
                os.remove(p)
        left = subprocess.run(["claude", "agents", "--json", "--all"], stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, timeout=60).stdout.decode()
        print("left in claude agents:", short in left)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
