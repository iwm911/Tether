#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tether_helper.py -- remote side of the Tether Android app.

Installed by the app (over SFTP) at ~/.tether/bin/tether_helper.py and invoked over SSH exec
channels. Python 3.6+, standard library only. Every command prints JSON on stdout (one object or
array per line); failures print {"error": "..."} and exit with status 1.

State lives in ~/.tether/runs/<runId>/:
    meta.json    run request + spawn info          in.jsonl   stream-json lines fed to claude's stdin
    out.jsonl    claude's stream-json stdout       err.log    claude's stderr
    pid          process-group leader (runner)     exit       claude's exit status (when it ended)
    runner.sh    the detached bash runner          .state.json incremental parse cache (byte offsets)

Sessions (protocol 2.0.0, docs/plans/one-session-daemon.md) -- <sid> is a session id or its 8-hex short:
    sessions [--cwd P] [--limit N] [--before MS]   {"sessions": [Session]} newest first
    watch [--cwd P] [--limit N]  {"snapshot": [Session]}, then {"changed": [Session], "removed": [sid]}, {"hb": ms}
    follow <sid> [--agent ID] [--from OFFSET]      follow events: line (+offset), draft, draftClear, status, state,
                                 peer, subagent, task, todos, caughtUp {offset}
    new                          stdin {cwd, prompt, model?, permissionMode?, images?, trust?, name?} -> Session
    send <sid>                   stdin {text, images?} -> {ok, woke} (a retired session is resumed under its own id)
    key <sid>                    stdin {keys: ["shift-tab"|"esc"|"enter"|"up"|"down"|"left"|"right"|"tab"|"space"|"1".."9"|{text}]}
    answer <sid>                 stdin {decision: allow|allow_always|deny, message?} -> Session
    ask <sid>                    stdin {answers: [{choices: [i…], other}]} -> Session
    interrupt <sid>              Esc -> Session
    stop <sid> | rm <sid>        {"ok": true}
  Errors carry "code": ENODAEMON EAUTH EPROTO EHELD ENOSESSION EUNTRUSTED ETIMEOUT EDAEMON.
  `follow`, `send` and `stop` with a Tether run id ("r…") and `sessions --legacy` / `watch --legacy` are the
  previous protocol, kept until the app no longer calls them.

Commands
    version                      {"version": HELPER_VERSION}
    probe                        host / claude / python facts (+ daemon: {running, proto, version, auth})
    projects                     [ProjectSummary]
    sessions --legacy [--cwd P] [--limit N]   [SessionSummary]
    transcript <sessionId>       raw transcript lines (filtered for size)
    history <runId>              transcript lines of a resumed session written before the run began
    start                        stdin: StartRunRequest JSON  ->  RunInfo
    runs                         [RunInfo]
    watch --legacy               a [RunInfo] line on every change, {"hb": ms} every 15 s
    follow <runId> <offset>      raw out.jsonl bytes from offset, complete lines only, until the reader goes
    send <runId>                 stdin: JSONL appended to in.jsonl (flock)
    input <runId>                in.jsonl lines (image payloads stripped)
    stop <runId> | delete <runId>
    ls [path]                    DirListing
    commands [--cwd P]           {commands: [{name, description, argumentHint}]} from claude's initialize reply
  Claude Code's own background agents (`claude --bg`, listed by `claude agents`):
    native-list                  [NativeAgent]  (agents --json --all merged with ~/.claude/jobs/<id>/state.json)
                                 Terminal sessions (kind "interactive") are listed too, as term-<pid>: watch-only;
                                 native-reply continues one as a background copy, the rest refuse them.
    native-start                 stdin {cwd, prompt, model?, permissionMode?, trust, mcp?} -> NativeAgent
                                   | {error:"untrusted"} | {error:"mcp", servers} (mcp: "enable" | "skip" answers it)
    native-reply <id>            stdin {message, model?, permissionMode?}: stop (if running) + `claude --bg --resume <session> [flags] msg`
    native-send <id>             stdin {message}: type into the RUNNING agent via `claude attach` (queues while busy)
    native-answer <id>           stdin {decision: allow|deny}: answer the agent's permission prompt
    native-interrupt <id>        press Esc in the agent (interrupts the current turn, keeps the agent)
    native-mode <id>             stdin {mode}: Shift+Tab the RUNNING agent to that permission mode
    native-ask <id>              stdin {answers:[{choices:[i…], other:str|null}]}: answer an AskUserQuestion prompt
    native-question <id>         the pending AskUserQuestion with multiSelect flags (peeks the TUI once, cached)
    native-stop <id> | native-rm <id>
    native-timeline <id>         [{at, state, detail, text}] from timeline.jsonl
    native-logs <id>             {text}: `claude logs <id>` with ANSI stripped
    native-follow <id>           the agent's transcript lines (as `transcript`), then new ones as they are written
  `watch` also emits {"native": [NativeAgent]} lines whenever the native list changes.
  The Claude Code daemon (control socket, proto 1):
    daemon-status                {running, proto, version, auth: ok|needs_login|unknown, pid?, error?, code?}
Errors from daemon calls carry "code": ENODAEMON | EAUTH | EPROTO | ENOSESSION | ETIMEOUT | EDAEMON ...
Global option: --claude PATH (explicit claude binary).
"""

import errno
import fcntl
import glob
import json
import os
import platform
import random
import select
import re
import shlex
import signal
import socket
import string
import subprocess
import sys
import time

HELPER_VERSION = "2.0.0"

HOME = os.path.expanduser("~")
TETHER_DIR = os.path.join(HOME, ".tether")
RUNS_DIR = os.path.join(TETHER_DIR, "runs")
CACHE_DIR = os.path.join(TETHER_DIR, "cache")
CLAUDE_PROJECTS = os.path.join(HOME, ".claude", "projects")

STATE_VERSION = 4
HEAD_BYTES = 1024 * 1024
TAIL_BYTES = 256 * 1024
RECENT_MS = 60 * 1000
TRANSCRIPT_MAX_BYTES = 12 * 1024 * 1024
TRANSCRIPT_STRING_CAP = 24 * 1024
LAST_TEXT_CAP = 200

SESSION_ID_RE = re.compile(r"^[0-9A-Za-z_-]{8,}$")
RUN_ID_RE = re.compile(r"^[0-9A-Za-z_-]{4,64}$")
META_PREFIXES = ("<system-reminder>", "<local-command", "Caveat:", "<command-message>", "<bash-")


class HelperError(Exception):
    pass


# ───────────────────────────────────────── utilities ─────────────────────────────────────────

def now_ms():
    return int(time.time() * 1000)


def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))
    sys.stdout.write("\n")
    sys.stdout.flush()


def mtime_ms(path):
    try:
        return int(os.stat(path).st_mtime * 1000)
    except OSError:
        return 0


def file_size(path):
    try:
        return os.stat(path).st_size
    except OSError:
        return -1


def read_text(path, default=None):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except (OSError, IOError):
        return default


def read_json(path, default=None):
    t = read_text(path)
    if t is None:
        return default
    try:
        return json.loads(t)
    except ValueError:
        return default


def write_json_atomic(path, obj):
    tmp = "%s.tmp%d" % (path, os.getpid())
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def ensure_dir(path, mode=0o700):
    try:
        os.makedirs(path, mode)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def iso_to_ms(s):
    """'2026-09-25T19:37:12.100Z' -> epoch ms (None when unparseable)."""
    if not isinstance(s, str) or len(s) < 19:
        return None
    try:
        import calendar
        base = time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S")
        ms = 0
        rest = s[19:]
        if rest.startswith("."):
            digits = ""
            for ch in rest[1:]:
                if ch.isdigit():
                    digits += ch
                else:
                    break
            if digits:
                ms = int((digits + "000")[:3])
        return calendar.timegm(base) * 1000 + ms
    except (ValueError, OverflowError):
        return None


def iter_lines_bytes(data):
    """Yields complete lines of a bytes buffer (no trailing newline)."""
    start = 0
    n = len(data)
    while start < n:
        nl = data.find(b"\n", start)
        if nl < 0:
            yield data[start:]
            return
        yield data[start:nl]
        start = nl + 1


def parse_line(raw):
    if not raw:
        return None
    try:
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", "replace")
        raw = raw.strip()
        if not raw or raw[0] != "{":
            return None
        v = json.loads(raw)
        return v if isinstance(v, dict) else None
    except ValueError:
        return None


def read_head(path, limit=HEAD_BYTES):
    try:
        with open(path, "rb") as f:
            return f.read(limit)
    except (OSError, IOError):
        return b""


def read_tail(path, limit=TAIL_BYTES):
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            start = max(0, size - limit)
            f.seek(start)
            data = f.read()
        if start > 0:
            nl = data.find(b"\n")
            data = data[nl + 1:] if nl >= 0 else b""
        return data
    except (OSError, IOError):
        return b""


def pid_alive(pid, start_tag=None):
    if not pid or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError as e:
        if e.errno == errno.EPERM:
            return True
        return False
    stat = read_text("/proc/%d/stat" % pid)
    if stat:
        try:
            after = stat[stat.rindex(")") + 2:].split()
            if after[0] == "Z":
                return False
            if start_tag and after[19] != str(start_tag):
                return False  # pid was recycled
        except (ValueError, IndexError):
            pass
    return True


def proc_start_tag(pid):
    stat = read_text("/proc/%d/stat" % pid)
    if not stat:
        return None
    try:
        return stat[stat.rindex(")") + 2:].split()[19]
    except (ValueError, IndexError):
        return None


def trim(s, cap):
    if s is None:
        return None
    s = s.strip()
    if len(s) > cap:
        return s[:cap - 1].rstrip() + "…"
    return s


def one_line(s, cap=160):
    if not isinstance(s, str):
        return None
    s = " ".join(s.split())
    return trim(s, cap)


def run_cmd(argv, timeout=15, env=None, shell=False):
    try:
        p = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env, shell=shell)
        try:
            out, err = p.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            p.communicate()
            return None, None, -1
        return out.decode("utf-8", "replace"), err.decode("utf-8", "replace"), p.returncode
    except OSError:
        return None, None, -1


# ───────────────────────────────────────── claude resolution ─────────────────────────────────────────

def is_exec(path):
    return bool(path) and os.path.isfile(path) and os.access(path, os.X_OK)


def login_shell_env():
    """(claude path from a login shell, login PATH). Non-interactive SSH skips the user's rc files."""
    shell = os.environ.get("SHELL") or "/bin/bash"
    if not is_exec(shell):
        shell = "/bin/bash" if is_exec("/bin/bash") else "/bin/sh"
    script = 'printf "__TETHER_PATH=%s\\n" "$PATH"; printf "__TETHER_CLAUDE=%s\\n" "$(command -v claude 2>/dev/null)"'
    out, _err, _rc = run_cmd([shell, "-lc", script], timeout=10)
    claude = None
    path = None
    if out:
        for line in out.splitlines():
            if line.startswith("__TETHER_PATH="):
                path = line[len("__TETHER_PATH="):].strip() or None
            elif line.startswith("__TETHER_CLAUDE="):
                c = line[len("__TETHER_CLAUDE="):].strip()
                if c.startswith("/") and is_exec(c):
                    claude = c
    return claude, path


def resolve_claude(explicit=None):
    """Returns (path or None, login PATH or None)."""
    cache_path = os.path.join(CACHE_DIR, "claude.json")
    if explicit:
        p = os.path.expanduser(explicit)
        if is_exec(p):
            cached = read_json(cache_path, {}) or {}
            return p, cached.get("loginPath")
        raise HelperError("The configured Claude path %s is not an executable file." % explicit)
    cached = read_json(cache_path, None)
    if cached and is_exec(cached.get("path")) and now_ms() - cached.get("at", 0) < 10 * 60 * 1000:
        return cached["path"], cached.get("loginPath")
    found, login_path = login_shell_env()
    if not found:
        which = None
        for d in (os.environ.get("PATH") or "").split(os.pathsep):
            c = os.path.join(d, "claude")
            if d and is_exec(c):
                which = c
                break
        found = which
    if not found:
        for c in ("~/.local/bin/claude", "~/.claude/local/claude", "~/.npm-global/bin/claude",
                  "/usr/local/bin/claude", "/opt/homebrew/bin/claude", "/usr/bin/claude"):
            c = os.path.expanduser(c)
            if is_exec(c):
                found = c
                break
    if found:
        try:
            ensure_dir(CACHE_DIR)
            write_json_atomic(cache_path, {"path": found, "loginPath": login_path, "at": now_ms()})
        except (OSError, IOError):
            pass
    return found, login_path


def claude_env(claude_path, login_path):
    env = dict(os.environ)
    parts = []
    if claude_path:
        parts.append(os.path.dirname(claude_path))
    if login_path:
        parts.extend(login_path.split(os.pathsep))
    parts.extend((env.get("PATH") or "/usr/local/bin:/usr/bin:/bin").split(os.pathsep))
    parts.extend([os.path.expanduser("~/.local/bin"), "/usr/local/bin", "/usr/bin", "/bin"])
    seen = []
    for p in parts:
        if p and p not in seen:
            seen.append(p)
    env["PATH"] = os.pathsep.join(seen)
    return env


def claude_version(path, login_path):
    out, _err, rc = run_cmd([path, "--version"], timeout=20, env=claude_env(path, login_path))
    if rc != 0 or not out:
        return None
    first = out.strip().split()
    return first[0] if first else None


# ───────────────────────────────────────── probe ─────────────────────────────────────────

def cmd_probe(opts):
    problem = None
    path = None
    version = None
    try:
        path, login_path = resolve_claude(opts.get("claude"))
        if path:
            version = claude_version(path, login_path)
            if not version:
                problem = "Claude Code at %s did not report a version." % path
        else:
            problem = "Claude Code was not found on this machine. Install it, or set its path in the machine settings."
    except HelperError as e:
        problem = str(e)
    uname = platform.uname()
    os_name = uname[0]
    if os_name == "Linux":
        rel = read_text("/etc/os-release", "") or ""
        m = re.search(r'^PRETTY_NAME="?([^"\n]+)"?', rel, re.M)
        if m:
            os_name = m.group(1)
    elif os_name == "Darwin":
        out, _e, rc = run_cmd(["sw_vers", "-productVersion"], timeout=5)
        os_name = "macOS " + out.strip() if rc == 0 and out else "macOS"
    emit({
        "hostname": socket.gethostname(),
        "os": os_name,
        "arch": uname[4],
        "home": HOME,
        "claudePath": path,
        "claudeVersion": version,
        "pythonVersion": platform.python_version(),
        "helperVersion": HELPER_VERSION,
        "helperReady": True,
        "problem": problem,
        "plan": claude_plan(),
        "daemon": dict((k, v) for k, v in daemon_status().items() if k in ("running", "proto", "version", "auth")),
    })


PLAN_NAMES = {"claude_pro": "Claude Pro", "claude_max": "Claude Max", "claude_team": "Claude Team",
              "claude_enterprise": "Claude Enterprise"}


def claude_plan():
    """The Claude subscription this machine's Claude Code signs in with, or None for API-key billing.
    Subscription users are not charged per token: total_cost_usd is only an API-equivalent estimate."""
    data = read_json(CLAUDE_JSON, {}) or {}
    acct = data.get("oauthAccount") if isinstance(data.get("oauthAccount"), dict) else None
    if not acct or os.environ.get("ANTHROPIC_API_KEY"):
        return None
    billing = str(acct.get("billingType") or "")
    org = str(acct.get("organizationType") or "")
    if "subscription" not in billing and org not in PLAN_NAMES:
        return None
    return PLAN_NAMES.get(org) or "Claude subscription"


# ───────────────────────────────────────── transcripts: sessions & projects ─────────────────────────────────────────

def is_meta_text(t):
    if not isinstance(t, str):
        return True
    s = t.lstrip()
    if not s:
        return True
    return s.startswith(META_PREFIXES)


def prompt_text(msg_obj):
    """Real user prompt text of a transcript 'user' line, or None."""
    if msg_obj.get("isMeta") or msg_obj.get("isSidechain") or msg_obj.get("isCompactSummary"):
        return None
    message = msg_obj.get("message") or {}
    content = message.get("content")
    text = None
    if isinstance(content, str):
        text = content
    elif isinstance(content, list):
        parts = []
        for b in content:
            if not isinstance(b, dict):
                continue
            if b.get("type") == "tool_result":
                return None
            if b.get("type") == "text" and isinstance(b.get("text"), str):
                parts.append(b["text"])
        text = "\n".join(parts) if parts else None
    if not text:
        return None
    s = text.strip()
    m = re.match(r"<command-name>\s*(/?[^<\s]+)\s*</command-name>", s)
    if m or s.startswith("<command-message>"):
        m = m or re.search(r"<command-name>\s*(/?[^<\s]+)\s*</command-name>", s)
        if not m:
            return None
        name = m.group(1)
        if not name.startswith("/"):
            name = "/" + name
        a = re.search(r"<command-args>(.*?)</command-args>", s, re.S)
        args = a.group(1).strip() if a else ""
        return (name + " " + args).strip()
    if is_meta_text(s) or s.startswith("[Request interrupted"):
        return None
    return s


def scan_head(path):
    """cwd, gitBranch, first prompt, first timestamp from the head of a transcript."""
    info = {"cwd": None, "gitBranch": None, "firstPrompt": None, "firstAt": None}
    data = read_head(path)
    for raw in iter_lines_bytes(data):
        need_cwd = info["cwd"] is None
        need_prompt = info["firstPrompt"] is None
        if not need_cwd and not need_prompt:
            break
        if need_cwd and b'"cwd"' not in raw and not need_prompt:
            continue
        if not need_cwd and b'"type":"user"' not in raw:
            continue
        o = parse_line(raw)
        if not o:
            continue
        if need_cwd and isinstance(o.get("cwd"), str):
            info["cwd"] = o["cwd"]
            info["gitBranch"] = o.get("gitBranch") if o.get("gitBranch") not in (None, "", "HEAD") else None
            info["firstAt"] = iso_to_ms(o.get("timestamp"))
        if need_prompt and o.get("type") == "user":
            p = prompt_text(o)
            if p:
                info["firstPrompt"] = p
    return info


def scan_titles(data, into):
    """Walks lines, keeping the LAST custom/ai title and last prompt seen."""
    for raw in iter_lines_bytes(data):
        if b'"custom-title"' in raw or b'"ai-title"' in raw or b'"last-prompt"' in raw or b'"summary"' in raw:
            o = parse_line(raw)
            if not o:
                continue
            t = o.get("type")
            if t == "custom-title" and o.get("customTitle"):
                into["customTitle"] = o["customTitle"]
            elif t == "ai-title" and o.get("aiTitle"):
                into["aiTitle"] = o["aiTitle"]
            elif t == "last-prompt" and o.get("lastPrompt"):
                into["lastPrompt"] = o["lastPrompt"]
            elif t == "summary" and o.get("summary"):
                into["summary"] = o["summary"]
        elif b'"gitBranch"' in raw:
            m = re.search(rb'"gitBranch":"([^"]*)"', raw)
            if m and m.group(1) and m.group(1) != b"HEAD":
                into["gitBranch"] = m.group(1).decode("utf-8", "replace")


def count_messages(path, size):
    """Number of user+assistant lines. Exact up to 8 MB, extrapolated from the head above that."""
    try:
        with open(path, "rb") as f:
            if size <= 8 * 1024 * 1024:
                data = f.read()
                return data.count(b'"type":"user"') + data.count(b'"type":"assistant"')
            data = f.read(2 * 1024 * 1024)
    except (OSError, IOError):
        return 0
    n = data.count(b'"type":"user"') + data.count(b'"type":"assistant"')
    return int(n * (float(size) / max(1, len(data))))


class SessionIndex(object):
    """Per-transcript facts cached by (size, mtime) in ~/.tether/cache/sessions.json."""

    def __init__(self, name="sessions.json", count=True):
        self.path = os.path.join(CACHE_DIR, name)
        self.count = count  # False: skip message counts (they read up to 8 MB; `watch` re-checks every second)
        self.data = read_json(self.path, {}) or {}
        if self.data.get("v") != STATE_VERSION:
            self.data = {"v": STATE_VERSION, "files": {}}
        self.files = self.data.setdefault("files", {})
        self.dirty = False

    def info(self, path, st):
        key = path
        cur = self.files.get(key)
        size = st.st_size
        mt = int(st.st_mtime * 1000)
        if cur and cur.get("size") == size and cur.get("mtime") == mt:
            return cur
        if cur and cur.get("size", 0) <= size and cur.get("cwd") is not None:
            head = {"cwd": cur.get("cwd"), "gitBranch": cur.get("gitBranch"),
                    "firstPrompt": cur.get("firstPrompt"), "firstAt": cur.get("firstAt")}
            if head["firstPrompt"] is None:
                head.update({k: v for k, v in scan_head(path).items() if v is not None})
        else:
            head = scan_head(path)
        titles = {}
        if cur and cur.get("size", 0) <= size:
            for k in ("customTitle", "aiTitle", "lastPrompt", "summary"):
                if cur.get(k):
                    titles[k] = cur[k]
        scan_titles(read_tail(path), titles)
        if not titles.get("customTitle") and not titles.get("aiTitle") and size > TAIL_BYTES:
            scan_titles(read_head(path, TAIL_BYTES), titles)
        info = {
            "size": size, "mtime": mt,
            "cwd": head.get("cwd"), "gitBranch": titles.get("gitBranch") or head.get("gitBranch"),
            "firstPrompt": head.get("firstPrompt"), "firstAt": head.get("firstAt"),
            "customTitle": titles.get("customTitle"), "aiTitle": titles.get("aiTitle"),
            "lastPrompt": titles.get("lastPrompt"), "summary": titles.get("summary"),
            "messages": count_messages(path, size) if self.count else 0,
        }
        self.files[key] = info
        self.dirty = True
        return info

    def save(self):
        if not self.dirty:
            return
        live = set()
        for k in list(self.files.keys()):
            if os.path.exists(k):
                live.add(k)
            else:
                del self.files[k]
        try:
            ensure_dir(CACHE_DIR)
            write_json_atomic(self.path, self.data)
        except (OSError, IOError):
            pass


def project_dir_name(cwd):
    return re.sub(r"[^A-Za-z0-9]", "-", cwd)


def list_session_files(project_dir):
    out = []
    try:
        names = os.listdir(project_dir)
    except OSError:
        return out
    for n in names:
        if not n.endswith(".jsonl"):
            continue
        stem = n[:-6]
        if "." in stem or not SESSION_ID_RE.match(stem):
            continue
        p = os.path.join(project_dir, n)
        try:
            st = os.stat(p)
        except OSError:
            continue
        if st.st_size == 0:
            continue
        out.append((p, stem, st))
    return out


def decode_dir_name(name):
    return "/" + name.lstrip("-").replace("-", "/") if name.startswith("-") else name


def live_sessions():
    """sessionId -> runId for live Tether runs."""
    res = {}
    for run_id, d in iter_run_dirs():
        st = read_json(os.path.join(d, ".state.json"), {}) or {}
        meta = read_json(os.path.join(d, "meta.json"), {}) or {}
        sid = st.get("sessionId") or meta.get("resumeSessionId")
        if sid and pid_alive(read_pid(d), meta.get("pidStart")):
            res[sid] = run_id
    return res


def session_title(info):
    for k in ("customTitle", "aiTitle", "summary"):
        if info.get(k):
            return one_line(info[k], 120)
    for k in ("firstPrompt", "lastPrompt"):
        if info.get(k):
            return one_line(info[k], 120)
    return "Untitled session"


def cmd_sessions(opts):
    limit = int(opts.get("limit") or 60)
    want_cwd = opts.get("cwd")
    if want_cwd:
        want_cwd = os.path.abspath(os.path.expanduser(want_cwd))
    files = []
    if not os.path.isdir(CLAUDE_PROJECTS):
        emit([])
        return
    if want_cwd:
        d = os.path.join(CLAUDE_PROJECTS, project_dir_name(want_cwd))
        if os.path.isdir(d):
            files.extend(list_session_files(d))
    else:
        for name in os.listdir(CLAUDE_PROJECTS):
            d = os.path.join(CLAUDE_PROJECTS, name)
            if os.path.isdir(d):
                files.extend(list_session_files(d))
    files.sort(key=lambda t: t[2].st_mtime, reverse=True)
    idx = SessionIndex()
    live = live_sessions()
    now = now_ms()
    out = []
    for path, sid, st in files:
        if len(out) >= limit:
            break
        info = idx.info(path, st)
        cwd = info.get("cwd") or decode_dir_name(os.path.basename(os.path.dirname(path)))
        if want_cwd and os.path.normpath(cwd) != os.path.normpath(want_cwd):
            continue
        mt = int(st.st_mtime * 1000)
        out.append({
            "sessionId": sid,
            "cwd": cwd,
            "title": session_title(info),
            "lastPrompt": one_line(info.get("lastPrompt"), 200),
            "updatedAt": mt,
            "messageCount": info.get("messages") or 0,
            "sizeBytes": st.st_size,
            "gitBranch": info.get("gitBranch"),
            "recentlyActive": (now - mt) < RECENT_MS and sid not in live,
            "liveRunId": live.get(sid),
        })
    idx.save()
    emit(out)


def cmd_projects(opts):
    if not os.path.isdir(CLAUDE_PROJECTS):
        emit([])
        return
    idx = SessionIndex()
    projects = {}
    for name in os.listdir(CLAUDE_PROJECTS):
        d = os.path.join(CLAUDE_PROJECTS, name)
        if not os.path.isdir(d):
            continue
        files = list_session_files(d)
        if not files:
            continue
        files.sort(key=lambda t: t[2].st_mtime, reverse=True)
        cwd = None
        branch = None
        # The real cwd: first transcript (newest first, max 4 tried) with a "cwd" line.
        for path, _sid, st in files[:4]:
            info = idx.info(path, st)
            if info.get("cwd"):
                cwd = info["cwd"]
                branch = info.get("gitBranch")
                break
        if not cwd:
            cwd = decode_dir_name(name)
        last = int(files[0][2].st_mtime * 1000)
        p = projects.get(cwd)
        if p:
            p["sessionCount"] += len(files)
            if last > p["lastActiveAt"]:
                p["lastActiveAt"] = last
                p["gitBranch"] = branch or p["gitBranch"]
        else:
            projects[cwd] = {
                "cwd": cwd,
                "sessionCount": len(files),
                "lastActiveAt": last,
                "exists": os.path.isdir(cwd),
                "gitBranch": branch,
            }
    idx.save()
    out = sorted(projects.values(), key=lambda p: p["lastActiveAt"], reverse=True)
    emit(out)


def find_transcript(session_id):
    if not SESSION_ID_RE.match(session_id or ""):
        raise HelperError("Invalid session id.")
    hits = glob.glob(os.path.join(glob.escape(CLAUDE_PROJECTS), "*", glob.escape(session_id) + ".jsonl"))
    if not hits:
        return None
    hits.sort(key=lambda p: file_size(p), reverse=True)
    return hits[0]


KEEP_TYPES = ("user", "assistant", "system", "custom-title", "ai-title", "summary", "last-prompt")


def cap_strings(v, cap=TRANSCRIPT_STRING_CAP):
    if isinstance(v, str):
        if len(v) > cap:
            return v[:cap] + "\n… (%d more characters)" % (len(v) - cap)
        return v
    if isinstance(v, list):
        return [cap_strings(x, cap) for x in v]
    if isinstance(v, dict):
        return {k: cap_strings(x, cap) for k, x in v.items()}
    return v


def slim_line(o):
    """Removes bulk the phone never renders: signatures, originalFile, huge tool payloads."""
    msg = o.get("message")
    if isinstance(msg, dict) and isinstance(msg.get("content"), list):
        for b in msg["content"]:
            if isinstance(b, dict):
                if b.get("type") == "thinking":
                    b.pop("signature", None)
                elif b.get("type") == "image":
                    src = b.get("source")
                    if isinstance(src, dict) and "data" in src:
                        src["data"] = ""
                        src["tetherStripped"] = True
    for k in ("toolUseResult", "tool_use_result"):
        r = o.get(k)
        if isinstance(r, dict):
            r.pop("originalFile", None)
            o[k] = cap_strings(r)
    if isinstance(msg, dict):
        o["message"] = cap_strings(msg)
    return o


def transcript_line_out(raw, before_ms=None, sidechain_ok=False):
    """One raw transcript line -> the (slimmed) JSON line the app renders, or None to skip it. sidechain_ok keeps
    sidechain lines (a subagent's own transcript is all sidechain)."""
    if not sidechain_ok and b'"isSidechain":true' in raw:
        return None
    o = parse_line(raw)
    if not o:
        return None
    t = o.get("type")
    if t not in KEEP_TYPES or (o.get("isSidechain") and not sidechain_ok):
        return None
    if before_ms is not None and t in ("user", "assistant", "system"):
        ts = iso_to_ms(o.get("timestamp"))
        if ts is not None and ts >= before_ms:
            return None
    if len(raw) > 64 * 1024:
        o = slim_line(o)
    else:
        msg = o.get("message")
        if isinstance(msg, dict) and isinstance(msg.get("content"), list):
            for b in msg["content"]:
                if isinstance(b, dict) and b.get("type") == "thinking":
                    b.pop("signature", None)
    return json.dumps(o, ensure_ascii=False, separators=(",", ":"))


def transcript_lines(path, before_ms=None):
    lines = []
    total = 0
    with open(path, "rb") as f:
        for raw in f:
            s = transcript_line_out(raw, before_ms)
            if s is None:
                continue
            lines.append(s)
            total += len(s) + 1
    dropped = 0
    while total > TRANSCRIPT_MAX_BYTES and len(lines) > 1:
        total -= len(lines[0]) + 1
        lines.pop(0)
        dropped += 1
    if dropped:
        lines.insert(0, json.dumps({"type": "tether-truncated", "droppedLines": dropped}))
    return lines


def cmd_transcript(opts, session_id):
    path = find_transcript(session_id)
    if not path:
        raise HelperError("No transcript found for session %s." % session_id)
    out = sys.stdout
    for s in transcript_lines(path):
        out.write(s)
        out.write("\n")
    out.flush()


def cmd_history(opts, run_id):
    d = run_dir(run_id)
    meta = read_json(os.path.join(d, "meta.json"), {}) or {}
    sid = meta.get("resumeSessionId")
    if not sid:
        return
    path = find_transcript(sid)
    if not path:
        return
    stop_at = meta.get("resumeAt")
    for s in transcript_lines(path, before_ms=meta.get("startedAt")):
        sys.stdout.write(s)
        sys.stdout.write("\n")
        if stop_at and ('"uuid":"%s"' % stop_at) in s:
            break  # a branch: history ends at the fork point
    sys.stdout.flush()


# ───────────────────────────────────────── runs ─────────────────────────────────────────

def run_dir(run_id):
    if not RUN_ID_RE.match(run_id or ""):
        raise HelperError("Invalid run id.")
    d = os.path.join(RUNS_DIR, run_id)
    if not os.path.isdir(d):
        raise HelperError("Run %s does not exist on this machine." % run_id)
    return d


def iter_run_dirs():
    try:
        names = os.listdir(RUNS_DIR)
    except OSError:
        return
    for n in names:
        d = os.path.join(RUNS_DIR, n)
        if RUN_ID_RE.match(n) and os.path.isfile(os.path.join(d, "meta.json")):
            yield n, d


def read_pid(d):
    t = read_text(os.path.join(d, "pid"))
    try:
        return int(t.strip()) if t else None
    except ValueError:
        return None


def new_run_id():
    t = int(time.time())
    alphabet = string.digits + string.ascii_lowercase
    s = ""
    while t:
        t, r = divmod(t, 36)
        s = alphabet[r] + s
    return "r" + s + "".join(random.choice(alphabet) for _ in range(5))


def rel_path(p, cwd):
    if not isinstance(p, str):
        return None
    if cwd and (p == cwd or p.startswith(cwd.rstrip("/") + "/")):
        r = os.path.relpath(p, cwd)
        return r if r != "." else os.path.basename(p)
    if p.startswith(HOME + "/"):
        return "~" + p[len(HOME):]
    return p


def permission_summary(tool, inp, cwd):
    if not isinstance(inp, dict):
        return tool
    if tool == "Bash":
        return one_line(inp.get("command"), 200) or "a shell command"
    if tool in ("Edit", "Write", "Read", "MultiEdit", "NotebookEdit", "NotebookRead"):
        return rel_path(inp.get("file_path") or inp.get("notebook_path") or inp.get("path"), cwd) or tool
    if tool == "WebFetch":
        url = inp.get("url") or ""
        m = re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)", url)
        return m.group(1) if m else (one_line(url, 120) or tool)
    if tool == "WebSearch":
        return one_line(inp.get("query"), 160) or tool
    if tool in ("Grep", "Glob"):
        pat = one_line(inp.get("pattern"), 120) or ""
        where = rel_path(inp.get("path"), cwd)
        return (pat + (" in " + where if where else "")) or tool
    if tool in ("Task", "Agent"):
        return one_line(inp.get("description") or inp.get("prompt"), 160) or tool
    for v in inp.values():
        if isinstance(v, str) and v.strip():
            return one_line(v, 160)
    return tool


def new_state():
    return {
        "v": STATE_VERSION, "outOff": 0, "inOff": 0,
        "sessionId": None, "model": None, "permissionMode": None,
        "lastText": None, "cost": 0.0, "turns": 0, "resultError": None,
        "initResp": False, "echoes": 0, "sent": 0, "lastKind": None,
        "requests": {}, "closed": [], "lastResultAt": 0, "workingSince": None,
        "bgTasks": [],
    }


def consume_out(state, data, cwd):
    closed = set(state["closed"])
    for raw in iter_lines_bytes(data):
        o = parse_line(raw)
        if not o:
            continue
        t = o.get("type")
        if t == "system":
            st = o.get("subtype")
            if st == "init":
                state["sessionId"] = o.get("session_id") or state["sessionId"]
                state["model"] = o.get("model") or state["model"]
                state["permissionMode"] = o.get("permissionMode") or state["permissionMode"]
                state["lastKind"] = "activity"
            elif st == "status":
                if o.get("permissionMode"):
                    state["permissionMode"] = o["permissionMode"]
                if o.get("status"):
                    state["lastKind"] = "activity"
            elif st in ("task_started", "task_updated", "task_notification", "task_progress"):
                # Background shells / subagents outlive the turn: tracked apart from turn activity.
                tid = o.get("task_id")
                patch = o.get("patch") if isinstance(o.get("patch"), dict) else {}
                if not tid:
                    pass
                elif st == "task_started":
                    if not o.get("ambient") and tid not in state["bgTasks"]:
                        state["bgTasks"] = (state["bgTasks"] + [tid])[-100:]
                elif st == "task_notification" or patch.get("status") not in (None, "running", "pending"):
                    state["bgTasks"] = [x for x in state["bgTasks"] if x != tid]
            else:
                state["lastKind"] = "activity"
        elif t == "assistant":
            state["lastKind"] = "activity"
            if o.get("parent_tool_use_id"):
                continue
            msg = o.get("message") or {}
            for b in msg.get("content") or []:
                if isinstance(b, dict) and b.get("type") == "text" and (b.get("text") or "").strip():
                    state["lastText"] = trim(b["text"], LAST_TEXT_CAP)
            if msg.get("model") and not msg.get("model", "").startswith("<"):
                state["model"] = msg["model"]
        elif t == "user":
            state["lastKind"] = "activity"
            if o.get("parent_tool_use_id"):
                continue
            content = (o.get("message") or {}).get("content")
            is_text = isinstance(content, str) or (
                isinstance(content, list) and any(isinstance(b, dict) and b.get("type") in ("text", "image") for b in content)
                and not any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content))
            if is_text and o.get("isReplay") is True:
                state["echoes"] += 1
                if state.get("workingSince") is None:
                    state["workingSince"] = iso_to_ms(o.get("timestamp")) or now_ms()
        elif t == "stream_event":
            state["lastKind"] = "activity"
        elif t == "result":
            state["lastKind"] = "result"
            # A resume with no prompt emits an empty bookkeeping result (num_turns 0): not a turn.
            if o.get("num_turns") or (isinstance(o.get("result"), str) and o["result"]) or o.get("is_error"):
                state["turns"] += 1
            if isinstance(o.get("total_cost_usd"), (int, float)):
                state["cost"] = float(o["total_cost_usd"])
            if o.get("session_id"):
                state["sessionId"] = o["session_id"]
            interrupted = str(o.get("terminal_reason") or "").startswith("aborted") or o.get("terminal_reason") == "interrupted"
            if interrupted:
                state["resultError"] = None
            elif o.get("is_error") or (o.get("subtype") and o.get("subtype") != "success"):
                err = o.get("result") if isinstance(o.get("result"), str) and o.get("result") else None
                if not err:
                    errs = o.get("errors")
                    err = "; ".join(str(e) for e in errs) if isinstance(errs, list) and errs else o.get("subtype")
                state["resultError"] = trim(err, 300)
            else:
                state["resultError"] = None
                if isinstance(o.get("result"), str) and o["result"].strip():
                    state["lastText"] = trim(o["result"], LAST_TEXT_CAP)
            # A finished turn leaves no live permission prompt behind.
            for rid in list(state["requests"].keys()):
                closed.add(rid)
            state["requests"] = {}
            state["lastResultAt"] = now_ms()
            state["workingSince"] = None
        elif t == "control_request":
            req = o.get("request") or {}
            rid = o.get("request_id")
            if req.get("subtype") == "can_use_tool" and rid and rid not in closed:
                tool = req.get("tool_name") or req.get("display_name") or "Tool"
                inp = req.get("input")
                ij = json.dumps(inp, ensure_ascii=False, separators=(",", ":")) if inp is not None else None
                state["requests"][rid] = {
                    "requestId": rid, "toolName": tool,
                    "summary": permission_summary(tool, inp, cwd),
                    "inputJson": ij if ij is None or len(ij) <= 512 * 1024 else None,
                }
        elif t == "control_cancel_request":
            rid = o.get("request_id")
            if rid:
                closed.add(rid)
                state["requests"].pop(rid, None)
        elif t == "control_response":
            resp = o.get("response") or {}
            if isinstance(resp.get("response"), dict) and "commands" in resp["response"]:
                state["initResp"] = True
                m = resp["response"].get("current_permission_mode")
                if m and not state["permissionMode"]:
                    state["permissionMode"] = m
    state["closed"] = list(closed)[-400:]


def consume_in(state, data):
    closed = set(state["closed"])
    for raw in iter_lines_bytes(data):
        if b'"type":"user"' in raw or b'"type": "user"' in raw:
            o = parse_line(raw)
            if o and o.get("type") == "user":
                state["sent"] += 1
                if state.get("workingSince") is None and state["lastKind"] != "activity":
                    state["workingSince"] = now_ms()
        elif b"control_response" in raw:
            o = parse_line(raw)
            if o and o.get("type") == "control_response":
                rid = (o.get("response") or {}).get("request_id")
                if rid:
                    closed.add(rid)
                    state["requests"].pop(rid, None)
    state["closed"] = list(closed)[-400:]


def read_complete(path, offset):
    """Bytes from offset to the last complete line. Returns (data, new_offset)."""
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            if size < offset:
                return None, 0  # truncated / replaced: start over
            if size == offset:
                return b"", offset
            f.seek(offset)
            data = f.read(size - offset)
    except (OSError, IOError):
        return b"", offset
    nl = data.rfind(b"\n")
    if nl < 0:
        return b"", offset
    return data[:nl + 1], offset + nl + 1


def load_state(d, cwd):
    sp = os.path.join(d, ".state.json")
    state = read_json(sp, None)
    if not state or state.get("v") != STATE_VERSION:
        state = new_state()
    changed = False
    for fname, key, fn in (("out.jsonl", "outOff", lambda s, b: consume_out(s, b, cwd)),
                           ("in.jsonl", "inOff", consume_in)):
        data, off = read_complete(os.path.join(d, fname), state[key])
        if data is None:
            state = new_state()
            return load_state_fresh(d, cwd, sp)
        if data:
            fn(state, data)
            state[key] = off
            changed = True
    if changed:
        try:
            write_json_atomic(sp, state)
        except (OSError, IOError):
            pass
    return state


def load_state_fresh(d, cwd, sp):
    try:
        os.remove(sp)
    except OSError:
        pass
    state = new_state()
    for fname, key in (("out.jsonl", "outOff"), ("in.jsonl", "inOff")):
        data, off = read_complete(os.path.join(d, fname), 0)
        if data:
            if key == "outOff":
                consume_out(state, data, cwd)
            else:
                consume_in(state, data)
            state[key] = off
    try:
        write_json_atomic(sp, state)
    except (OSError, IOError):
        pass
    return state


def err_tail(d, cap=400):
    data = read_tail(os.path.join(d, "err.log"), 4096)
    if not data:
        return None
    text = data.decode("utf-8", "replace").strip()
    lines = [l for l in text.splitlines() if l.strip()]
    return trim("\n".join(lines[-4:]), cap) if lines else None


def run_info(run_id, d):
    meta = read_json(os.path.join(d, "meta.json"), {}) or {}
    cwd = meta.get("cwd") or HOME
    state = load_state(d, cwd)
    pid = read_pid(d)
    alive = pid_alive(pid, meta.get("pidStart"))
    exit_txt = read_text(os.path.join(d, "exit"))
    exit_code = None
    if exit_txt is not None:
        try:
            exit_code = int(exit_txt.strip())
        except ValueError:
            exit_code = None
    stopped = os.path.exists(os.path.join(d, "stopped"))
    pending = None
    error = None
    started = meta.get("startedAt") or mtime_ms(os.path.join(d, "meta.json"))
    if alive:
        if state["requests"]:
            # Oldest first: JSON objects keep insertion order (CPython 3.6+ dicts are ordered).
            pending = next(iter(state["requests"].values()))
            status = "AWAITING_PERMISSION"
        elif state["lastKind"] == "activity" or (
                state["sent"] > state["echoes"]
                # a sent-but-never-echoed line older than 5 min after a result is not "working"
                and not (state["lastKind"] == "result"
                         and now_ms() - mtime_ms(os.path.join(d, "in.jsonl")) > 5 * 60 * 1000)):
            status = "WORKING"
        elif not state["initResp"] and state["turns"] == 0 and now_ms() - started < 120 * 1000:
            status = "STARTING"
        else:
            status = "IDLE"
        if state["resultError"] and status == "IDLE":
            error = state["resultError"]
    else:
        if stopped or exit_code in (0, 143, -15, 137) or (state["turns"] > 0 and exit_code is None):
            status = "ENDED"
        elif state["turns"] > 0 and exit_code == 0:
            status = "ENDED"
        else:
            status = "FAILED"
            error = err_tail(d) or (("Claude exited with status %s." % exit_code) if exit_code is not None
                                    else "The agent process is gone.")
        if status == "ENDED" and state["resultError"]:
            error = state["resultError"]
    upd = max(mtime_ms(os.path.join(d, "out.jsonl")), mtime_ms(os.path.join(d, "in.jsonl")), started)
    out_size = file_size(os.path.join(d, "out.jsonl"))
    return {
        "runId": run_id,
        "cwd": cwd,
        "title": meta.get("title"),
        "sessionId": state["sessionId"] or meta.get("resumeSessionId"),
        "forked": bool(meta.get("forkSession")),
        "model": state["model"] or meta.get("model"),
        "permissionMode": state["permissionMode"] or meta.get("permissionMode"),
        "startedAt": started,
        "updatedAt": upd,
        "alive": alive,
        "status": status,
        "lastText": state["lastText"],
        "costUsd": state["cost"],
        "turns": state["turns"],
        "pending": pending,
        "outBytes": max(0, out_size),
        "exitCode": exit_code,
        "error": error,
        "backgroundTasks": len(state["bgTasks"]) if alive else 0,
    }


def all_runs():
    out = []
    for run_id, d in iter_run_dirs():
        try:
            out.append(run_info(run_id, d))
        except (OSError, IOError, ValueError, KeyError):
            continue
    out.sort(key=lambda r: r["updatedAt"], reverse=True)
    return out


def cmd_runs(opts):
    emit(all_runs())


def run_signature():
    sig = []
    for run_id, d in iter_run_dirs():
        meta_pid = read_pid(d)
        sig.append((run_id, file_size(os.path.join(d, "out.jsonl")), file_size(os.path.join(d, "in.jsonl")),
                    os.path.exists(os.path.join(d, "exit")), pid_alive(meta_pid)))
    sig.sort()
    return sig


class ReaderGone(object):
    """Detects that whoever reads our stdout (the SSH channel) went away, without writing to it."""

    def __init__(self):
        self.poller = None
        try:
            self.poller = select.poll()
            self.poller.register(sys.stdout.fileno(), 0)  # POLLERR / POLLHUP are always reported
        except (AttributeError, ValueError, OSError):
            self.poller = None

    def wait(self, seconds):
        """Sleeps up to `seconds`; returns True as soon as the reader is gone."""
        if self.poller is None:
            time.sleep(seconds)
            return os.getppid() == 1
        try:
            events = self.poller.poll(int(seconds * 1000))
        except (OSError, select.error):
            time.sleep(seconds)
            return False
        for _fd, ev in events:
            if ev & (select.POLLERR | select.POLLHUP | getattr(select, "POLLNVAL", 0)):
                return True
        return False


def cmd_follow(opts, run_id, offset):
    d = run_dir(run_id)
    path = os.path.join(d, "out.jsonl")
    try:
        pos = max(0, int(offset or 0))
    except ValueError:
        raise HelperError("Invalid offset.")
    gone = ReaderGone()
    out = sys.stdout.buffer
    f = None
    pending = b""
    while True:
        if f is None:
            try:
                f = open(path, "rb")
                f.seek(pos)
            except (OSError, IOError):
                f = None
                if gone.wait(0.5):
                    return
                continue
        chunk = f.read(256 * 1024)
        if chunk:
            pending += chunk
            nl = pending.rfind(b"\n")
            if nl >= 0:
                out.write(pending[:nl + 1])
                out.flush()
                pos += nl + 1
                pending = pending[nl + 1:]
            continue
        size = file_size(path)
        if size < pos + len(pending):
            return  # truncated or replaced: let the app re-attach from scratch
        if gone.wait(0.25):
            return


def cmd_watch(opts):
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass
    gone = ReaderGone()
    last_sig = None
    last_line = None
    last_emit = 0
    last_full = 0
    native_on = "--no-native" not in sys.argv
    last_nsig = None
    last_native = 0
    last_nline = None
    while True:
        t = time.time()
        sig = run_signature()
        # Re-derive at least every 10 s even without file changes (STARTING -> IDLE ageing, etc.).
        if sig != last_sig or t - last_full > 10:
            last_sig = sig
            last_full = t
            line = json.dumps(all_runs(), ensure_ascii=False, separators=(",", ":"))
            if line != last_line:
                last_line = line
                sys.stdout.write(line + "\n")
                sys.stdout.flush()
                last_emit = t
        if native_on:
            nsig = native_signature()
            # The job files move on every change; `claude agents` (pids) is re-read at least every 5 s.
            if nsig != last_nsig or t - last_native > 5:
                last_nsig = nsig
                last_native = t
                try:
                    nline = json.dumps({"native": native_list(opts)}, ensure_ascii=False, separators=(",", ":"))
                except HelperError:
                    native_on = False  # no claude here: nothing native to watch
                    nline = None
                except (OSError, IOError, ValueError):
                    nline = None
                if nline is not None and nline != last_nline:
                    last_nline = nline
                    sys.stdout.write(nline + "\n")
                    sys.stdout.flush()
                    last_emit = t
        if t - last_emit >= 15:
            sys.stdout.write('{"hb":%d}\n' % now_ms())
            sys.stdout.flush()
            last_emit = t
        if gone.wait(1.0):
            return


def cmd_start(opts):
    raw = sys.stdin.read()
    try:
        req = json.loads(raw) if raw.strip() else {}
    except ValueError:
        raise HelperError("The start request is not valid JSON.")
    cwd = os.path.abspath(os.path.expanduser(req.get("cwd") or HOME))
    if not os.path.isdir(cwd):
        raise HelperError("The folder %s does not exist." % cwd)
    claude_dir = os.path.join(HOME, ".claude")
    if cwd == claude_dir or cwd.startswith(claude_dir + "/"):
        raise HelperError("Claude Code will not work inside ~/.claude. Pick another folder.")
    claude, login_path = resolve_claude(opts.get("claude") or req.get("claudePath"))
    if not claude:
        raise HelperError("Claude Code was not found on this machine. Install it, or set its path in the machine settings.")

    ensure_dir(RUNS_DIR)
    run_id = new_run_id()
    d = os.path.join(RUNS_DIR, run_id)
    ensure_dir(d)
    started = now_ms()

    args = [claude, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages", "--replay-user-messages", "--permission-prompt-tool", "stdio"]
    model = req.get("model")
    if model and model != "default":
        args += ["--model", model]
    mode = req.get("permissionMode")
    if mode and mode != "default":
        args += ["--permission-mode", mode]
    resume = req.get("resumeSessionId")
    if resume:
        if not SESSION_ID_RE.match(resume):
            raise HelperError("Invalid session id.")
        args += ["--resume", resume]
        if req.get("forkSession"):
            args += ["--fork-session"]
        at = req.get("resumeAt")
        if at:
            # Branch point: the new session keeps history only up to (and including) this message.
            if not SESSION_ID_RE.match(at):
                raise HelperError("Invalid message id.")
            args += ["--resume-session-at", at]

    meta = {
        "runId": run_id, "cwd": cwd, "title": req.get("title"), "model": model,
        "permissionMode": mode, "resumeSessionId": resume, "forkSession": bool(req.get("forkSession")),
        "resumeAt": req.get("resumeAt") if resume else None,
        "startedAt": started, "claudePath": claude, "helperVersion": HELPER_VERSION,
    }
    write_json_atomic(os.path.join(d, "meta.json"), meta)

    lines = [json.dumps({"type": "control_request", "request_id": "init_1", "request": {"subtype": "initialize"}})]
    if mode == "default":
        # --permission-mode rejects "default", and omitting the flag inherits the user's settings
        # defaultMode (often "auto"). Ask mode must really ask, so set it explicitly.
        lines.append(json.dumps({"type": "control_request", "request_id": "mode_init",
                                 "request": {"subtype": "set_permission_mode", "mode": "default"}}))
    prompt = req.get("prompt")
    if isinstance(prompt, str) and prompt.strip():
        lines.append(json.dumps({"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": prompt}]}},
                                ensure_ascii=False))
    with open(os.path.join(d, "in.jsonl"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    open(os.path.join(d, "out.jsonl"), "a").close()

    q = shlex.quote
    env = claude_env(claude, login_path)
    runner = "\n".join([
        "#!/bin/bash",
        "# Tether run %s -- started %s" % (run_id, time.strftime("%Y-%m-%d %H:%M:%S")),
        "export PATH=%s" % q(env["PATH"]),
        # File checkpoints make "restore files to before this message" (rewind_files) possible later.
        "export CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1",
        "D=%s" % q(d),
        "cd %s || { echo cannot cd to %s >> \"$D/err.log\"; echo 97 > \"$D/exit\"; exit 97; }" % (q(cwd), q(cwd)),
        "tail -n +1 -f \"$D/in.jsonl\" | %s > \"$D/out.jsonl\" 2> \"$D/err.log\"" % " ".join(q(a) for a in args),
        "echo $? > \"$D/exit.tmp\" && mv -f \"$D/exit.tmp\" \"$D/exit\"",
        "kill 0",
        "",
    ])
    rp = os.path.join(d, "runner.sh")
    with open(rp, "w") as f:
        f.write(runner)
    os.chmod(rp, 0o700)
    proc = subprocess.Popen(["/bin/bash", rp], cwd=d, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, close_fds=True, start_new_session=True, env=env)
    with open(os.path.join(d, "pid"), "w") as f:
        f.write(str(proc.pid))
    tag = proc_start_tag(proc.pid)
    if tag:
        meta["pidStart"] = tag
        write_json_atomic(os.path.join(d, "meta.json"), meta)
    # Give the process a moment so an immediate failure (bad flag, auth) is reported as FAILED.
    deadline = time.time() + 1.2
    while time.time() < deadline:
        if proc.poll() is not None:
            break
        time.sleep(0.1)
    emit(run_info(run_id, d))


def cmd_send(opts, run_id):
    d = run_dir(run_id)
    data = sys.stdin.buffer.read()
    if not data.strip():
        emit({"ok": True, "bytes": 0})
        return
    lines = []
    for raw in iter_lines_bytes(data):
        raw = raw.strip()
        if not raw:
            continue
        try:
            json.loads(raw.decode("utf-8"))
        except ValueError:
            raise HelperError("Refusing to send a line that is not JSON.")
        lines.append(raw)
    payload = b"\n".join(lines) + b"\n"
    fd = os.open(os.path.join(d, "in.jsonl"), os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        # Make sure we start on a fresh line even if a previous writer died mid-line.
        size = os.fstat(fd).st_size
        if size > 0:
            with open(os.path.join(d, "in.jsonl"), "rb") as f:
                f.seek(size - 1)
                if f.read(1) != b"\n":
                    payload = b"\n" + payload
        view = memoryview(payload)
        while view:
            n = os.write(fd, view)
            view = view[n:]
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)
    meta = read_json(os.path.join(d, "meta.json"), {}) or {}
    emit({"ok": True, "bytes": len(payload), "alive": pid_alive(read_pid(d), meta.get("pidStart"))})


def cmd_input(opts, run_id):
    d = run_dir(run_id)
    try:
        with open(os.path.join(d, "in.jsonl"), "rb") as f:
            for raw in f:
                raw = raw.strip()
                if not raw:
                    continue
                if len(raw) > 32 * 1024 and b'"image"' in raw:
                    o = parse_line(raw)
                    if o:
                        slim_line(o)
                        raw = json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                sys.stdout.buffer.write(raw + b"\n")
    except (OSError, IOError):
        pass
    sys.stdout.flush()


def stop_run(d):
    meta = read_json(os.path.join(d, "meta.json"), {}) or {}
    pid = read_pid(d)
    try:
        open(os.path.join(d, "stopped"), "w").close()
    except (OSError, IOError):
        pass
    if not pid or not pid_alive(pid, meta.get("pidStart")):
        return False
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(pid, sig)
        except OSError:
            try:
                os.kill(pid, sig)
            except OSError:
                pass
        deadline = time.time() + 3.0
        while time.time() < deadline:
            if not pid_alive(pid, meta.get("pidStart")):
                try:
                    os.killpg(pid, signal.SIGKILL)  # reap any straggler (tail) in the group
                except OSError:
                    pass
                return True
            time.sleep(0.1)
    return True


def cmd_stop(opts, run_id):
    d = run_dir(run_id)
    stop_run(d)
    emit(run_info(run_id, d))


def cmd_delete(opts, run_id):
    d = run_dir(run_id)
    stop_run(d)
    import shutil
    real = os.path.realpath(d)
    if not real.startswith(os.path.realpath(RUNS_DIR) + os.sep):
        raise HelperError("Refusing to delete outside ~/.tether/runs.")
    shutil.rmtree(real, ignore_errors=True)
    emit({"ok": True, "runId": run_id})


# ───────────────────────────────────────── native background agents ─────────────────────────────────────────
# Claude Code's own `claude --bg` sessions. `claude agents --json --all` is the list of record (it
# knows pids and forgets removed ones); ~/.claude/jobs/<id>/state.json adds the live one-line
# detail, subagent fan-out and tokens; timeline.jsonl is the activity history.

JOBS_DIR = os.path.join(HOME, ".claude", "jobs")
CLAUDE_SESSIONS = os.path.join(HOME, ".claude", "sessions")
# A running terminal `claude` session (kind "interactive" in `claude agents`) has no id of its own: term-<pid>.
TERMINAL_PREFIX = "term-"
CLAUDE_JSON = os.path.join(HOME, ".claude.json")
NATIVE_ID_RE = re.compile(r"^[0-9A-Za-z_-]{4,64}$")
ANSI_RE = re.compile(r"\x1b\[[0-9;?<=>]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78DEHMc]")
BG_ID_RE = re.compile(r"backgrounded\s*[·\-:]\s*([0-9A-Za-z_-]{4,64})")
NATIVE_RESULT_CAP = 600


def strip_ansi(s):
    if not s:
        return s
    s = ANSI_RE.sub("", s)
    s = s.replace("\r\n", "\n")
    # A bare CR redraws the line: keep what was drawn last.
    return "\n".join(l.rsplit("\r", 1)[-1] for l in s.split("\n"))


TERM_TOKEN_RE = re.compile(r"\x1b\[([0-9;?<=>]*)([ -/]*)([@-~])|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b([()][0-9A-Za-z]|[=>78DEHMc])|([\r\n\b\t])|([^\x1b\r\n\b\t]+)")


def render_terminal(raw, max_rows=5000, max_cols=4096):
    """Replays a TUI byte stream (cursor moves, erases, redraws) onto a virtual screen and returns the
    final screen as plain text -- what the terminal would show, not every intermediate frame."""
    rows = {}
    r = c = 0
    saved = (0, 0)

    def line(i):
        l = rows.get(i)
        if l is None:
            l = rows[i] = []
        return l

    def put(text):
        nonlocal c
        l = line(r)
        for ch in text:
            if ord(ch) < 32 or ch == "\x7f":
                continue
            if c >= max_cols:  # cursor moved absurdly far right (e.g. CSI 2147483647 C): drop, don't allocate
                continue
            if c < len(l):
                l[c] = ch
            else:
                l.extend(" " * (c - len(l)))
                l.append(ch)
            c += 1

    for m in TERM_TOKEN_RE.finditer(raw):
        params, _inter, final, esc, ctl, text = m.groups()
        if text is not None:
            put(text)
        elif ctl is not None:
            if ctl == "\r":
                c = 0
            elif ctl == "\n":
                r += 1
                c = 0
            elif ctl == "\b":
                c = max(0, c - 1)
            elif ctl == "\t":
                c = (c // 8 + 1) * 8
        elif esc is not None:
            if esc == "7":
                saved = (r, c)
            elif esc == "8":
                r, c = saved
            elif esc == "c":
                rows.clear()
                r = c = 0
        elif final is not None:
            ps = [int(x) if x.isdigit() else 0 for x in (params or "").lstrip("?<=>").split(";")] if params else []
            n = ps[0] if ps and ps[0] else 1
            if final == "A":
                r = max(0, r - n)
            elif final == "B":
                r += n
            elif final == "C":
                c += n
            elif final == "D":
                c = max(0, c - n)
            elif final == "E":
                r += n
                c = 0
            elif final == "F":
                r = max(0, r - n)
                c = 0
            elif final == "G":
                c = n - 1
            elif final in ("H", "f"):
                r = (ps[0] - 1) if ps and ps[0] else 0
                c = (ps[1] - 1) if len(ps) > 1 and ps[1] else 0
            elif final == "d":
                r = n - 1
            elif final == "K":
                mode = ps[0] if ps else 0
                l = line(r)
                if mode == 0:
                    del l[c:]
                elif mode == 1:
                    for i in range(min(c + 1, len(l))):
                        l[i] = " "
                else:
                    del l[:]
            elif final == "J":
                mode = ps[0] if ps else 0
                if mode in (2, 3):
                    rows.clear()
                elif mode == 0:
                    del line(r)[c:]
                    for k in [k for k in rows if k > r]:
                        del rows[k]
                elif mode == 1:
                    for k in [k for k in rows if k < r]:
                        del rows[k]
            if r > max_rows:
                r = max_rows
    if not rows:
        return ""
    out = ["".join(rows.get(i, [])).rstrip().replace("\xa0", " ") for i in range(0, max(rows) + 1)]
    while out and not out[-1].strip():
        out.pop()
    while out and not out[0].strip():
        out.pop(0)
    # Collapse runs of blank lines left by full-screen layouts.
    res = []
    for l in out:
        if not l.strip() and res and not res[-1].strip():
            continue
        res.append(l)
    return "\n".join(res)


def native_id(v, terminal_ok=False):
    if not NATIVE_ID_RE.match(v or ""):
        raise HelperError("Invalid background agent id.")
    if not terminal_ok and v.startswith(TERMINAL_PREFIX):
        raise HelperError("This Claude Code session runs in a terminal on your computer. Continue it there, "
                          "or reply here to start a background copy.")
    return v


def terminal_pid(agent_id):
    try:
        return int(agent_id[len(TERMINAL_PREFIX):]) if agent_id.startswith(TERMINAL_PREFIX) else None
    except ValueError:
        return None


def parent_pid(pid):
    try:
        with open("/proc/%d/stat" % pid) as f:
            s = f.read()
        return int(s[s.rindex(")") + 2:].split()[1])
    except (OSError, IOError, ValueError, IndexError):
        pass
    out, _err, rc = run_cmd(["ps", "-o", "ppid=", "-p", str(pid)], timeout=5)
    try:
        return int(out.strip()) if rc == 0 and out else None
    except ValueError:
        return None


def tether_runner_pids():
    """Pids of Tether's live-run runners: their `claude -p` child also shows in `claude agents` as interactive."""
    pids = set()
    for _run_id, d in iter_run_dirs():
        p = read_pid(d)
        if p:
            pids.add(p)
    return pids


def claude_run(opts, args, cwd=None, timeout=30, raw=False):
    """Runs the resolved claude with args. Returns (stdout, stderr, rc) with ANSI stripped."""
    claude, login_path = resolve_claude(opts.get("claude"))
    if not claude:
        raise HelperError("Claude Code was not found on this machine. Install it, or set its path in the machine settings.")
    env = claude_env(claude, login_path)
    env.setdefault("TERM", "dumb")
    env["NO_COLOR"] = "1"
    try:
        p = subprocess.Popen([claude] + list(args), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env, cwd=cwd or HOME, start_new_session=True)
        try:
            out, err = p.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            out, err = p.communicate()
            return strip_ansi(out.decode("utf-8", "replace")), strip_ansi(err.decode("utf-8", "replace")), -1
    except OSError as e:
        raise HelperError("Could not run Claude Code: %s" % e)
    out = out.decode("utf-8", "replace")
    err = err.decode("utf-8", "replace")
    if raw:
        return out, err, p.returncode
    return strip_ansi(out), strip_ansi(err), p.returncode


def agents_json(opts):
    """`claude agents --json --all`, or None when it could not be read."""
    out, _err, rc = claude_run(opts, ["agents", "--json", "--all"], timeout=20)
    if rc != 0 or not out:
        return None
    t = out.strip()
    i = t.find("[")
    if i < 0:
        return None
    try:
        v = json.loads(t[i:])
    except ValueError:
        return None
    return [x for x in v if isinstance(x, dict)] if isinstance(v, list) else None


def job_state(agent_id):
    return read_json(os.path.join(JOBS_DIR, agent_id, "state.json"), {}) or {}


def flag_value(flags, name):
    if not isinstance(flags, list):
        return None
    for i, f in enumerate(flags):
        if f == name and i + 1 < len(flags) and isinstance(flags[i + 1], str):
            return flags[i + 1]
        if isinstance(f, str) and f.startswith(name + "="):
            return f[len(name) + 1:]
    return None


def timeline_entries(agent_id, limit=None, text_cap=4000):
    path = os.path.join(JOBS_DIR, agent_id, "timeline.jsonl")
    data = read_tail(path, 64 * 1024) if limit else read_tail(path, 4 * 1024 * 1024)
    out = []
    for raw in iter_lines_bytes(data):
        o = parse_line(raw)
        if not o:
            continue
        out.append({
            "at": iso_to_ms(o.get("at")) or 0,
            "state": o.get("state"),
            "detail": one_line(o.get("detail"), 240),
            "text": trim(o.get("text"), text_cap) if isinstance(o.get("text"), str) else None,
        })
    if limit:
        out = out[-limit:]
    return out


def native_agent(item, want_timeline=True):
    aid = item.get("id") or ""
    st = job_state(aid) if NATIVE_ID_RE.match(aid) else {}
    pid = item.get("pid") if isinstance(item.get("pid"), int) else None
    fan = []
    todos = []
    for f in st.get("fan") or []:
        if not isinstance(f, dict):
            continue
        started = f.get("startedAt") if isinstance(f.get("startedAt"), (int, float)) and f.get("startedAt") > 0 else None
        done_at = f.get("doneAt") if isinstance(f.get("doneAt"), (int, float)) and f.get("doneAt") > 0 else None
        if f.get("kind") == "todo":
            todos.append({"label": one_line(f.get("label"), 200) or "", "done": "doneAt" in f})
            continue
        fan.append({
            "id": str(f.get("id") or ""), "kind": f.get("kind") or "agent",
            "label": one_line(f.get("label"), 200) or (f.get("kind") or "Subagent"),
            "group": one_line(f.get("group"), 120),
            "startedAt": int(started) if started else None,
            "doneAt": int(done_at) if done_at else None,
            "running": "doneAt" not in f,
        })
    in_flight = st.get("inFlight") if isinstance(st.get("inFlight"), dict) else {}
    output = st.get("output") if isinstance(st.get("output"), dict) else {}
    result = output.get("result") if isinstance(output.get("result"), str) else None
    sid = item.get("sessionId") or st.get("sessionId")
    tpath = st.get("linkScanPath") if isinstance(st.get("linkScanPath"), str) else None
    if not (tpath and os.path.isfile(tpath)) and sid and SESSION_ID_RE.match(sid):
        tpath = find_transcript(sid)
    jd = os.path.join(JOBS_DIR, aid)
    updated = iso_to_ms(st.get("updatedAt")) or 0
    updated = max(updated, mtime_ms(os.path.join(jd, "timeline.jsonl")), mtime_ms(os.path.join(jd, "state.json")))
    started = item.get("startedAt") if isinstance(item.get("startedAt"), (int, float)) else iso_to_ms(st.get("createdAt"))
    flags = st.get("respawnFlags")
    terminal = item.get("kind") == "interactive"
    name = st.get("name") or item.get("name")
    intent = st.get("intent")
    if terminal:
        # Claude derives a name from the folder ("tether-ac"); the conversation's own title says more.
        titles = {}
        if tpath:
            updated = max(updated, mtime_ms(tpath))
            scan_titles(read_tail(tpath, 256 * 1024), titles)
        reg = read_json(os.path.join(CLAUDE_SESSIONS, "%d.json" % pid), {}) if pid else {}
        if not (isinstance(reg, dict) and reg.get("nameSource") not in (None, "derived")):
            name = titles.get("customTitle") or titles.get("aiTitle") or name
        intent = titles.get("lastPrompt")
    out = {
        "id": aid,
        "sessionId": sid,
        "cwd": item.get("cwd") or st.get("cwd") or HOME,
        "kind": item.get("kind") or "background",
        "name": one_line(name, 160),
        "intent": one_line(intent, 240),
        "state": item.get("state") or st.get("state") or (
            ("working" if item.get("status") == "busy" else "idle") if terminal else ("working" if pid else "done")),
        "status": item.get("status"),
        "pid": pid,
        "alive": bool(pid) and pid_alive(pid),
        "startedAt": int(started or updated or 0),
        "updatedAt": int(updated or started or 0),
        "detail": one_line(st.get("detail"), 240),
        "tempo": st.get("tempo"),
        "tasks": in_flight.get("tasks") if isinstance(in_flight.get("tasks"), int) else 0,
        "queued": in_flight.get("queued") if isinstance(in_flight.get("queued"), int) else 0,
        "fan": fan,
        "todos": todos,
        "tokens": st.get("tokens") if isinstance(st.get("tokens"), int) else 0,
        "result": trim(result, NATIVE_RESULT_CAP),
        "model": flag_value(flags, "--model"),
        "permissionMode": flag_value(flags, "--permission-mode"),
        "transcript": bool(tpath),
    }
    if item.get("status") == "waiting" and not terminal:
        # A question lives in state.json ("block") — its tool_use reaches the transcript only once answered.
        pt = question_pending(aid, st) or (pending_tool_use(tpath, out["cwd"]) if tpath else None)
        if not pt:
            # Nothing on disk describes it: almost always an AskUserQuestion — the app fetches it
            # with native-question (reads the agent's screen) when the user opens it.
            pt = {"toolUseId": "q-screen-%s" % (st.get("updatedAt") or out["updatedAt"]), "toolName": "AskUserQuestion",
                  "summary": out.get("detail") or "Claude has a question",
                  "inputJson": json.dumps({"questions": [], "needsFetch": True})}
        out["pendingTool"] = pt
    if want_timeline:
        out["timeline"] = timeline_entries(aid, 4, 280) if st else []
    return out


def native_items(opts):
    items = agents_json(opts)
    if items is None:
        # `claude agents` unavailable: fall back to the job directories themselves.
        items = []
        try:
            names = os.listdir(JOBS_DIR)
        except OSError:
            names = []
        for n in names:
            st = read_json(os.path.join(JOBS_DIR, n, "state.json"), None)
            if isinstance(st, dict) and NATIVE_ID_RE.match(n):
                items.append({"id": n, "cwd": st.get("cwd"), "kind": "background", "sessionId": st.get("sessionId"),
                              "name": st.get("name"), "state": st.get("state"), "startedAt": iso_to_ms(st.get("createdAt"))})
    for item in items:
        if item.get("kind") == "interactive" and isinstance(item.get("pid"), int) and not item.get("id"):
            item["id"] = TERMINAL_PREFIX + str(item["pid"])
    return items


LINEAGE_PATH = os.path.join(TETHER_DIR, "native_lineage.json")


def read_lineage():
    """{successorId: [ancestorIds…]} for replies that claude continued under a new id."""
    v = read_json(LINEAGE_PATH, {}) or {}
    return v if isinstance(v, dict) else {}


def record_fork(old_id, new_id):
    lin = read_lineage()
    ancestors = [old_id] + [a for a in lin.pop(old_id, []) if a != new_id]
    lin[new_id] = ancestors
    try:
        ensure_dir(TETHER_DIR)
        write_json_atomic(LINEAGE_PATH, lin)
    except (OSError, IOError):
        pass


def native_list(opts):
    out = []
    items = native_items(opts)
    present = set(i.get("id") for i in items)
    lin = read_lineage()
    hidden = set()
    for succ, ancestors in lin.items():
        if succ in present:
            hidden.update(ancestors)  # the same conversation continues under succ
    runners = None
    for item in items:
        if item.get("id") in hidden:
            continue
        kind = item.get("kind")
        if kind == "interactive":
            if not item.get("id"):
                continue
            if runners is None:
                runners = tether_runner_pids()
            if parent_pid(item["pid"]) in runners:
                continue  # a Tether live run: listed by `runs` already
        elif not item.get("id") or kind not in (None, "background"):
            continue
        try:
            a = native_agent(item)
            prev = [x for x in lin.get(a["id"], []) if x in present]
            if prev:
                a["previousIds"] = prev
            out.append(a)
        except (OSError, IOError, ValueError, KeyError, TypeError):
            continue
    out.sort(key=lambda a: a["updatedAt"], reverse=True)
    return out


def find_native(opts, agent_id):
    for item in native_items(opts):
        if item.get("id") == agent_id:
            return item
    return None


def cmd_native_list(opts):
    emit(native_list(opts))


def native_signature():
    sig = []
    try:
        names = sorted(os.listdir(JOBS_DIR))
    except OSError:
        return sig
    for n in names:
        d = os.path.join(JOBS_DIR, n)
        sig.append((n, mtime_ms(os.path.join(d, "state.json")), file_size(os.path.join(d, "timeline.jsonl"))))
    try:
        # Terminal sessions: busy / idle / waiting live in ~/.claude/sessions/<pid>.json.
        for n in sorted(os.listdir(CLAUDE_SESSIONS)):
            if n.endswith(".json"):
                sig.append((n, mtime_ms(os.path.join(CLAUDE_SESSIONS, n))))
    except OSError:
        pass
    return sig


# ── folder trust (~/.claude.json projects[<dir>].hasTrustDialogAccepted) ──

def folder_trusted(cwd):
    data = read_json(CLAUDE_JSON, {}) or {}
    projects = data.get("projects") if isinstance(data.get("projects"), dict) else {}
    p = os.path.normpath(cwd)
    while True:
        e = projects.get(p)
        if isinstance(e, dict) and e.get("hasTrustDialogAccepted") is True:
            return True
        parent = os.path.dirname(p)
        if parent == p:
            return False
        p = parent


def trust_folder(cwd):
    """Marks cwd trusted for Claude Code: read, set, write a temp file, os.replace; keeps everything else."""
    cwd = os.path.normpath(cwd)
    data = read_json_object(CLAUDE_JSON, "~/.claude.json")
    projects = data.get("projects")
    if not isinstance(projects, dict):
        projects = {}
        data["projects"] = projects
    entry = projects.get(cwd)
    if not isinstance(entry, dict):
        entry = {}
        projects[cwd] = entry
    if entry.get("hasTrustDialogAccepted") is True:
        return
    entry["hasTrustDialogAccepted"] = True
    write_json_preserving(CLAUDE_JSON, data)


def read_json_object(path, label):
    """The JSON object in path ({} when missing); raises rather than overwrite a file we cannot read."""
    text = read_text(path)
    if text is None:
        return {}
    try:
        data = json.loads(text)
    except ValueError:
        raise HelperError("%s is not valid JSON; not touching it." % label)
    if not isinstance(data, dict):
        raise HelperError("%s has an unexpected shape; not touching it." % label)
    return data


def write_json_preserving(path, data):
    """Writes a temp file next to path, then os.replace, keeping path's permissions."""
    try:
        mode = os.stat(path).st_mode & 0o777
    except OSError:
        mode = 0o600
    tmp = "%s.tether%d.tmp" % (path, os.getpid())
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


# ── project MCP servers (.mcp.json in cwd or a parent). Claude Code asks once per folder which ones
#    to enable; a `claude --bg` session would sit on that screen, so Tether asks on the phone instead
#    and records the answer where Claude does: <cwd>/.claude/settings.local.json. ──

def project_mcp_servers(cwd):
    names = []
    p = os.path.normpath(cwd)
    while True:
        data = read_json(os.path.join(p, ".mcp.json"))
        servers = data.get("mcpServers") if isinstance(data, dict) else None
        if isinstance(servers, dict):
            names += [n for n in servers if isinstance(n, str) and n not in names]
        parent = os.path.dirname(p)
        if parent == p:
            return names
        p = parent


def pending_mcp_servers(cwd):
    """Project MCP servers Claude Code has not been told to enable or skip in cwd yet."""
    names = project_mcp_servers(cwd)
    if not names:
        return []
    cwd = os.path.normpath(cwd)
    sources = [read_json(f) for f in ("/etc/claude-code/managed-settings.json",
                                      os.path.join(HOME, ".claude", "settings.json"),
                                      os.path.join(cwd, ".claude", "settings.json"),
                                      os.path.join(cwd, ".claude", "settings.local.json"))]
    data = read_json(CLAUDE_JSON, {}) or {}
    projects = data.get("projects") if isinstance(data, dict) and isinstance(data.get("projects"), dict) else {}
    sources.append(projects.get(cwd))
    decided = set()
    for s in sources:
        if not isinstance(s, dict):
            continue
        if s.get("enableAllProjectMcpServers") is True:
            return []
        for key in ("enabledMcpjsonServers", "disabledMcpjsonServers"):
            v = s.get(key)
            if isinstance(v, list):
                decided.update(x for x in v if isinstance(x, str))
    return [n for n in names if n not in decided]


def settle_mcp_servers(cwd, names, enable):
    """Adds names to enabledMcpjsonServers (or disabledMcpjsonServers) in <cwd>/.claude/settings.local.json."""
    d = os.path.join(os.path.normpath(cwd), ".claude")
    path = os.path.join(d, "settings.local.json")
    data = read_json_object(path, path.replace(HOME, "~", 1))
    key = "enabledMcpjsonServers" if enable else "disabledMcpjsonServers"
    cur = data.get(key) if isinstance(data.get(key), list) else []
    data[key] = cur + [n for n in names if n not in cur]
    os.makedirs(d, exist_ok=True)
    write_json_preserving(path, data)


def read_request():
    raw = sys.stdin.read()
    try:
        req = json.loads(raw) if raw.strip() else {}
    except ValueError:
        raise HelperError("The request is not valid JSON.")
    if not isinstance(req, dict):
        raise HelperError("The request is not a JSON object.")
    return req


def native_cwd(v):
    cwd = os.path.abspath(os.path.expanduser(v or HOME))
    if not os.path.isdir(cwd):
        raise HelperError("The folder %s does not exist." % cwd)
    claude_dir = os.path.join(HOME, ".claude")
    if cwd == claude_dir or cwd.startswith(claude_dir + "/"):
        raise HelperError("Claude Code will not work inside ~/.claude. Pick another folder.")
    return cwd


def bg_args(model, mode):
    args = ["--bg"]
    if model and model != "default":
        args += ["--model", model]
    if mode and mode != "default":
        args += ["--permission-mode", mode]
    return args


def launch_bg(opts, cwd, args):
    """Runs `claude --bg …` in cwd; returns the new id, 'untrusted', or raises with Claude's sentence."""
    out, err, rc = claude_run(opts, args, cwd=cwd, timeout=90)
    text = (out or "") + "\n" + (err or "")
    m = BG_ID_RE.search(text)
    if m:
        return m.group(1), text
    if "not trusted" in text.lower():
        return "untrusted", text
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    raise HelperError(lines[-1] if lines else "claude --bg exited with status %s." % rc)


def wait_native(opts, agent_id, seconds=6.0):
    deadline = time.time() + seconds
    while True:
        item = find_native(opts, agent_id)
        if item or time.time() > deadline:
            return item
        time.sleep(0.5)


def cmd_native_start(opts):
    req = read_request()
    cwd = native_cwd(req.get("cwd"))
    prompt = req.get("prompt")
    if not isinstance(prompt, str) or not prompt.strip():
        raise HelperError("A background agent needs a first message.")
    # "--" ends option parsing: a prompt starting with "-" must never be read as a claude flag.
    args = bg_args(req.get("model"), req.get("permissionMode")) + ["--", prompt]
    if not folder_trusted(cwd):
        if not req.get("trust"):
            emit({"error": "untrusted", "cwd": cwd})
            return
        trust_folder(cwd)
    pending = pending_mcp_servers(cwd)
    if pending:
        choice = req.get("mcp")
        if choice not in ("enable", "skip"):
            emit({"error": "mcp", "cwd": cwd, "servers": pending})
            return
        settle_mcp_servers(cwd, pending, choice == "enable")
    new_id, text = launch_bg(opts, cwd, args)
    if new_id == "untrusted":
        # Claude has its own notion (e.g. a parent entry we did not read the same way): trust exactly cwd.
        if not req.get("trust"):
            emit({"error": "untrusted", "cwd": cwd})
            return
        trust_folder(cwd)
        new_id, text = launch_bg(opts, cwd, args)
        if new_id == "untrusted":
            raise HelperError("Claude Code still does not trust %s." % cwd)
    item = wait_native(opts, new_id) or {"id": new_id, "cwd": cwd, "kind": "background", "state": "working",
                                         "startedAt": now_ms()}
    emit(native_agent(item))


def stop_native(opts, agent_id, item):
    if not (item and item.get("pid")):
        return
    claude_run(opts, ["stop", agent_id], timeout=30)
    deadline = time.time() + 12
    while time.time() < deadline:
        cur = find_native(opts, agent_id)
        if not cur or not cur.get("pid"):
            return
        time.sleep(0.5)


def cmd_native_reply(opts, agent_id):
    req = read_request()
    msg = req.get("message")
    if not isinstance(msg, str) or not msg.strip():
        raise HelperError("Nothing to send.")
    cmd_native_reply_with(opts, native_id(agent_id, terminal_ok=True), msg,
                          model=setting_arg(req.get("model"), MODEL_ARG_RE, "model"),
                          mode=setting_arg(req.get("permissionMode"), None, "permission mode"))


MODEL_ARG_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-\[\]]{0,80}$")
PERMISSION_MODES = ("default", "acceptEdits", "plan", "auto", "bypassPermissions")


def setting_arg(v, pattern, what):
    """A model / permission mode from the app, checked before it becomes a claude flag."""
    if v is None or v == "":
        return None
    ok = isinstance(v, str) and (pattern.match(v) if pattern else v in PERMISSION_MODES)
    if not ok:
        raise HelperError("Unknown %s." % what)
    return v


def cmd_native_reply_with(opts, agent_id, msg, model=None, mode=None):
    item = find_native(opts, agent_id)
    if not item:
        raise HelperError("That background agent no longer exists.")
    st = job_state(agent_id)
    sid = item.get("sessionId") or st.get("sessionId")
    if not sid or not SESSION_ID_RE.match(sid):
        raise HelperError("This background agent has no conversation to continue.")
    cwd = native_cwd(item.get("cwd") or st.get("cwd"))
    terminal = item.get("kind") == "interactive"
    if not terminal:
        stop_native(opts, agent_id, item)
    # No --model / --permission-mode here: the session keeps its own saved options, and passing any
    # flag makes claude start a copy under a new id instead of continuing this one. A terminal session
    # is still in use, so it gets exactly that: --fork-session copies it under a new session id rather
    # than writing into the conversation the terminal is running.
    # A model / mode chosen on the phone rides along as flags: they apply to this session only (unlike
    # the TUI's /model, which also saves the choice as the default for every new session).
    args = ["--bg", "--resume", sid] + (["--fork-session"] if terminal else [])
    if (model or mode) and not terminal:
        # Any flag starts a copy with only the flags given: keep the setting that isn't changing.
        flags = st.get("respawnFlags")
        model = model or flag_value(flags, "--model")
        mode = mode or flag_value(flags, "--permission-mode")
    if model:
        args += ["--model", model]
    if mode:
        args += ["--permission-mode", mode]
    new_id, _text = launch_bg(opts, cwd, args + ["--", msg])
    if new_id == "untrusted":
        raise HelperError("Claude Code no longer trusts %s." % cwd)
    cur = wait_native(opts, new_id) or {"id": new_id, "cwd": cwd, "kind": "background", "sessionId": None if terminal else sid,
                                        "state": "working", "startedAt": now_ms()}
    if new_id != agent_id and not terminal:
        record_fork(agent_id, new_id)
    res = native_agent(cur)
    res["previousId"] = agent_id
    res["forked"] = new_id != agent_id
    emit(res)


# ── driving a running background agent through `claude attach` ──
# A background agent is a TUI owned by Claude Code's daemon; `claude attach <id>` is the supported
# way in. We open it in a pseudo-terminal, act like a person at the keyboard, and detach. The agent
# keeps running throughout (verified on 2.1.283: text pasted while it works is queued, "1" answers
# "Do you want to proceed? 1. Yes", Esc cancels a prompt / interrupts a turn).

def pending_tool_use(tpath, cwd):
    """The newest tool_use in the transcript that has no tool_result yet (what a prompt is about)."""
    data = read_tail(tpath, 512 * 1024)
    uses = {}
    order = []
    done = set()
    for raw in iter_lines_bytes(data):
        o = parse_line(raw)
        if not o or o.get("isSidechain"):
            continue
        msg = o.get("message") if isinstance(o.get("message"), dict) else {}
        content = msg.get("content")
        if not isinstance(content, list):
            continue
        for b in content:
            if not isinstance(b, dict):
                continue
            if b.get("type") == "tool_use" and b.get("id"):
                uses[b["id"]] = b
                order.append(b["id"])
            elif b.get("type") == "tool_result" and b.get("tool_use_id"):
                done.add(b["tool_use_id"])
    for tid in reversed(order):
        if tid not in done:
            b = uses[tid]
            inp = b.get("input") if isinstance(b.get("input"), dict) else {}
            name = b.get("name") or "Tool"
            return {
                "toolUseId": tid,
                "toolName": name,
                "summary": permission_summary(name, inp, cwd),
                "inputJson": json.dumps(inp, ensure_ascii=False)[:20000],
            }
    return None


def transcript_size(item):
    sid = item.get("sessionId") or job_state(item.get("id") or "").get("sessionId")
    path = find_transcript(sid) if sid and SESSION_ID_RE.match(sid) else None
    return path, (file_size(path) if path else 0)


def attach_session(opts, agent_id, actions, settle=1.2):
    """Runs `claude attach <id>` in a pty and performs actions: a list of bytes (written) or floats
    (seconds to wait while draining output). Waits for the TUI prompt before acting."""
    import pty
    import struct
    import termios
    claude, login_path = resolve_claude(opts.get("claude"))
    if not claude:
        raise HelperError("Claude Code was not found on this machine.")
    env = claude_env(claude, login_path)
    env["TERM"] = "xterm-256color"
    env.pop("NO_COLOR", None)
    pid, fd = pty.fork()
    if pid == 0:  # child
        try:
            os.chdir(HOME)
            os.execve(claude, [claude, "attach", agent_id], env)
        finally:
            os._exit(127)
    seen = bytearray()
    try:
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))

        def pump(seconds, until=None):
            end = time.time() + seconds
            while time.time() < end:
                r, _w, _x = select.select([fd], [], [], 0.05)
                if not r:
                    continue
                try:
                    d = os.read(fd, 65536)
                except OSError:
                    return False
                if not d:
                    return False
                seen.extend(d)
                if b"\x1b[6n" in d:  # cursor position query: answer so the TUI does not stall
                    os.write(fd, b"\x1b[1;1R")
                if until is not None and until in seen:
                    return True
            return until is None

        # The input prompt glyph (❯) appears once the TUI has drawn.
        if not pump(10.0, until="\u276f".encode("utf-8")):
            raise HelperError("Couldn't open the background agent's terminal.")
        pump(settle)
        for a in actions:
            if isinstance(a, (int, float)):
                pump(float(a))
            else:
                os.write(fd, a)
                pump(0.05)
        pump(0.6)
    finally:
        try:
            os.kill(pid, signal.SIGHUP)
        except OSError:
            pass
        time.sleep(0.2)
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except OSError:
            pass
        try:
            os.close(fd)
        except OSError:
            pass
    return bytes(seen)


def live_native(opts, agent_id):
    agent_id = native_id(agent_id)
    item = find_native(opts, agent_id)
    if not item:
        raise HelperError("That background agent no longer exists.")
    pid = item.get("pid") if isinstance(item.get("pid"), int) else None
    return agent_id, item, bool(pid) and pid_alive(pid)


def cmd_native_send(opts, agent_id):
    req = read_request()
    msg = req.get("message")
    if not isinstance(msg, str) or not msg.strip():
        raise HelperError("Nothing to send.")
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive:
        # A stopped agent has no terminal to type into: continue its session instead.
        return cmd_native_reply_with(opts, agent_id, msg)
    if item.get("status") == "waiting":
        raise HelperError("Claude is waiting for your approval — answer it first.")
    text = msg.replace("\r\n", "\n").replace("\r", "\n").strip()
    path, before = transcript_size(item)
    paste = b"\x1b[200~" + text.encode("utf-8") + b"\x1b[201~"
    attach_session(opts, agent_id, [paste, 1.5, b"\r", 1.5])
    delivered = wait_transcript_growth(path, before, 6.0)
    if not delivered:
        # One retry, typed in small chunks (newlines as backslash+Enter), for TUIs that drop pastes.
        body = text.replace("\n", "\\\r").encode("utf-8")
        chunks = [body[i:i + 48] for i in range(0, len(body), 48)]
        acts = []
        for c in chunks:
            acts += [c, 0.03]
        attach_session(opts, agent_id, acts + [0.4, b"\r", 1.5])
        delivered = wait_transcript_growth(path, before, 6.0)
    if not delivered:
        raise HelperError("The agent didn't take the message. Try again in a moment.")
    cur = find_native(opts, agent_id) or item
    res = native_agent(cur)
    res["messageQueued"] = cur.get("status") == "busy"
    emit(res)


def wait_transcript_growth(path, before, seconds):
    if not path:
        time.sleep(min(seconds, 2.0))
        return True  # cannot verify without a transcript; assume the keystrokes landed
    end = time.time() + seconds
    while time.time() < end:
        if file_size(path) > before:
            return True
        time.sleep(0.25)
    return False


def cmd_native_answer(opts, agent_id):
    req = read_request()
    decision = req.get("decision")
    if decision not in ("allow", "deny"):
        raise HelperError("decision must be allow or deny.")
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive:
        raise HelperError("That background agent has stopped.")
    if item.get("status") != "waiting":
        raise HelperError("There is no pending approval any more.")
    # "1" is always "Yes" in Claude Code's prompt; Esc always cancels it (numbering of "No" varies).
    attach_session(opts, agent_id, [b"1" if decision == "allow" else b"\x1b", 1.0])
    deadline = time.time() + 5.0
    cur = item
    while time.time() < deadline:
        cur = find_native(opts, agent_id) or cur
        if cur.get("status") != "waiting":
            break
        time.sleep(0.4)
    emit(native_agent(cur))


def cmd_native_interrupt(opts, agent_id):
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive:
        raise HelperError("That background agent has stopped.")
    if item.get("status") == "busy":
        attach_session(opts, agent_id, [b"\x1b", 1.0])
    emit(native_agent(find_native(opts, agent_id) or item))


# The mode line under the prompt, as Tui.text() reads it (whitespace removed), e.g. "⏸ manual mode on",
# "⏵⏵ accept edits on (shift+tab to cycle)". "auto mode unavailable for this model" matches none.
SCREEN_MODES = (("manualmodeon", "default"), ("accepteditson", "acceptEdits"), ("planmodeon", "plan"),
                ("automodeon", "auto"), ("bypasspermissionson", "bypassPermissions"))


def screen_mode(text):
    """The mode the newest mode line in text shows, or None."""
    best, at = None, -1
    for label, mode in SCREEN_MODES:
        i = text.rfind(label)
        if i > at:
            best, at = mode, i
    return best


def cmd_native_mode(opts, agent_id):
    """Switches a running agent's permission mode with Shift+Tab, like a person at its terminal.
    The cycle (manual → accept edits → plan → auto → bypass) skips modes the agent can't use; when the
    wanted one never comes up, the agent is put back where it was."""
    req = read_request()
    want = setting_arg(req.get("mode"), None, "permission mode")
    if not want:
        raise HelperError("Unknown permission mode.")
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive:
        raise HelperError("That background agent has stopped.")
    if item.get("status") == "waiting":
        raise HelperError("Answer Claude's request first.")
    tui = Tui(opts, agent_id)
    try:
        orig = cur = screen_mode(tui.text(0))
        if cur is None:
            raise HelperError("Couldn't read the agent's mode.")

        def press():
            m = tui.mark()
            tui.send(b"\x1b[Z", 0.8)
            mode = screen_mode(tui.text(m))
            if mode is None:
                tui.pump(1.0)
                mode = screen_mode(tui.text(m))
            if mode is None:
                raise HelperError("Couldn't read the agent's mode.")
            return mode

        for _ in range(len(SCREEN_MODES)):
            if cur == want:
                break
            cur = press()
        if cur != want:
            for _ in range(len(SCREEN_MODES)):
                if cur == orig:
                    break
                cur = press()
            raise HelperError("That mode isn't available for this agent.")
    finally:
        tui.close()
    # The daemon mirrors the mode into the job's respawn flags, which native_agent reports.
    deadline = time.time() + 3.0
    cur_item = item
    while time.time() < deadline:
        cur_item = find_native(opts, agent_id) or cur_item
        if flag_value(job_state(agent_id).get("respawnFlags"), "--permission-mode") == want or \
                (want == "default" and not flag_value(job_state(agent_id).get("respawnFlags"), "--permission-mode")):
            break
        time.sleep(0.3)
    res = native_agent(cur_item)
    res["permissionMode"] = want
    emit(res)


def question_block(st):
    blk = st.get("block") if isinstance(st.get("block"), dict) else None
    qs = blk.get("questions") if blk else None
    if not isinstance(qs, list) or not qs:
        return None
    return [q for q in qs if isinstance(q, dict) and q.get("question")]


def question_key(st, qs):
    raw = json.dumps([st.get("needs"), qs], sort_keys=True, ensure_ascii=False)
    import hashlib
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def qcache_path(agent_id):
    return os.path.join(TETHER_DIR, "qcache", agent_id + ".json")


def cached_multi(agent_id, key):
    c = read_json(qcache_path(agent_id), {}) or {}
    return c.get("multi") if c.get("key") == key and isinstance(c.get("multi"), list) else None


def question_pending(agent_id, st):
    qs = question_block(st)
    if not qs:
        c = read_json(qcache_path(agent_id), {}) or {}
        if isinstance(c.get("questions"), list) and c.get("questions") and c.get("status_at") == st.get("updatedAt"):
            qs = c["questions"]
    if not qs:
        return None
    key = question_key(st, qs)
    multi = cached_multi(agent_id, key)
    questions = []
    for i, q in enumerate(qs):
        e = {"question": q.get("question"), "header": q.get("header") or "",
             "options": [{"label": o.get("label") or "", "description": o.get("description") or ""}
                         for o in (q.get("options") or []) if isinstance(o, dict)]}
        if isinstance(q.get("multiSelect"), bool):
            e["multiSelect"] = q["multiSelect"]
        elif multi is not None and i < len(multi):
            e["multiSelect"] = bool(multi[i])
        questions.append(e)
    return {
        "toolUseId": "q-" + key,
        "toolName": "AskUserQuestion",
        "summary": one_line(qs[0].get("question"), 200) or "Claude has a question",
        "inputJson": json.dumps({"questions": questions, "multiSelectKnown": all("multiSelect" in q for q in questions)}, ensure_ascii=False),
    }


class Screen(object):
    """Just enough of a VT100 to replay a TUI's output into a character grid (Ink-style output:
    relative cursor moves, erase-line, carriage returns). Colours and modes are ignored."""
    CSI = re.compile(r"\x1b\[([0-9;?<=>]*)([ -/]*)([@-~])|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78DEHMc]")

    def __init__(self, rows=40, cols=120):
        self.rows, self.cols = rows, cols
        self.grid = [[" "] * cols for _ in range(rows)]
        self.r = self.c = 0

    def _put(self, ch):
        if self.c >= self.cols:
            self.c = 0
            self._lf()
        self.grid[self.r][self.c] = ch
        self.c += 1

    def _lf(self):
        if self.r == self.rows - 1:
            self.grid.pop(0)
            self.grid.append([" "] * self.cols)
        else:
            self.r += 1

    def feed(self, text):
        pos = 0
        for m in self.CSI.finditer(text):
            self._text(text[pos:m.start()])
            pos = m.end()
            if m.group(3) is None:
                continue
            params, final = m.group(1), m.group(3)
            if params.startswith("?"):
                continue
            nums = [int(x) if x.isdigit() else 0 for x in params.split(";")] if params else []
            n = nums[0] if nums and nums[0] else 1
            if final in "Hf":
                self.r = min(self.rows - 1, max(0, (nums[0] if nums and nums[0] else 1) - 1))
                self.c = min(self.cols - 1, max(0, (nums[1] if len(nums) > 1 and nums[1] else 1) - 1))
            elif final == "A":
                self.r = max(0, self.r - n)
            elif final == "B":
                self.r = min(self.rows - 1, self.r + n)
            elif final == "C":
                self.c = min(self.cols - 1, self.c + n)
            elif final == "D":
                self.c = max(0, self.c - n)
            elif final == "G":
                self.c = min(self.cols - 1, max(0, n - 1))
            elif final == "d":
                self.r = min(self.rows - 1, max(0, n - 1))
            elif final == "E":
                self.r = min(self.rows - 1, self.r + n); self.c = 0
            elif final == "F":
                self.r = max(0, self.r - n); self.c = 0
            elif final == "K":
                mode = nums[0] if nums else 0
                row = self.grid[self.r]
                if mode == 0:
                    for i in range(self.c, self.cols): row[i] = " "
                elif mode == 1:
                    for i in range(0, self.c + 1): row[i] = " "
                else:
                    for i in range(self.cols): row[i] = " "
            elif final == "J":
                mode = nums[0] if nums else 0
                if mode == 2 or mode == 3:
                    self.grid = [[" "] * self.cols for _ in range(self.rows)]
                elif mode == 0:
                    for i in range(self.c, self.cols): self.grid[self.r][i] = " "
                    for rr in range(self.r + 1, self.rows): self.grid[rr] = [" "] * self.cols
        self._text(text[pos:])

    def _text(self, t):
        for ch in t:
            if ch == "\r":
                self.c = 0
            elif ch == "\n":
                self._lf()
            elif ch == "\b":
                self.c = max(0, self.c - 1)
            elif ch == "\t":
                self.c = min(self.cols - 1, (self.c // 8 + 1) * 8)
            elif ch >= " ":
                self._put(ch)

    def lines(self):
        return ["".join(r).rstrip() for r in self.grid]


OPTION_RE = re.compile(r"^\s*(?:\u276f\s*)?(\d+)\.\s+(\[[ \u2714xX]\]\s*)?(.*\S)\s*$")


def parse_question_screen(lines):
    """{question, header, options[{label, description}], multiSelect, tabs[]} from a rendered
    AskUserQuestion screen, or None when no question is showing (e.g. the Submit review)."""
    tabs = []
    tab_i = None
    for i, l in enumerate(lines):
        if "Submit" in l and ("\u2190" in l or "\u2192" in l):
            tab_i = i
            inner = l.replace("\u2190", "").replace("\u2192", "")
            tabs = [t.strip() for t in re.split(r"[\u2610\u2612\u2714\u2611\u25a1\u25a0]", inner) if t.strip() and t.strip() != "Submit"]
    # The question UI sits below the tab bar (several questions) or below the last rule line.
    region = tab_i + 1 if tab_i is not None else 0
    if tab_i is None:
        rules = [i for i, l in enumerate(lines) if l.strip() and set(l.strip()) <= set("\u2500\u2501 ") and len(l.strip()) > 20]
        opts_all = [i for i, l in enumerate(lines) if OPTION_RE.match(l)]
        if opts_all:
            above = [r for r in rules if r < opts_all[-1]]
            # the rule right above the last option block (skip the one between options and "Chat about this")
            blocks = [r for r in above if not any(r < o < opts_all[-1] and OPTION_RE.match(lines[o]).group(3).lower().startswith("chat") for o in opts_all)]
            region = (max(blocks) + 1) if blocks else 0
    opt_idx = [i for i, l in enumerate(lines) if i >= region and OPTION_RE.match(l)]
    if not opt_idx:
        return None
    first = opt_idx[0]
    start = region
    qtext = ""
    for l in lines[start:first]:
        t = l.strip()
        if t and not set(t) <= set("\u2500-\u2501 "):
            qtext = (qtext + " " + t).strip() if qtext else t
    options, multi = [], False
    i = first
    while i < len(lines):
        m = OPTION_RE.match(lines[i])
        if not m:
            i += 1
            continue
        label = m.group(3).strip()
        if m.group(2):
            multi = True
        low = label.lower().rstrip(".")
        if low in ("type something", "chat about this") or low.startswith("type something"):
            break
        desc = []
        j = i + 1
        while j < len(lines) and lines[j].strip() and not OPTION_RE.match(lines[j]) and not set(lines[j].strip()) <= set("\u2500 "):
            desc.append(lines[j].strip())
            j += 1
        options.append({"label": label, "description": " ".join(desc)})
        i = j
    if not qtext or not options:
        return None
    return {"question": qtext, "options": options, "multiSelect": multi, "tabs": tabs}


def read_questions_from_tui(opts, agent_id, tui_factory=None):
    """Renders the agent's screen, walking the question tabs with → (answers nothing)."""
    tui = tui_factory() if tui_factory else Tui(opts, agent_id)
    sc = Screen(40, 120)
    fed = 0
    found = []
    try:
        def look():
            nonlocal_fed[0] = len(tui.buf)
        nonlocal_fed = [0]

        def snapshot():
            sc.feed(bytes(tui.buf[nonlocal_fed[0]:]).decode("utf-8", "replace"))
            nonlocal_fed[0] = len(tui.buf)
            return parse_question_screen(sc.lines())

        q = snapshot()
        # Walk left to the first question: stop when ← no longer changes the question (no wrap)
        # or lands on the Submit tab (wrapped) — in that case step back right once.
        for _ in range(6):
            tui.send(b"\x1b[D", 0.8)
            q2 = snapshot()
            if q2 is None:
                tui.send(b"\x1b[C", 0.8)
                q = snapshot()
                break
            if q and q2["question"] == q["question"]:
                break
            q = q2
        if q is None:
            tui.send(b"\x1b[C", 0.8)
            q = snapshot()
        if q is None:
            return []
        found.append(q)
        total = max(1, len(q.get("tabs") or []))
        for _ in range(total - 1):
            tui.send(b"\x1b[C", 0.9)
            q2 = snapshot()
            if q2 is None or q2["question"] == found[-1]["question"]:
                break
            found.append(q2)
        for _ in range(len(found) - 1):
            tui.send(b"\x1b[D", 0.5)
    finally:
        tui.close()
    for i, q in enumerate(found):
        tabs = q.pop("tabs", [])
        q["header"] = tabs[i] if i < len(tabs) else ""
    return found


class Tui(object):
    """A `claude attach <id>` pty we can drive step by step and read back (ANSI stripped)."""

    def __init__(self, opts, agent_id):
        import pty
        import struct
        import termios
        claude, login_path = resolve_claude(opts.get("claude"))
        if not claude:
            raise HelperError("Claude Code was not found on this machine.")
        env = claude_env(claude, login_path)
        env["TERM"] = "xterm-256color"
        env.pop("NO_COLOR", None)
        self.buf = bytearray()
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                os.chdir(HOME)
                os.execve(claude, [claude, "attach", agent_id], env)
            finally:
                os._exit(127)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        if not self.pump(10.0, until="\u276f".encode("utf-8")):
            self.close()
            raise HelperError("Couldn't open the background agent's terminal.")
        self.pump(1.0)

    def pump(self, seconds, until=None):
        end = time.time() + seconds
        while time.time() < end:
            r, _w, _x = select.select([self.fd], [], [], 0.05)
            if not r:
                continue
            try:
                d = os.read(self.fd, 65536)
            except OSError:
                return False
            if not d:
                return False
            self.buf.extend(d)
            if b"\x1b[6n" in d:
                os.write(self.fd, b"\x1b[1;1R")
            if until is not None and until in self.buf:
                return True
        return until is None

    def send(self, data, wait=0.8):
        os.write(self.fd, data)
        self.pump(wait)

    def mark(self):
        return len(self.buf)

    def text(self, since=0):
        """Screen output since a mark, ANSI-free with ALL whitespace removed (the TUI positions
        words with cursor moves, so spaces are unreliable)."""
        # Not strip_ansi(): it keeps only the last CR segment of a line, and the TUI redraws with
        # bare CRs — that would drop the very text we look for.
        t = ANSI_RE.sub("", bytes(self.buf[since:]).decode("utf-8", "replace"))
        return re.sub(r"\s+", "", t)

    def close(self):
        try:
            os.kill(self.pid, signal.SIGHUP)
        except OSError:
            pass
        time.sleep(0.2)
        try:
            os.kill(self.pid, signal.SIGKILL)
        except OSError:
            pass
        try:
            os.waitpid(self.pid, 0)
        except OSError:
            pass
        try:
            os.close(self.fd)
        except OSError:
            pass


def _norm(s):
    return re.sub(r"\s+", "", s or "")


CHECKBOX_RE = re.compile("\\[(|\u2714|x|X)\\]")


def current_question(text, qs):
    """(index, is_multi) of the question on screen, or (None, None) (e.g. the Submit tab).
    The TUI repaints only characters that changed, so match fuzzily: whole option labels that
    appear, plus the longest run of the question text that survived the diff."""
    import difflib
    best, best_score = None, 0.0
    for i, q in enumerate(qs):
        labels = [_norm(o.get("label")) for o in (q.get("options") or []) if isinstance(o, dict) and o.get("label")]
        score = float(sum(1 for l in labels if l and l in text))
        qn = _norm(q.get("question"))
        if qn:
            m = difflib.SequenceMatcher(None, qn, text, autojunk=False).find_longest_match(0, len(qn), 0, len(text))
            score += 2.0 * m.size / len(qn)
        if score > best_score:
            best, best_score = i, score
    if best is None or best_score < 1.2:
        return None, None
    return best, bool(CHECKBOX_RE.search(text))


def goto_question(tui, qs, target, seen=None):
    """Moves the tab cursor to question target with ←/→, re-reading the screen after every press.
    Position is tracked on the Tui (tui.qcur); multi-select flags are recorded only from a fresh
    repaint of that one question, never from older frames still in the buffer."""
    if not hasattr(tui, "qcur"):
        tui.qcur, multi = current_question(tui.text(0), qs)
        if seen is not None and tui.qcur is not None:
            seen[tui.qcur] = multi
    for _ in range(2 * len(qs) + 2):
        if tui.qcur == target:
            return True
        m = tui.mark()
        tui.send(b"\x1b[D" if (tui.qcur is None or tui.qcur > target) else b"\x1b[C", 0.9)
        c2, multi = current_question(tui.text(m), qs)
        tui.qcur = c2  # None = the Submit tab (or unreadable): keep stepping left
        if seen is not None and c2 is not None:
            seen[c2] = multi
    return tui.qcur == target


def ensure_multi(opts, agent_id, st, tui_factory=None):
    qs = question_block(st)
    if not qs:
        c = read_json(qcache_path(agent_id), {}) or {}
        if isinstance(c.get("questions"), list) and c.get("questions") and c.get("status_at") == st.get("updatedAt"):
            qs = c["questions"]
            return qs, [bool(q.get("multiSelect")) for q in qs]
        qs = read_questions_from_tui(opts, agent_id, tui_factory)
        if not qs:
            return None, None
        try:
            ensure_dir(os.path.dirname(qcache_path(agent_id)))
            write_json_atomic(qcache_path(agent_id), {"questions": qs, "status_at": st.get("updatedAt")})
        except (OSError, IOError):
            pass
        return qs, [bool(q.get("multiSelect")) for q in qs]
    key = question_key(st, qs)
    if all(isinstance(q.get("multiSelect"), bool) for q in qs):
        return qs, [bool(q["multiSelect"]) for q in qs]
    multi = cached_multi(agent_id, key)
    if multi is None or len(multi) != len(qs):
        seen = {}
        tui = tui_factory() if tui_factory else Tui(opts, agent_id)
        try:
            for i in range(len(qs)):
                goto_question(tui, qs, i, seen)
            goto_question(tui, qs, 0, seen)
        finally:
            tui.close()
        if len(seen) != len(qs):
            raise HelperError("Couldn't read the question on the machine. Try again.")
        multi = [bool(seen[i]) for i in range(len(qs))]
        try:
            ensure_dir(os.path.dirname(qcache_path(agent_id)))
            write_json_atomic(qcache_path(agent_id), {"key": key, "multi": multi})
        except (OSError, IOError):
            pass
    return qs, multi


def cmd_native_question(opts, agent_id):
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive or item.get("status") != "waiting":
        raise HelperError("Claude isn't asking anything right now.")
    st = job_state(agent_id)
    qs, _multi = ensure_multi(opts, agent_id, st)
    if not qs:
        raise HelperError("The pending prompt isn't a question.")
    emit(question_pending(agent_id, job_state(agent_id)))


def cmd_native_ask(opts, agent_id):
    """Answers an AskUserQuestion prompt in the agent's TUI, keystroke for keystroke (2.1.283):
    single choice = its digit (advances); multi = digits toggle, → advances; "Type something" =
    digit n+1, paste, Enter; with >1 question or any multi-select, a review screen needs "1" (Submit)."""
    req = read_request()
    answers = req.get("answers")
    if not isinstance(answers, list) or not answers:
        raise HelperError("No answers given.")
    agent_id, item, alive = live_native(opts, agent_id)
    if not alive:
        raise HelperError("That background agent has stopped.")
    if item.get("status") != "waiting":
        raise HelperError("Claude isn't waiting for an answer any more.")
    st = job_state(agent_id)
    qs, multi = ensure_multi(opts, agent_id, st)
    if not qs:
        raise HelperError("The pending prompt isn't a question.")
    questions = [dict(q, multiSelect=bool(multi[i]) if multi and i < len(multi) else bool(q.get("multiSelect")))
                 for i, q in enumerate(qs)]
    plan = question_plan(questions, answers)
    tui = Tui(opts, agent_id)
    try:
        press_answers(tui, qs, questions, plan)
    finally:
        tui.close()
    deadline = time.time() + 6.0
    cur = item
    while time.time() < deadline:
        cur = find_native(opts, agent_id) or cur
        if cur.get("status") != "waiting":
            break
        time.sleep(0.4)
    if cur.get("status") == "waiting":
        raise HelperError("Claude is still waiting — the answer may not have gone through. Try again.")
    emit(native_agent(cur))


def cmd_rewind(opts):
    """Restores files to how they were before a user message (Claude Code's checkpoints).
    stdin {sessionId, messageId, cwd, dryRun, runId?}. Uses the live run's control channel when
    it drives this session; otherwise resumes the session briefly (sending no message) just to
    issue the rewind. Emits {canRewind, filesChanged, insertions, deletions, error?}."""
    req = read_request()
    sid, mid = req.get("sessionId"), req.get("messageId")
    if not sid or not SESSION_ID_RE.match(sid) or not mid or not SESSION_ID_RE.match(mid):
        raise HelperError("Invalid session or message id.")
    dry = bool(req.get("dryRun"))
    rid = "rw_%d" % random.randint(100000, 999999)
    ctl = {"type": "control_request", "request_id": rid,
           "request": {"subtype": "rewind_files", "user_message_id": mid, "dry_run": dry}}
    run_id = req.get("runId")
    if run_id and RUN_ID_RE.match(run_id):
        d = os.path.join(RUNS_DIR, run_id)
        pid = read_pid(d) if os.path.isdir(d) else None
        if pid and pid_alive(pid):
            out_path = os.path.join(d, "out.jsonl")
            start = file_size(out_path)
            with open(os.path.join(d, "in.jsonl"), "a", encoding="utf-8") as f:
                fcntl.flock(f, fcntl.LOCK_EX)
                f.write(json.dumps(ctl) + "\n")
            deadline = time.time() + 25
            while time.time() < deadline:
                with open(out_path, "rb") as f:
                    f.seek(start)
                    for raw in f.read().splitlines():
                        o = parse_line(raw)
                        if o and o.get("type") == "control_response" and (o.get("response") or {}).get("request_id") == rid:
                            return emit_rewind(o["response"])
                time.sleep(0.3)
            raise HelperError("Claude didn't answer the rewind request.")
    cwd = os.path.abspath(os.path.expanduser(req.get("cwd") or HOME))
    claude, login_path = resolve_claude(opts.get("claude"))
    if not claude:
        raise HelperError("Claude Code was not found on this machine.")
    env = claude_env(claude, login_path)
    env["CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING"] = "1"
    p = subprocess.Popen([claude, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                          "--resume", sid], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         cwd=cwd if os.path.isdir(cwd) else HOME, env=env, start_new_session=True)
    try:
        init = {"type": "control_request", "request_id": "init_rw", "request": {"subtype": "initialize"}}
        p.stdin.write((json.dumps(init) + "\n" + json.dumps(ctl) + "\n").encode())
        p.stdin.flush()
        deadline = time.time() + 40
        while time.time() < deadline:
            r, _w, _x = select.select([p.stdout], [], [], 0.5)
            if not r:
                if p.poll() is not None:
                    break
                continue
            raw = p.stdout.readline()
            if not raw:
                break
            o = parse_line(raw)
            if o and o.get("type") == "control_response" and (o.get("response") or {}).get("request_id") == rid:
                return emit_rewind(o["response"])
        raise HelperError("Couldn't reach Claude Code to restore files.")
    finally:
        try:
            p.stdin.close()
        except (OSError, IOError):
            pass
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except OSError:
            pass
        try:
            p.wait(timeout=3)
        except Exception:  # noqa
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except OSError:
                pass


def emit_rewind(resp):
    if resp.get("subtype") == "error":
        emit({"canRewind": False, "filesChanged": [], "insertions": 0, "deletions": 0, "error": resp.get("error") or "Rewind failed."})
        return
    body = resp.get("response") or {}
    emit({"canRewind": bool(body.get("canRewind")), "filesChanged": body.get("filesChanged") or [],
          "insertions": body.get("insertions") or 0, "deletions": body.get("deletions") or 0,
          "error": body.get("error")})


def cmd_native_stop(opts, agent_id):
    agent_id = native_id(agent_id)
    item = find_native(opts, agent_id)
    if not item:
        raise HelperError("That background agent no longer exists.")
    if item.get("pid"):
        out, err, rc = claude_run(opts, ["stop", agent_id], timeout=30)
        if rc != 0:
            raise HelperError(((err or out or "").strip().splitlines() or ["claude stop failed."])[-1])
        deadline = time.time() + 12
        while time.time() < deadline:
            item = find_native(opts, agent_id)
            if not item or not item.get("pid"):
                break
            time.sleep(0.5)
    emit(native_agent(item) if item else {"ok": True, "id": agent_id})


def cmd_native_rm(opts, agent_id):
    agent_id = native_id(agent_id)
    out, err, rc = claude_run(opts, ["rm", agent_id], timeout=45)
    if rc != 0 and find_native(opts, agent_id):
        raise HelperError(((err or out or "").strip().splitlines() or ["claude rm failed."])[-1])
    # Earlier ids of the same conversation (hidden forks) go with it.
    lin = read_lineage()
    removed = [agent_id]
    for old in lin.pop(agent_id, []):
        if NATIVE_ID_RE.match(old or ""):
            claude_run(opts, ["rm", old], timeout=45)
            removed.append(old)
    for k in list(lin.keys()):
        lin[k] = [a for a in lin[k] if a not in removed]
    try:
        write_json_atomic(LINEAGE_PATH, lin)
    except (OSError, IOError):
        pass
    emit({"ok": True, "id": agent_id, "removed": removed})


def cmd_native_timeline(opts, agent_id):
    agent_id = native_id(agent_id)
    emit(timeline_entries(agent_id)[-500:])


def cmd_native_logs(opts, agent_id):
    agent_id = native_id(agent_id)
    out, err, rc = claude_run(opts, ["logs", agent_id], timeout=30, raw=True)
    text = render_terminal(out) if (out or "").strip() else strip_ansi(err)
    if rc != 0 and not (text or "").strip():
        raise HelperError("claude logs exited with status %s." % rc)
    text = (text or "").strip("\n")
    if len(text) > 400 * 1024:
        text = "… (earlier output trimmed)\n" + text[-400 * 1024:]
    emit({"id": agent_id, "text": text})


def native_transcript_path(agent_id, sid):
    pid = terminal_pid(agent_id)
    if pid is not None and not sid:
        reg = read_json(os.path.join(CLAUDE_SESSIONS, "%d.json" % pid), {}) or {}
        sid = reg.get("sessionId") if isinstance(reg, dict) and isinstance(reg.get("sessionId"), str) else None
    st = job_state(agent_id)
    p = st.get("linkScanPath")
    if isinstance(p, str) and os.path.isfile(p) and p.startswith(CLAUDE_PROJECTS + os.sep):
        return p
    sid = sid or st.get("sessionId")
    return find_transcript(sid) if sid and SESSION_ID_RE.match(sid) else None


def cmd_native_follow(opts, agent_id, sid=None):
    """All (filtered) transcript lines so far, then each new one as it is written, until the reader goes.
    `{"tether":"caught-up"}` separates history from live lines."""
    agent_id = native_id(agent_id, terminal_ok=True)
    if sid and not SESSION_ID_RE.match(sid):
        raise HelperError("Invalid session id.")
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass
    gone = ReaderGone()
    out = sys.stdout
    path = None
    waited = 0.0
    while path is None:
        path = native_transcript_path(agent_id, sid)
        if path is None:
            if waited == 0.0:
                out.write('{"tether":"caught-up"}\n')
                out.flush()
            if gone.wait(1.0):
                return
            waited += 1.0
    pos = 0
    pending = b""
    first = True
    last_hb = time.time()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if chunk:
                pending += chunk
                nl = pending.rfind(b"\n")
                if nl >= 0:
                    block = pending[:nl + 1]
                    pending = pending[nl + 1:]
                    pos += len(block)
                    for raw in iter_lines_bytes(block):
                        s = transcript_line_out(raw)
                        if s is not None:
                            out.write(s)
                            out.write("\n")
                    out.flush()
                continue
            if first:
                first = False
                if waited == 0.0:
                    out.write('{"tether":"caught-up"}\n')
                    out.flush()
            if file_size(path) < pos + len(pending):
                return  # replaced: let the app re-attach
            t = time.time()
            if t - last_hb > 15:
                out.write('{"hb":%d}\n' % now_ms())
                out.flush()
                last_hb = t
            if gone.wait(0.5):
                return


# ───────────────────────────────────────── ls ─────────────────────────────────────────

VISIBLE_DOTS = (".github", ".config", ".claude", ".vscode", ".devcontainer")


COMMANDS_TTL = 10 * 60 * 1000


def cmd_commands(opts):
    """Slash commands Claude Code offers in a folder, from its own initialize reply (built-ins, user and
    project commands, skills, plugins, MCP prompts). Cached per folder for a few minutes."""
    cwd = os.path.abspath(os.path.expanduser(opts.get("cwd") or HOME))
    if not os.path.isdir(cwd):
        cwd = HOME
    key = re.sub(r"[^A-Za-z0-9]", "-", cwd)[-120:]
    cache_path = os.path.join(CACHE_DIR, "commands-%s.json" % key)
    cached = read_json(cache_path)
    if isinstance(cached, dict) and cached.get("cwd") == cwd and now_ms() - (cached.get("at") or 0) < COMMANDS_TTL:
        return emit({"commands": cached.get("commands") or []})
    claude, login_path = resolve_claude(opts.get("claude"))
    if not claude:
        raise HelperError("Claude Code was not found on this machine.")
    p = subprocess.Popen([claude, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         cwd=cwd, env=claude_env(claude, login_path), start_new_session=True)
    try:
        init = {"type": "control_request", "request_id": "init_cmds", "request": {"subtype": "initialize"}}
        p.stdin.write((json.dumps(init) + "\n").encode())
        p.stdin.flush()
        deadline = time.time() + 30
        while time.time() < deadline:
            r, _w, _x = select.select([p.stdout], [], [], 0.5)
            if not r:
                if p.poll() is not None:
                    break
                continue
            raw = p.stdout.readline()
            if not raw:
                break
            o = parse_line(raw)
            resp = (o or {}).get("response") or {}
            if (o or {}).get("type") != "control_response" or resp.get("request_id") != "init_cmds":
                continue
            body = resp.get("response") or {}
            cmds = []
            for c in body.get("commands") or []:
                if isinstance(c, dict) and isinstance(c.get("name"), str):
                    cmds.append({"name": c["name"], "description": c.get("description") or "",
                                 "argumentHint": c.get("argumentHint") or ""})
            try:
                ensure_dir(CACHE_DIR)
                write_json_atomic(cache_path, {"cwd": cwd, "at": now_ms(), "commands": cmds})
            except OSError:
                pass
            return emit({"commands": cmds})
        raise HelperError("Claude Code didn't list its commands.")
    finally:
        try:
            p.stdin.close()
        except (OSError, IOError):
            pass
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except OSError:
            pass


def cmd_ls(opts, path=None):
    p = os.path.abspath(os.path.expanduser(path)) if path else HOME
    if not os.path.isdir(p):
        raise HelperError("%s is not a folder." % p)
    try:
        names = os.listdir(p)
    except OSError as e:
        raise HelperError("Cannot open %s: %s" % (p, e.strerror or e))
    entries = []
    for n in names:
        if n.startswith(".") and n not in VISIBLE_DOTS:
            continue
        full = os.path.join(p, n)
        try:
            st = os.stat(full)
        except OSError:
            continue
        is_dir = os.path.isdir(full)
        entries.append({
            "name": n, "path": full, "isDir": is_dir,
            "isGitRepo": is_dir and os.path.exists(os.path.join(full, ".git")),
            "modifiedAt": int(st.st_mtime * 1000),
        })
    entries.sort(key=lambda e: (not e["isDir"], e["name"].lower()))
    if len(entries) > 1000:
        entries = entries[:1000]
    parent = os.path.dirname(p) if p != "/" else None
    emit({"path": p, "parent": parent, "entries": entries})


# ───────────────────────────────────────── Claude Code daemon client ─────────────────────────────────────────
#
# The Claude Code daemon (`claude daemon`, 2.1.286, proto 1) owns every background session. Its control
# socket speaks one JSON request line -> one JSON reply line; `subscribe` keeps streaming JSON lines and
# `attach` keeps streaming the session's terminal after its reply line. Same-uid peers only.

DAEMON_PROTO = 1
DAEMON_CONNECT_TIMEOUT = 3.0
DAEMON_REPLY_TIMEOUT = 10.0
SHORT_RE = re.compile(r"^[a-f0-9]{8}$")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
DAEMON_SOURCES = ("shell", "slash", "fleet", "spare", "respawn")

# Daemon error codes -> the helper protocol's codes. Anything unlisted becomes EDAEMON (the daemon's own
# code stays in "daemonCode").
DAEMON_CODE_MAP = {
    "EAUTH": "EAUTH",
    "EPROTO": "EPROTO",
    "ENOJOB": "ENOSESSION",
    "ETIMEOUT": "ETIMEOUT",
    "ENOCONN": "ENODAEMON",
}

# Daemon frame codec: 4-byte big-endian payload length, 1-byte kind (0 data, 1 ctrl JSON), payload. NOTE:
# verified live on 2.1.286/2.1.287, control-socket `attach` is NOT framed: after its JSON reply line the
# socket carries raw pty bytes both ways (DaemonAttach below). The codec is the daemon's internal
# worker-pty framing, kept (and unit-tested) in case a later proto frames attach.
FRAME_HEADER = 5
FRAME_DATA = 0
FRAME_CTRL = 1
FRAME_MAX = 1024 * 1024


class DaemonError(HelperError):
    """A daemon failure with a protocol error code (ENODAEMON, EAUTH, EPROTO, ENOSESSION, ETIMEOUT, EDAEMON,
    ...). daemon_code is the daemon's own code, when it sent one."""

    def __init__(self, message, code="EDAEMON", daemon_code=None):
        HelperError.__init__(self, message)
        self.code = code
        self.daemon_code = daemon_code


def daemon_error_from_reply(reply, op=None):
    """DaemonError for a {ok:false, error, code} reply."""
    if not isinstance(reply, dict):
        return DaemonError("The Claude Code daemon sent an unreadable reply.", "EDAEMON")
    dcode = reply.get("code") if isinstance(reply.get("code"), str) else None
    msg = reply.get("error") if isinstance(reply.get("error"), str) and reply.get("error") else None
    if not msg:
        msg = "The Claude Code daemon refused %s." % (op or "the request")
    if dcode == "EPROTO":
        msg = ("This machine's Claude Code daemon speaks protocol %s (version %s); Tether speaks %d. "
               "Update Claude Code or Tether." % (reply.get("serverProto"), reply.get("serverVersion"), DAEMON_PROTO))
    return DaemonError(msg, DAEMON_CODE_MAP.get(dcode, "EDAEMON"), dcode)


def claude_config_dir():
    d = os.environ.get("CLAUDE_CONFIG_DIR")
    return os.path.abspath(os.path.expanduser(d)) if d else os.path.join(HOME, ".claude")


def daemon_runtime_root():
    """/tmp/cc-daemon-<uid> (Termux: $PREFIX/tmp/cc-daemon-<uid>)."""
    base = "/tmp"
    if os.environ.get("TERMUX_VERSION") and os.environ.get("PREFIX"):
        base = os.path.join(os.environ["PREFIX"], "tmp")
    return os.path.join(base, "cc-daemon-%d" % os.getuid())


def daemon_socket_hash(config_dir=None):
    """The daemon names its runtime dir after sha256(resolved config dir)[:8]."""
    import hashlib
    p = os.path.abspath(config_dir or claude_config_dir())
    return hashlib.sha256(p.encode("utf-8")).hexdigest()[:8]


def daemon_lock():
    """~/.claude/daemon.lock: {pid, version, startedAt, origin, ...} or None."""
    d = read_json(os.path.join(claude_config_dir(), "daemon.lock"), None)
    return d if isinstance(d, dict) else None


def unix_socket_inodes(path):
    """Inodes of listening unix sockets bound at path (Linux /proc/net/unix), or None off Linux."""
    text = read_text("/proc/net/unix", None)
    if text is None:
        return None
    out = set()
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 8 and parts[7] == path:
            out.add(parts[6])
    return out


def pid_holds_socket(pid, path):
    """True / False when /proc says whether pid has the socket bound at path open, None when unknown."""
    inodes = unix_socket_inodes(path)
    if inodes is None or not pid:
        return None
    fd_dir = "/proc/%d/fd" % int(pid)
    try:
        fds = os.listdir(fd_dir)
    except OSError:
        return None
    for fd in fds:
        try:
            link = os.readlink(os.path.join(fd_dir, fd))
        except OSError:
            continue
        if link.startswith("socket:[") and link[8:-1] in inodes:
            return True
    return False


def find_daemon_socket():
    """Path of this user's daemon control socket, or None. The daemon puts it at
    /tmp/cc-daemon-<uid>/<sha256(config dir)[:8]>/control.sock; when that is missing (another config dir
    resolution), glob, preferring the socket held by the daemon.lock pid, then the newest."""
    root = daemon_runtime_root()
    expected = os.path.join(root, daemon_socket_hash(), "control.sock")
    if os.path.exists(expected):
        return expected
    found = [p for p in glob.glob(os.path.join(root, "*", "control.sock")) if os.path.exists(p)]
    if not found:
        return None
    if len(found) > 1:
        lock = daemon_lock() or {}
        pid = lock.get("pid") if isinstance(lock.get("pid"), int) else None
        if pid:
            held = [p for p in found if pid_holds_socket(pid, p)]
            if held:
                return held[0]
        found.sort(key=lambda p: -mtime_ms(p))
    return found[0]


def daemon_control_key():
    """The hex control key the daemon checks on dispatch / reply / attach / permission-response, or None."""
    k = read_text(os.path.join(claude_config_dir(), "daemon", "control.key"), None)
    k = k.strip() if k else None
    return k or None


def new_nonce():
    return "%08x" % random.getrandbits(32)


class LineBuffer(object):
    """Splits a byte stream into complete lines; the rest waits for more bytes."""

    def __init__(self):
        self.buf = b""

    def feed(self, data):
        self.buf += data
        out = []
        while True:
            i = self.buf.find(b"\n")
            if i < 0:
                return out
            out.append(self.buf[:i])
            self.buf = self.buf[i + 1:]


def decode_json_line(raw):
    """A reply line -> dict; DaemonError(EDAEMON) on anything else."""
    try:
        obj = json.loads(raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw)
    except ValueError:
        raise DaemonError("The Claude Code daemon sent an unreadable reply.", "EDAEMON")
    if not isinstance(obj, dict):
        raise DaemonError("The Claude Code daemon sent an unreadable reply.", "EDAEMON")
    return obj


def encode_frame(kind, payload):
    """One attach frame: 4-byte BE length, 1-byte kind (0 data, 1 ctrl JSON), payload."""
    if isinstance(payload, dict):
        payload = json.dumps(payload, separators=(",", ":"))
    if not isinstance(payload, bytes):
        payload = payload.encode("utf-8")
    n = len(payload)
    return bytes(bytearray([(n >> 24) & 255, (n >> 16) & 255, (n >> 8) & 255, n & 255, kind & 255])) + payload


class FrameDecoder(object):
    """Incremental attach-frame decoder. feed(bytes) -> [(kind, payload)] where payload is bytes for data
    frames and a dict for ctrl frames. Raises DaemonError(EDAEMON) on a malformed stream."""

    def __init__(self):
        self.buf = b""

    def feed(self, data):
        self.buf += data
        out = []
        while len(self.buf) >= FRAME_HEADER:
            b = bytearray(self.buf[:FRAME_HEADER])
            n = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
            kind = b[4]
            if n > FRAME_MAX:
                raise DaemonError("attach frame too large (%d bytes)" % n, "EDAEMON")
            if len(self.buf) < FRAME_HEADER + n:
                break
            payload = self.buf[FRAME_HEADER:FRAME_HEADER + n]
            self.buf = self.buf[FRAME_HEADER + n:]
            if kind == FRAME_DATA:
                out.append((FRAME_DATA, payload))
            elif kind == FRAME_CTRL:
                try:
                    out.append((FRAME_CTRL, json.loads(payload.decode("utf-8"))))
                except ValueError:
                    raise DaemonError("attach sent a bad ctrl frame", "EDAEMON")
            else:
                raise DaemonError("attach sent an unknown frame kind %d" % kind, "EDAEMON")
        return out


class DaemonConn(object):
    """One connection to the control socket (each request opens its own: the daemon ends the stream after
    a one-shot reply)."""

    def __init__(self, path=None, timeout=DAEMON_CONNECT_TIMEOUT):
        self.path = path or find_daemon_socket()
        if not self.path:
            raise DaemonError("The Claude Code daemon is not running on this machine.", "ENODAEMON")
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        try:
            self.sock.connect(self.path)
        except socket.timeout:
            self.close()
            raise DaemonError("The Claude Code daemon did not accept a connection in time.", "ETIMEOUT")
        except (OSError, IOError) as e:
            self.close()
            if getattr(e, "errno", None) in (errno.ENOENT, errno.ECONNREFUSED, errno.ENOTSOCK):
                raise DaemonError("The Claude Code daemon is not running on this machine.", "ENODAEMON")
            raise DaemonError("Couldn't reach the Claude Code daemon: %s" % e, "ENODAEMON")
        self.lines = LineBuffer()
        self.pending = []

    def close(self):
        try:
            self.sock.close()
        except (OSError, IOError, AttributeError):
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def send(self, obj):
        data = (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            self.sock.sendall(data)
        except (OSError, IOError) as e:
            raise DaemonError("Couldn't talk to the Claude Code daemon: %s" % e, "ENODAEMON")

    def recv(self, timeout):
        """Raw bytes, b"" at EOF. Raises DaemonError(ETIMEOUT)."""
        self.sock.settimeout(timeout)
        try:
            return self.sock.recv(65536)
        except socket.timeout:
            raise DaemonError("The Claude Code daemon did not answer in time.", "ETIMEOUT")
        except (OSError, IOError) as e:
            if getattr(e, "errno", None) in (errno.ECONNRESET, errno.EPIPE):
                return b""
            raise DaemonError("Couldn't talk to the Claude Code daemon: %s" % e, "ENODAEMON")

    def read_line(self, timeout=DAEMON_REPLY_TIMEOUT):
        """The next JSON line as a dict, or None at EOF."""
        deadline = time.time() + timeout
        while not self.pending:
            left = deadline - time.time()
            if left <= 0:
                raise DaemonError("The Claude Code daemon did not answer in time.", "ETIMEOUT")
            data = self.recv(left)
            if not data:
                if self.lines.buf.strip():
                    tail, self.lines.buf = self.lines.buf, b""
                    return decode_json_line(tail)
                return None
            self.pending.extend(l for l in self.lines.feed(data) if l.strip())
        return decode_json_line(self.pending.pop(0))

    def take_buffered(self):
        """Bytes already read past the reply line (start of a stream)."""
        rest = b"\n".join(self.pending) + (b"\n" if self.pending else b"") + self.lines.buf
        self.pending = []
        self.lines.buf = b""
        return rest


def daemon_request(req, timeout=DAEMON_REPLY_TIMEOUT, path=None, retry_starting=True):
    """Send one request, return its ok reply dict. Raises DaemonError mapped to the protocol codes.
    Retries ESTARTING / ERESPAWNING (daemon adopting its workers after a restart or self-upgrade) for up to ~8 s,
    as the CLI does."""
    req = dict(req)
    req.setdefault("proto", DAEMON_PROTO)
    tries = 40 if retry_starting else 1
    while True:
        with DaemonConn(path) as c:
            c.send(req)
            reply = c.read_line(timeout)
        if reply is None:
            raise DaemonError("The Claude Code daemon closed the connection without answering.", "EDAEMON")
        if reply.get("ok") is True:
            return reply
        tries -= 1
        if reply.get("code") in ("ESTARTING", "ERESPAWNING") and tries > 0:
            time.sleep(0.2)
            continue
        raise daemon_error_from_reply(reply, req.get("op"))


def need_key():
    k = daemon_control_key()
    if not k:
        raise DaemonError("The Claude Code daemon control key (~/.claude/daemon/control.key) is missing.", "EAUTH")
    return k


def daemon_ping(path=None):
    """{ok, op, version, proto}; EPROTO when the daemon speaks another protocol."""
    r = daemon_request({"op": "ping"}, timeout=5, path=path)
    if r.get("proto") != DAEMON_PROTO:
        raise DaemonError("This machine's Claude Code daemon speaks protocol %s (version %s); Tether speaks %d. "
                          "Update Claude Code or Tether." % (r.get("proto"), r.get("version"), DAEMON_PROTO), "EPROTO")
    return r


def daemon_list():
    """The daemon's live job records: [{short, nonce, sessionId, pid, cwd, state, tempo, detail, needs,
    intent, name, source, cliVersion, startedAt, createdAt, outcome?, dying?}]."""
    jobs = daemon_request({"op": "list"}).get("jobs")
    return jobs if isinstance(jobs, list) else []


def daemon_has(short):
    """{alive, present, ready}."""
    return daemon_request({"op": "has", "short": short})


def daemon_dispatch(d, timeout_ms=5000):
    """Dispatch a launch spec over the socket. The daemon waits for the worker's ack itself (up to
    timeout_ms, capped at 30 s) and answers {ok, op:"dispatch", short, pid, messagingSock, via}."""
    d = dict(d)
    d.setdefault("nonce", new_nonce())
    req = {"op": "dispatch", "d": d, "timeoutMs": int(timeout_ms), "auth": need_key()}
    r = daemon_request(req, timeout=timeout_ms / 1000.0 + 5)
    r.setdefault("nonce", d["nonce"])
    return r


def daemon_await_ack(short, nonce=None, timeout_ms=5000):
    req = {"op": "await-ack", "short": short, "timeoutMs": int(timeout_ms)}
    if nonce:
        req["nonce"] = nonce
    return daemon_request(req, timeout=timeout_ms / 1000.0 + 5)


def daemon_reply(short, text, next_turn=False):
    """Type text into a live worker (bracketed paste + Enter, or the rendezvous socket when it is blocked /
    next_turn). ENOSESSION when the worker is retired: dispatch a resume with the same short first."""
    req = {"op": "reply", "short": short, "text": text, "auth": need_key()}
    if next_turn:
        req["nextTurn"] = True
    return daemon_request(req)


def daemon_kill(short, signal_name=None, evict=False):
    req = {"op": "kill", "short": short}
    if signal_name:
        req["signal"] = signal_name
    if evict:
        req["evict"] = True
    return daemon_request(req)


def daemon_resize(short, cols, rows, attach_id=None):
    req = {"op": "resize", "short": short, "cols": int(cols), "rows": int(rows)}
    if attach_id:
        req["attachId"] = attach_id
    return daemon_request(req)


def daemon_subscribe(short, tail=None, timeout=None, path=None):
    """Generator of subscribe events: {"type":"snapshot", record, streamTail:[str]} first, then
    {"type":"stream", line:str} (raw pty text), {"type":"state", patch:{}}, {"type":"settled", outcome}.
    Ends when the daemon closes the stream (after "settled"). timeout: max seconds of silence (None =
    forever) before DaemonError(ETIMEOUT)."""
    req = {"proto": DAEMON_PROTO, "op": "subscribe", "short": short}
    if tail is not None:
        req["tail"] = int(tail)
    c = DaemonConn(path)
    try:
        c.send(req)
        first = c.read_line(DAEMON_REPLY_TIMEOUT)
        if first is None:
            raise DaemonError("The Claude Code daemon closed the subscription without answering.", "EDAEMON")
        if first.get("ok") is False:
            raise daemon_error_from_reply(first, "subscribe")
        yield first
        while True:
            ev = c.read_line(timeout if timeout is not None else 10 ** 9)
            if ev is None:
                return
            yield ev
    finally:
        c.close()


class DaemonAttach(object):
    """A live `attach` (like `claude attach`): the reply dict in .reply ({ok, op, decModes, via, booting,
    tempo, state, cached, stale, workerCliVersion}), then the session's terminal as RAW bytes (unframed),
    starting with a repaint. Keys go back raw with send_keys() (e.g. b"\x1b[Z" Shift+Tab, b"\x1b" Esc).
    close() detaches; the session keeps running."""

    def __init__(self, short, cols=120, rows=40, caps=None, attach_id=None, path=None):
        self.short = short
        self.conn = DaemonConn(path)
        req = {"proto": DAEMON_PROTO, "op": "attach", "short": short, "auth": need_key(),
               "cols": int(cols), "rows": int(rows),
               "caps": caps if caps is not None else {"terminal": None, "mux": None, "ssh": True}}
        if attach_id:
            req["attachId"] = attach_id
        try:
            self.conn.send(req)
            reply = self.conn.read_line(DAEMON_REPLY_TIMEOUT)
            if reply is None:
                raise DaemonError("The Claude Code daemon closed the attach without answering.", "EDAEMON")
            if reply.get("ok") is not True:
                raise daemon_error_from_reply(reply, "attach")
        except Exception:
            self.conn.close()
            raise
        self.reply = reply
        self.backlog = self.conn.take_buffered()

    def read(self, timeout=1.0):
        """Terminal bytes available within timeout (b"" when none arrived); None at EOF."""
        if self.backlog:
            out, self.backlog = self.backlog, b""
            return out
        try:
            r, _w, _x = select.select([self.conn.sock], [], [], timeout)
        except (OSError, IOError, ValueError):
            return None
        if not r:
            return b""
        try:
            data = self.conn.recv(1.0)
        except DaemonError:
            return None
        return data if data else None

    def send_keys(self, data):
        if not isinstance(data, bytes):
            data = data.encode("utf-8")
        try:
            self.conn.sock.sendall(data)
        except (OSError, IOError) as e:
            raise DaemonError("The attached session went away: %s" % e, "ENOSESSION")

    def close(self):
        self.conn.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def daemon_prompt_spec(cwd, prompt, flags=None, session_id=None, source="fleet", name=None, env=None,
                       cols=None, rows=None):
    """Launch spec for a NEW session: `claude --session-id <uuid> [flags] -- <prompt>` under the daemon."""
    import uuid
    sid = session_id or str(uuid.uuid4())
    flags = list(flags or [])
    args = ["--session-id", sid] + flags + (["--", prompt] if prompt else [])
    d = {"proto": DAEMON_PROTO, "short": sid[:8], "sessionId": sid, "createdAt": now_ms(), "source": source,
         "cwd": cwd, "launch": {"mode": "prompt", "args": args}, "env": dict(env or {}), "isolation": "none",
         "respawnFlags": flags, "seed": {"intent": prompt or ""}}
    if name:
        d["seed"]["name"] = name
    if cols and rows:
        d["cols"], d["rows"] = int(cols), int(rows)
    return d


def daemon_resume_spec(session_id, cwd, flags=None, transcript_path=None, source="fleet", intent="", env=None,
                       cols=None, rows=None):
    """Launch spec that wakes an existing session under its own id (fork:false): same short, same transcript."""
    flags = list(flags or [])
    launch = {"mode": "resume", "sessionId": session_id, "fork": False, "flagArgs": flags}
    if transcript_path:
        launch["transcriptPath"] = transcript_path
    d = {"proto": DAEMON_PROTO, "short": session_id[:8], "sessionId": session_id, "createdAt": now_ms(),
         "source": source, "cwd": cwd, "launch": launch, "env": dict(env or {}), "isolation": "none",
         "respawnFlags": flags, "seed": {"intent": intent or ""}}
    if cols and rows:
        d["cols"], d["rows"] = int(cols), int(rows)
    return d


def claude_auth_status():
    """"ok" when Claude Code has credentials here, "needs_login" when it has none, "unknown" when they may
    live in an OS keychain we can't see."""
    if os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"):
        return "ok"
    if file_size(os.path.join(claude_config_dir(), ".credentials.json")) > 0:
        return "ok"
    data = read_json(CLAUDE_JSON, {}) or {}
    if isinstance(data.get("oauthAccount"), dict) or data.get("primaryApiKey"):
        return "unknown" if platform.system() == "Darwin" else "ok"
    return "needs_login" if platform.system() != "Darwin" else "unknown"


def daemon_status():
    """{running, proto, version, auth, socket?, pid?, error?, code?} — never raises."""
    out = {"running": False, "proto": None, "version": None, "auth": claude_auth_status()}
    try:
        r = daemon_ping()
        out.update({"running": True, "proto": r.get("proto"), "version": r.get("version")})
    except DaemonError as e:
        out.update({"error": str(e), "code": e.code})
        if e.code == "EPROTO":
            out["running"] = True
    lock = daemon_lock()
    if lock and isinstance(lock.get("pid"), int):
        out["pid"] = lock["pid"]
    if out["running"] and not daemon_control_key():
        out["keyMissing"] = True
    return out


def cmd_daemon_status(opts):
    emit(daemon_status())


# ───────────────────────────────────────── sessions: the one-session model (HELPER_VERSION 2) ─────────────────────────────────────────
#
# One kind of thing, a session, keyed by its session id (short = first 8 hex). Sources: the daemon's job dirs
# (~/.claude/jobs/<short>/state.json), the session registry (~/.claude/sessions/<pid>.json: kind bg|interactive),
# the daemon's live job records (`list`), and transcripts in ~/.claude/projects with no job at all. Every write
# goes through the daemon control socket; Tether never runs `claude` for these (decision 1), except to start the
# daemon itself when it is not running.

import collections
import shutil
import unicodedata

TASKS_DIR = os.path.join(HOME, ".claude", "tasks")
REMOVED_PATH = os.path.join(TETHER_DIR, "removed_sessions.json")
SESSIONS_LIMIT = 60
WATCH_LIMIT = 200
FOLLOW_TAIL_CHUNKS = 100000  # the daemon keeps <= 256 KB of pty output per job; ask for all of it
KEY_COLS, KEY_ROWS = 120, 40  # pty size while the helper is attached to press keys
AGENT_ID_RE = re.compile(r"^[0-9A-Za-z_-]{1,64}$")
# respawnFlags that make sense again on a wake (a resume): everything else (the first prompt, -n, -w, ...) is
# a launch-time choice and must not be replayed.
RESUME_VALUE_FLAGS = ("--model", "--permission-mode", "--agent", "--effort", "--fallback-model", "--add-dir",
                      "--settings", "--mcp-config", "--allowedTools", "--allowed-tools", "--disallowedTools",
                      "--disallowed-tools", "--append-system-prompt")
RESUME_BOOL_FLAGS = ("--dangerously-skip-permissions", "--strict-mcp-config", "--verbose")


def coded_error(message, code):
    """A HelperError carrying a protocol code (EHELD, ENOSESSION, EUNTRUSTED, ...)."""
    return DaemonError(message, code)


def is_session_arg(v):
    """A session id (uuid) or short (8 hex): the new protocol. Tether run ids ("r…") are the old one."""
    v = (v or "").strip().lower()
    return bool(UUID_RE.match(v) or SHORT_RE.match(v))


def resume_flags(flags):
    """The respawnFlags worth replaying on a wake (--model, --permission-mode, ...)."""
    out = []
    if not isinstance(flags, list):
        return out
    i = 0
    while i < len(flags):
        f = flags[i]
        if not isinstance(f, str):
            i += 1
            continue
        name = f.split("=", 1)[0]
        if name in RESUME_VALUE_FLAGS:
            if "=" in f:
                out.append(f)
            elif i + 1 < len(flags) and isinstance(flags[i + 1], str):
                out += [f, flags[i + 1]]
                i += 1
        elif f in RESUME_BOOL_FLAGS:
            out.append(f)
        i += 1
    return out


# ── sources ──

def job_states():
    """short -> state.json of every daemon job dir that has one (spares have none)."""
    out = {}
    try:
        names = os.listdir(JOBS_DIR)
    except OSError:
        return out
    for n in names:
        if not NATIVE_ID_RE.match(n):
            continue
        st = read_json(os.path.join(JOBS_DIR, n, "state.json"), None)
        if isinstance(st, dict):
            out[n] = st
    return out


def registry_entries():
    """Live entries of ~/.claude/sessions/<pid>.json (dead pids skipped)."""
    out = []
    try:
        names = os.listdir(CLAUDE_SESSIONS)
    except OSError:
        return out
    for n in names:
        if not n.endswith(".json"):
            continue
        r = read_json(os.path.join(CLAUDE_SESSIONS, n), None)
        if not isinstance(r, dict):
            continue
        pid = r.get("pid") if isinstance(r.get("pid"), int) else None
        if pid is None:
            try:
                pid = int(n[:-5])
            except ValueError:
                continue
        if not pid_alive(pid, r.get("procStart")):
            continue
        r["pid"] = pid
        out.append(r)
    return out


def daemon_records():
    """short -> the daemon's job record, or None when the daemon can't be asked."""
    try:
        return dict((j["short"], j) for j in daemon_list() if isinstance(j, dict) and isinstance(j.get("short"), str))
    except DaemonError:
        return None


def record_live(rec):
    return bool(rec) and not rec.get("dying") and not rec.get("outcome")


def read_removed():
    v = read_json(REMOVED_PATH, {}) or {}
    return v if isinstance(v, dict) else {}


class Sources(object):
    """One read of everything a Session is built from."""

    def __init__(self, daemon=True, jobs=None, registry=None, records=None):
        self.jobs = job_states() if jobs is None else jobs
        self.registry = registry_entries() if registry is None else registry
        self.daemon = (daemon_records() if daemon else None) if records is None else records
        self.removed = read_removed()
        self._runners = None

    def runner_pids(self):
        if self._runners is None:
            self._runners = tether_runner_pids()
        return self._runners

    def slots(self):
        """sessionId -> {sid, jobs:[(short, st)], rec, term, bg} for every session the daemon or a terminal knows."""
        by = {}

        def slot(sid):
            return by.setdefault(sid, {"sid": sid, "jobs": [], "rec": None, "term": None, "bg": None})

        for short, st in self.jobs.items():
            sid = st.get("sessionId")
            if isinstance(sid, str) and UUID_RE.match(sid):
                slot(sid)["jobs"].append((short, st))
        for short, rec in (self.daemon or {}).items():
            sid = rec.get("sessionId")
            if not (isinstance(sid, str) and UUID_RE.match(sid)):
                continue
            if rec.get("source") == "spare" and short not in self.jobs:
                continue  # a pre-warmed worker waiting for its first dispatch: not a session yet
            s = slot(sid)
            if s["rec"] is None or (record_live(rec) and not record_live(s["rec"])):
                s["rec"] = rec
        for r in self.registry:
            sid = r.get("sessionId")
            if not (isinstance(sid, str) and UUID_RE.match(sid)):
                continue
            if r.get("kind") == "interactive":
                if self._runners is not None or os.path.isdir(RUNS_DIR):
                    if parent_pid(r["pid"]) in self.runner_pids():
                        continue  # a Tether live run's `claude -p`: listed by `runs`
                slot(sid)["term"] = r
            elif r.get("kind") == "bg" and sid in by:
                by[sid]["bg"] = r
        return by


# ── transcript facts (cached by size + mtime) ──

def scan_tail_facts(data):
    """lastText, model, permissionMode, PR links from the end of a transcript."""
    f = {"lastText": None, "model": None, "permissionMode": None, "prs": []}
    for raw in iter_lines_bytes(data):
        if b'"permission-mode"' in raw:
            o = parse_line(raw)
            if o and o.get("type") == "permission-mode" and isinstance(o.get("permissionMode"), str):
                f["permissionMode"] = o["permissionMode"]
        elif b'"pr-link"' in raw:
            o = parse_line(raw)
            if o and o.get("type") == "pr-link" and o.get("prUrl"):
                pr = {"id": str(o.get("prNumber") or ""), "href": o["prUrl"], "kind": "pr"}
                if pr not in f["prs"]:
                    f["prs"].append(pr)
        elif b'"assistant"' in raw and b'"isSidechain":true' not in raw:
            o = parse_line(raw)
            msg = o.get("message") if o and o.get("type") == "assistant" and isinstance(o.get("message"), dict) else None
            if not msg or o.get("isSidechain"):
                continue
            if isinstance(msg.get("model"), str) and not msg["model"].startswith("<"):
                f["model"] = msg["model"]
            parts = [b.get("text") for b in (msg.get("content") or []) if isinstance(b, dict)
                     and b.get("type") == "text" and isinstance(b.get("text"), str) and b["text"].strip()]
            if parts:
                f["lastText"] = one_line("\n".join(parts), LAST_TEXT_CAP)
    return f


class TranscriptFacts(object):
    """Head facts (SessionIndex) + tail facts per transcript, cached by (size, mtime)."""

    def __init__(self):
        self.index = SessionIndex("sessions_v2.json", count=False)
        self.path = os.path.join(CACHE_DIR, "session_tails.json")
        data = read_json(self.path, {}) or {}
        self.tails = data.get("files") if isinstance(data.get("files"), dict) and data.get("v") == 1 else {}
        self.dirty = False

    def info(self, path, st):
        return self.index.info(path, st)

    def tail(self, path, st):
        cur = self.tails.get(path)
        size, mt = st.st_size, int(st.st_mtime * 1000)
        if cur and cur.get("size") == size and cur.get("mtime") == mt:
            return cur
        facts = scan_tail_facts(read_tail(path, TAIL_BYTES))
        if cur and cur.get("size", 0) <= size:
            for k in ("lastText", "model", "permissionMode"):
                if facts.get(k) is None:
                    facts[k] = cur.get(k)
            facts["prs"] = (cur.get("prs") or []) + [p for p in facts["prs"] if p not in (cur.get("prs") or [])]
        facts.update(size=size, mtime=mt)
        self.tails[path] = facts
        self.dirty = True
        return facts

    def save(self):
        self.index.save()
        if not self.dirty:
            return
        for k in list(self.tails.keys()):
            if not os.path.exists(k):
                del self.tails[k]
        try:
            ensure_dir(CACHE_DIR)
            write_json_atomic(self.path, {"v": 1, "files": self.tails})
            self.dirty = False
        except (OSError, IOError):
            pass


def all_transcripts(want_cwd=None):
    """[(path, sid, stat)] of top-level transcripts, newest first (one project dir when want_cwd is given)."""
    files = []
    if not os.path.isdir(CLAUDE_PROJECTS):
        return files
    if want_cwd:
        d = os.path.join(CLAUDE_PROJECTS, project_dir_name(want_cwd))
        if os.path.isdir(d):
            files.extend(list_session_files(d))
    else:
        try:
            names = os.listdir(CLAUDE_PROJECTS)
        except OSError:
            names = []
        for name in names:
            d = os.path.join(CLAUDE_PROJECTS, name)
            if os.path.isdir(d):
                files.extend(list_session_files(d))
    files.sort(key=lambda t: t[2].st_mtime, reverse=True)
    return files


def transcript_for(sid, st=None):
    """Transcript path of a session: the job's linkScanPath when it is a real transcript, else a glob."""
    p = (st or {}).get("linkScanPath")
    if isinstance(p, str) and p.endswith(sid + ".jsonl") and p.startswith(CLAUDE_PROJECTS + os.sep) and os.path.isfile(p):
        return p
    return find_transcript(sid)


# ── the Session object ──

def best_job(slot):
    """(short, state.json) of the job that speaks for this session: the live record's, else the newest."""
    rec = slot.get("rec")
    jobs = slot.get("jobs") or []
    if rec:
        for short, st in jobs:
            if short == rec.get("short"):
                return short, st
    if jobs:
        return max(jobs, key=lambda j: iso_to_ms(j[1].get("updatedAt")) or 0)
    if rec:
        return rec.get("short"), {}
    return None, {}


def session_state(held, live, st, rec, term, bg):
    if held == "terminal":
        return {"busy": "working", "waiting": "needs_you"}.get((term or {}).get("status"), "idle")
    if live:
        tempo = (rec or {}).get("tempo") or st.get("tempo")
        status = (bg or {}).get("status")
        if tempo == "blocked" or status == "waiting":
            return "needs_you"
        if tempo == "active" or status == "busy":
            return "working"
        if ((rec or {}).get("state") or st.get("state")) in ("starting", "resuming"):
            return "working"
        return "idle"
    end = (rec or {}).get("outcome") or st.get("state")
    if end in ("failed", "crashed", "error"):
        return "failed"
    return "done"


def session_dialog(short, st, reg, tpath, cwd):
    """pending {kind:"dialog", ...} for a blocking startup / session dialog (decision 5). Filled in by H4b."""
    return None


def session_pending(short, st, reg, tpath, cwd):
    """What a needs_you session is waiting for: a question, a tool permission, or a dialog."""
    wf = (reg or {}).get("waitingFor")
    q = question_pending(short, st) if short else None
    if q:
        return dict(q, kind="question")
    if wf in (None, "permission prompt") and tpath:
        pt = pending_tool_use(tpath, cwd)
        if pt:
            return dict(pt, kind="question" if pt.get("toolName") == "AskUserQuestion" else "permission")
    d = session_dialog(short, st, reg, tpath, cwd)
    if d:
        return d
    return None


def make_session(slot, facts=None, tfile=None):
    """The protocol's Session object for one slot. tfile = (path, stat) when the caller already found it."""
    sid = slot["sid"]
    short, st = best_job(slot)
    st = st or {}
    rec, term, bg = slot.get("rec"), slot.get("term"), slot.get("bg")
    if term and term.get("parkedJobId"):
        term = None  # parked into a background job: the daemon holds it now
    path = tfile[0] if tfile else transcript_for(sid, st)
    tst = tfile[1] if tfile else None
    if path and tst is None:
        try:
            tst = os.stat(path)
        except OSError:
            path = None
    info, tail = {}, {}
    if path and tst is not None:
        facts = facts or TranscriptFacts()
        info = facts.info(path, tst)
        tail = facts.tail(path, tst)
    live = record_live(rec)
    held = "terminal" if term else ("daemon" if live else "none")
    state = session_state(held, live, st, rec, term, bg)
    reg = term or bg or {}
    cwd = info.get("cwd") or st.get("cwd") or (rec or {}).get("cwd") or reg.get("cwd") or \
        (decode_dir_name(os.path.basename(os.path.dirname(path))) if path else HOME)
    waiting = reg.get("waitingFor") if isinstance(reg.get("waitingFor"), str) else None
    if state == "needs_you" and not waiting:
        waiting = one_line((rec or {}).get("needs") or st.get("needs"), 240) or None
    if state != "needs_you":
        waiting = None
    flags = st.get("respawnFlags")
    reg_name = reg.get("name") if reg.get("nameSource") not in (None, "derived") else None
    name = st.get("name") or (rec or {}).get("name") or reg_name or info.get("customTitle") or info.get("aiTitle") or \
        (session_title(info) if info else None) or sid[:8]
    in_flight = st.get("inFlight") if isinstance(st.get("inFlight"), dict) else {}
    output = st.get("output") if isinstance(st.get("output"), dict) else {}
    jd = os.path.join(JOBS_DIR, short) if short and NATIVE_ID_RE.match(short) else None
    updated = max([0, iso_to_ms(st.get("updatedAt")) or 0, mtime_ms(path) if path else 0,
                   mtime_ms(os.path.join(jd, "state.json")) if jd else 0] +
                  [v for v in (reg.get("updatedAt"), reg.get("statusUpdatedAt")) if isinstance(v, (int, float))])
    started = (rec or {}).get("createdAt") or iso_to_ms(st.get("createdAt")) or info.get("firstAt") or \
        reg.get("startedAt") or (int(tst.st_mtime * 1000) if tst is not None else None) or updated
    children = st.get("children") if isinstance(st.get("children"), list) else None
    if not children:
        children = tail.get("prs") or []
    return {
        "sessionId": sid,
        "short": sid[:8],
        "cwd": cwd,
        "name": one_line(name, 160),
        "intent": one_line(st.get("intent") or (rec or {}).get("intent") or info.get("firstPrompt"), 240) or None,
        "state": state,
        "waitingFor": waiting,
        "pending": session_pending(short, st, reg, path, cwd) if state == "needs_you" else None,
        "process": "live" if (live or term) else "retired",
        "heldBy": held,
        "terminalPid": term.get("pid") if term else None,
        "startedAt": int(started or 0),
        "updatedAt": int(updated or started or 0),
        "lastText": trim(output.get("result"), LAST_TEXT_CAP) if isinstance(output.get("result"), str) else tail.get("lastText"),
        "tokens": st.get("tokens") if isinstance(st.get("tokens"), int) else None,
        "model": flag_value(flags, "--model") or tail.get("model"),
        # The daemon mirrors Shift+Tab into the job's respawn flags at once (the transcript only gets a
        # permission-mode line on the next turn), and a wake resumes with them: they win unless a
        # terminal holds the session.
        "permissionMode": (tail.get("permissionMode") or flag_value(flags, "--permission-mode")) if held == "terminal"
        else (flag_value(flags, "--permission-mode") or tail.get("permissionMode")),
        "inFlight": {"tasks": in_flight.get("tasks") if isinstance(in_flight.get("tasks"), int) else 0,
                     "queued": in_flight.get("queued") if isinstance(in_flight.get("queued"), int) else 0,
                     "kinds": [k for k in (in_flight.get("kinds") or []) if isinstance(k, str)]},
        "children": [c for c in children if isinstance(c, dict)],
        "gitBranch": info.get("gitBranch"),
    }


def build_sessions(src, facts, want_cwd=None, limit=SESSIONS_LIMIT, before=None):
    """Sessions newest first: every job / terminal session plus transcripts with no job."""
    if want_cwd:
        want_cwd = os.path.normpath(os.path.abspath(os.path.expanduser(want_cwd)))
    slots = src.slots()
    files = all_transcripts(want_cwd)
    by_file = {}
    for path, sid, st in files:
        if sid not in by_file or st.st_size > by_file[sid][1].st_size:
            by_file[sid] = (path, st)
    cands = []
    for sid, slot in slots.items():
        tf = by_file.get(sid)
        if tf is None and want_cwd:
            tf = None  # the job may live in another project dir: found below by transcript_for
        cands.append((slot, tf))
    for sid, tf in by_file.items():
        if sid in slots:
            continue
        gone = src.removed.get(sid)
        if gone is not None and gone == tf[1].st_size:
            continue  # removed with `rm`; shows again only if the transcript grows (resumed elsewhere)
        cands.append(({"sid": sid, "jobs": [], "rec": None, "term": None, "bg": None}, tf))

    def rough(c):
        slot, tf = c
        t = int(tf[1].st_mtime * 1000) if tf else 0
        for short, st in slot["jobs"]:
            t = max(t, iso_to_ms(st.get("updatedAt")) or 0)
        return t

    cands.sort(key=rough, reverse=True)
    out = []
    for slot, tf in cands:
        if len(out) >= limit:
            break
        live = record_live(slot.get("rec")) or slot.get("term")
        if before is not None and not live and rough((slot, tf)) >= before:
            continue
        try:
            s = make_session(slot, facts, tf)
        except (OSError, IOError, ValueError, KeyError, TypeError):
            continue
        if want_cwd and os.path.normpath(s["cwd"]) != want_cwd:
            continue
        if before is not None and s["updatedAt"] >= before:
            continue
        out.append(s)
    out.sort(key=lambda s: s["updatedAt"], reverse=True)
    return out


def int_opt(opts, name, default=None):
    v = opts.get(name)
    if v is None or v == "":
        return default
    try:
        return int(v)
    except ValueError:
        raise HelperError("--%s needs a number." % name)


def cmd_sessions_v2(opts):
    facts = TranscriptFacts()
    out = build_sessions(Sources(), facts, opts.get("cwd"), max(1, int_opt(opts, "limit", SESSIONS_LIMIT)),
                         int_opt(opts, "before"))
    facts.save()
    emit({"sessions": out})


# ── resolving one session ──

class SessionRef(object):
    """Everything about one session: ids, its job, its registry entry, its transcript, its Session object."""

    def __init__(self, arg, src=None):
        arg = (arg or "").strip().lower()
        if not is_session_arg(arg):
            raise HelperError("Invalid session id.")
        self.src = src or Sources()
        slots = self.src.slots()
        sid = arg if UUID_RE.match(arg) else None
        if sid is None:
            for s in slots.values():
                if s["sid"].startswith(arg) or any(short == arg for short, _st in s["jobs"]) or \
                        (s.get("rec") or {}).get("short") == arg:
                    sid = s["sid"]
                    break
        if sid is None:
            hits = glob.glob(os.path.join(glob.escape(CLAUDE_PROJECTS), "*", arg + "*.jsonl"))
            sids = set(os.path.basename(p)[:-6] for p in hits if UUID_RE.match(os.path.basename(p)[:-6]))
            if len(sids) == 1:
                sid = sids.pop()
        if sid is None:
            raise coded_error("No session %s on this machine." % arg, "ENOSESSION")
        self.sid = sid
        self.slot = slots.get(sid) or {"sid": sid, "jobs": [], "rec": None, "term": None, "bg": None}
        self.job_short, self.st = best_job(self.slot)
        self.st = self.st or {}
        self.tpath = transcript_for(sid, self.st)
        if not self.tpath and not self.slot["jobs"] and not self.slot.get("rec") and not self.slot.get("term"):
            raise coded_error("No session %s on this machine." % arg, "ENOSESSION")

    @property
    def short(self):
        """The daemon short to talk to: the live record's, else the session's own."""
        rec = self.slot.get("rec")
        if record_live(rec):
            return rec["short"]
        return self.sid[:8]

    def session(self, facts=None):
        return make_session(self.slot, facts)

    def held_by_terminal(self):
        t = self.slot.get("term")
        return bool(t) and not t.get("parkedJobId")

    def live(self):
        return record_live(self.slot.get("rec"))

    def refresh(self):
        return SessionRef(self.sid)


def session_now(sid):
    """A fresh Session for sid."""
    return SessionRef(sid).session()


def require_not_held(ref):
    if ref.held_by_terminal():
        raise coded_error("This session is open in a terminal on this machine. Type /bg there to continue it here.",
                          "EHELD")


def cmd_watch_v2(opts):
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass
    gone = ReaderGone()
    want_cwd = opts.get("cwd")
    limit = max(1, int_opt(opts, "limit", WATCH_LIMIT))
    facts = TranscriptFacts()
    last = None  # sid -> json
    last_sig = None
    last_build = 0.0
    last_emit = time.time()
    last_save = time.time()
    records = None
    last_records = 0.0
    while True:
        t = time.time()
        sig = watch_signature(want_cwd)
        if t - last_records >= 5 or sig != last_sig:
            records = daemon_records()
            last_records = t
        if sig != last_sig or t - last_build >= 10 or last is None:
            last_sig = sig
            last_build = t
            src = Sources(daemon=False, records=records if records is not None else {})
            sessions = build_sessions(src, facts, want_cwd, limit)
            cur = collections.OrderedDict((s["sessionId"], json.dumps(s, ensure_ascii=False, separators=(",", ":")))
                                          for s in sessions)
            if last is None:
                sys.stdout.write('{"snapshot":[%s]}\n' % ",".join(cur.values()))
                sys.stdout.flush()
                last_emit = t
            else:
                changed = [v for k, v in cur.items() if last.get(k) != v]
                removed = [k for k in last if k not in cur]
                if changed or removed:
                    sys.stdout.write('{"changed":[%s],"removed":%s}\n' % (",".join(changed), json.dumps(removed)))
                    sys.stdout.flush()
                    last_emit = t
            last = cur
        if t - last_save > 60:
            facts.save()
            last_save = t
        if t - last_emit >= 15:
            sys.stdout.write('{"hb":%d}\n' % now_ms())
            sys.stdout.flush()
            last_emit = t
        if gone.wait(1.0):
            facts.save()
            return


def watch_signature(want_cwd=None):
    """Cheap change detector: stat() of job states, registry entries and transcripts (no parsing)."""
    sig = []
    for d in (JOBS_DIR,):
        try:
            names = sorted(os.listdir(d))
        except OSError:
            names = []
        for n in names:
            p = os.path.join(d, n, "state.json")
            sig.append((n, mtime_ms(p), file_size(p)))
    try:
        for n in sorted(os.listdir(CLAUDE_SESSIONS)):
            if n.endswith(".json"):
                sig.append((n, mtime_ms(os.path.join(CLAUDE_SESSIONS, n))))
    except OSError:
        pass
    for path, _sid, st in all_transcripts(want_cwd and os.path.abspath(os.path.expanduser(want_cwd))):
        sig.append((path, st.st_size, int(st.st_mtime * 1000)))
    sig.append(("removed", mtime_ms(REMOVED_PATH)))
    return sig


# ── follow: the session's events ──

class WideScreen(Screen):
    """Screen where East Asian wide characters take two cells, as in a real terminal."""

    def _put(self, ch):
        w = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if self.c + w > self.cols:
            self.c = 0
            self._lf()
        self.grid[self.r][self.c] = ch
        if w == 2 and self.c + 1 < self.cols:
            self.grid[self.r][self.c + 1] = ""
        self.c += w


PARTIAL_ESC_RE = re.compile(r"\x1b(?:\[[0-9;?<=>]*[ -/]*|\][^\x07\x1b]*|[()])?$")
SPINNER_RE = re.compile(u"^\\s{0,4}[·✢✳✶✻✽*∗]\\s+(\\S[^()…]*…)\\s*(?:\\((.*?)\\)?)?\\s*$")
MESSAGE_GLYPHS = (u"●", u"⏺")  # ● (Linux) / ⏺ (macOS)
TOOL_LINE_RE = re.compile(r"^[A-Za-z_][\w.:-]*\(.*\)?\s*$|.*\(ctrl\+o to expand\)\s*$")
LIST_START_RE = re.compile(u"^(?:[-*+•]\\s|\\d+[.)]\\s|#|>|\\||```|⎿)")


def parse_spinner(paren):
    """(elapsedS, tokens) from the spinner's "(12s · ↓ 1.2k tokens · thinking)" part."""
    el = tok = None
    if paren:
        m = re.search(r"(?:(\d+)h\s*)?(?:(\d+)m\s*)?(\d+)s\b", paren)
        if m:
            el = int(m.group(1) or 0) * 3600 + int(m.group(2) or 0) * 60 + int(m.group(3))
        m = re.search(u"[↓↑]\\s*([\\d.,]+)\\s*([kKmM]?)\\s*tokens", paren)
        if m:
            try:
                n = float(m.group(1).replace(",", ""))
                tok = int(round(n * {"k": 1000, "m": 1000000}.get(m.group(2).lower(), 1)))
            except ValueError:
                tok = None
    return el, tok


def soft_wrapped(prev_raw, nxt, width):
    """True when nxt continues prev_raw's line: prev was near full width and nxt's first word would not have fit."""
    if width < 40 or not nxt.strip() or not prev_raw.strip():
        return False
    t = nxt.strip()
    if LIST_START_RE.match(t):
        return False
    word = t.split()[0]
    n = len(prev_raw.rstrip())
    return n >= width * 0.6 and n + 1 + len(word) > width - 1


def is_rule(line):
    t = line.strip()
    return len(t) > 20 and set(t) <= set(u"─━ ")


def input_box_top(lines):
    """Index of the rule above the prompt box (── / ❯ / ──) at the bottom of the screen, or None."""
    for i in range(len(lines) - 1, 0, -1):
        if lines[i].lstrip().startswith(u"❯"):
            j = i - 1
            while j >= 0 and not lines[j].strip():
                j -= 1
            return j if j >= 0 and is_rule(lines[j]) else None
    return None


def screen_draft(lines, working=False):
    """(draft text or None, status {verb, elapsedS, tokens} or None) from a rendered screen: the in-progress
    reply is the block after the last ● above the spinner line; soft-wrapped lines are joined again.
    While some replies stream (numbered lists) the CLI drops the spinner: with working=True (the session
    is mid-turn) the prompt box's top rule anchors the block instead."""
    spin = None
    for i in range(len(lines) - 1, -1, -1):
        if SPINNER_RE.match(lines[i]):
            spin = i
            break
    status = None
    if spin is None:
        spin = input_box_top(lines) if working else None
        if spin is None:
            return None, None
    else:
        m = SPINNER_RE.match(lines[spin])
        el, tok = parse_spinner(m.group(2))
        status = {"verb": m.group(1).strip(), "elapsedS": el, "tokens": tok}
    start = None
    for i in range(spin - 1, -1, -1):
        t = lines[i].lstrip()
        if t.startswith(MESSAGE_GLYPHS):
            start = i
            break
        if t.startswith(u"❯") or is_rule(t):
            break  # the prompt echo or a rule: nothing streamed since
    if start is None:
        return None, status
    block = lines[start:spin]
    while block and not block[-1].strip():
        block.pop()
    if not block:
        return None, status
    first = block[0]
    gi = len(first) - len(first.lstrip())
    indent = gi + 2
    rows = [first[gi + 1:].lstrip()]
    for l in block[1:]:
        lead = len(l) - len(l.lstrip())
        rows.append(l[min(lead, indent):])
    if TOOL_LINE_RE.match(rows[0].strip()) and (len(rows) == 1 or any(r.lstrip().startswith(u"⎿") for r in rows)):
        return None, status  # a tool call (● Bash(ls) / ⎿ output), not a reply
    if any(r.lstrip().startswith(u"⎿") for r in rows[:2]):
        return None, status
    width = max(len(l.rstrip()) for l in lines) if lines else 0
    out, raws = [], []
    for raw, text in zip(block, rows):
        if out and soft_wrapped(raws[-1], text, width) and out[-1].strip():
            out[-1] = out[-1].rstrip() + " " + text.strip()
        else:
            out.append(text.rstrip())
        raws.append(raw)
    text = "\n".join(out).strip("\n")
    return (text or None), status


class ScreenTracker(object):
    """Replays the subscribe stream into a big virtual screen (the renderer positions absolutely from the top,
    so a grid larger than the real pty renders the same) and cuts drafts / status from it."""

    def __init__(self, rows=150, cols=400):
        self.rows, self.cols = rows, cols
        self.reset()

    def reset(self):
        self.screen = WideScreen(self.rows, self.cols)
        self.pending = ""
        self.dirty = True

    def feed(self, text):
        if not text:
            return
        text = self.pending + text
        m = PARTIAL_ESC_RE.search(text)
        if m and m.start() < len(text):
            self.pending = text[m.start():]
            text = text[:m.start()]
        else:
            self.pending = ""
        self.screen.feed(text)
        self.dirty = True

    def feed_tail(self, chunks):
        """The snapshot's ring tail: start at the last full repaint (2J / alt screen) when there is one."""
        raw = "".join(c for c in chunks if isinstance(c, str))
        cut = max(raw.rfind("\x1b[2J"), raw.rfind("\x1b[?1049h"))
        self.reset()
        self.feed(raw[cut:] if cut > 0 else raw)

    def read(self, working=False):
        self.dirty = False
        return screen_draft(self.screen.lines(), working)


def text_key(s):
    """Letters and digits only: what survives markdown rendering on the screen."""
    return re.sub(r"[\W_]+", "", s or "", flags=re.U).lower()


TN_FIELDS = ("task-id", "tool-use-id", "output-file", "status", "summary")
PEER_RE = re.compile(r'<cross-session-message\s+([^>]*)>\n?(.*?)\n?</cross-session-message>', re.S)
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')
TASK_STATUS = {"completed": "completed", "failed": "failed", "killed": "killed", "stopped": "killed",
               "cancelled": "killed", "canceled": "killed", "error": "failed", "running": "running"}


def tag_value(text, tag):
    m = re.search(r"<%s>(.*?)</%s>" % (re.escape(tag), re.escape(tag)), text, re.S)
    return m.group(1).strip() if m else None


def peer_session_id(src):
    """uds:/run/user/1000/cc-socks/<pid>.sock -> that live session's id, when the registry has it."""
    m = re.search(r"/(\d+)\.sock$", src or "")
    if not m:
        return None
    r = read_json(os.path.join(CLAUDE_SESSIONS, "%s.json" % m.group(1)), None)
    sid = r.get("sessionId") if isinstance(r, dict) else None
    return sid if isinstance(sid, str) and UUID_RE.match(sid) else None


class TranscriptEvents(object):
    """Turns transcript lines into follow events: line, peer, task, todos (subagent status is tracked here and
    emitted by the follower)."""

    def __init__(self, sid, tpath=None, sidechain_ok=False):
        self.sid = sid
        self.tpath = tpath
        self.sidechain_ok = sidechain_ok
        self.tool_uses = {}  # tool_use id -> (name, input)
        self.results = set()  # tool_use ids with a result
        self.tasks = {}  # taskId -> last task event
        self.peers = set()
        self.notified = set()  # agent task ids that reported completion
        self.landed = collections.deque(maxlen=6)  # text_key of the newest assistant texts
        self.todowrite = None

    def task_output_path(self, task_id):
        if not self.tpath:
            return None
        slug = os.path.basename(os.path.dirname(self.tpath))
        return "/tmp/claude-%d/%s/%s/tasks/%s.output" % (os.getuid(), slug, self.sid, task_id)

    def feed(self, raw, offset):
        """Events for one raw transcript line. 'line' events are (raw JSON string, end offset) tuples."""
        o = parse_line(raw)
        if not o:
            return []
        evs = []
        s = transcript_line_out(raw, sidechain_ok=self.sidechain_ok)
        if s is not None:
            evs.append(("line", s, offset))
        t = o.get("type")
        if t == "queue-operation" and o.get("operation") == "enqueue" and isinstance(o.get("content"), str):
            evs += self.special_text(o["content"], iso_to_ms(o.get("timestamp")))
        elif t == "user":
            content = (o.get("message") or {}).get("content") if isinstance(o.get("message"), dict) else None
            if isinstance(content, str):
                evs += self.special_text(content, iso_to_ms(o.get("timestamp")))
            elif isinstance(content, list):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result" and b.get("tool_use_id"):
                        evs += self.tool_result(b, o.get("toolUseResult"))
                    elif isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str):
                        evs += self.special_text(b["text"], iso_to_ms(o.get("timestamp")))
        elif t == "assistant" and (self.sidechain_ok or not o.get("isSidechain")):
            msg = o.get("message") if isinstance(o.get("message"), dict) else {}
            for b in msg.get("content") or []:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "tool_use" and b.get("id"):
                    inp = b.get("input") if isinstance(b.get("input"), dict) else {}
                    self.tool_uses[b["id"]] = (b.get("name"), inp)
                    if b.get("name") == "TodoWrite" and isinstance(inp.get("todos"), list):
                        self.todowrite = [{"id": str(i + 1), "subject": one_line(x.get("content") or x.get("subject"), 300) or "",
                                           "status": x.get("status") if x.get("status") in ("pending", "in_progress", "completed") else "pending"}
                                          for i, x in enumerate(inp["todos"]) if isinstance(x, dict)]
                        evs.append({"e": "todos", "listId": self.sid, "items": self.todowrite})
                elif b.get("type") == "text" and isinstance(b.get("text"), str) and b["text"].strip():
                    self.landed.append(text_key(b["text"]))
                    evs.append(("landed",))
        return evs

    def special_text(self, text, at):
        evs = []
        if "<cross-session-message" in text:
            for m in PEER_RE.finditer(text):
                attrs = dict(ATTR_RE.findall(m.group(1)))
                body = m.group(2).strip()
                key = (attrs.get("from"), body)
                if key in self.peers:
                    continue
                self.peers.add(key)
                evs.append({"e": "peer", "dir": "in", "from": attrs.get("from") or "", "fromName": attrs.get("from-name") or "",
                            "fromSessionId": peer_session_id(attrs.get("from")), "text": body, "at": at or 0})
        if "<task-notification>" in text:
            for chunk in text.split("<task-notification>")[1:]:
                ev = self.notification(chunk.split("</task-notification>")[0])
                if ev:
                    evs.append(ev)
        return evs

    def notification(self, body):
        tid = tag_value(body, "task-id")
        if not tid:
            return None
        status = TASK_STATUS.get((tag_value(body, "status") or "").lower(), "completed")
        tuid = tag_value(body, "tool-use-id") or ""
        prev = self.tasks.get(tid) or {}
        summary = one_line(tag_value(body, "summary"), 300) or prev.get("summary") or ""
        kind = prev.get("kind") or self.kind_of(tuid) or ("agent" if summary.startswith("Agent ") else
                                                          "shell" if summary.startswith("Background command") else
                                                          "monitor" if summary.startswith("Monitor") else "other")
        ev = {"e": "task", "taskId": tid, "toolUseId": tuid or prev.get("toolUseId") or "", "kind": kind,
              "status": status, "summary": summary,
              "outputFile": tag_value(body, "output-file") or prev.get("outputFile") or self.task_output_path(tid) or ""}
        if kind == "agent" and status != "running":
            self.notified.add(tid)
        if prev == ev:
            return None
        self.tasks[tid] = ev
        return ev

    def kind_of(self, tool_use_id):
        name = (self.tool_uses.get(tool_use_id) or (None, None))[0]
        return {"Bash": "shell", "Monitor": "monitor", "Agent": "agent", "Task": "agent"}.get(name)

    def tool_result(self, b, tur):
        tid = b["tool_use_id"]
        self.results.add(tid)
        name, inp = self.tool_uses.get(tid) or (None, {})
        inp = inp or {}
        task_id = kind = None
        if isinstance(tur, dict):
            if isinstance(tur.get("backgroundTaskId"), str):
                task_id = tur["backgroundTaskId"]
                kind = {"Monitor": "monitor"}.get(name, "shell" if name in (None, "Bash") else "other")
            elif (tur.get("isAsync") or tur.get("status") == "async_launched") and isinstance(tur.get("agentId"), str):
                task_id, kind = tur["agentId"], "agent"
            elif name == "Monitor":
                for k in ("taskId", "monitorId", "id"):
                    if isinstance(tur.get(k), str):
                        task_id, kind = tur[k], "monitor"
                        break
        if not task_id:
            return []
        text = b.get("content")
        if isinstance(text, list):
            text = " ".join(x.get("text") or "" for x in text if isinstance(x, dict))
        m = re.search(r"written to:\s*(\S+?\.output)", text or "")
        summary = one_line(inp.get("description") or inp.get("command") or inp.get("prompt") or
                           (tur.get("description") if isinstance(tur, dict) else None), 300) or ""
        ev = {"e": "task", "taskId": task_id, "toolUseId": tid, "kind": kind, "status": "running",
              "summary": summary, "outputFile": (m.group(1) if m else None) or self.task_output_path(task_id) or ""}
        if self.tasks.get(task_id, {}).get("status") not in (None, "running"):
            return []  # already finished (a notification came first)
        self.tasks[task_id] = ev
        return [ev]


def read_task_list(list_id):
    """[{id, subject, status}] of ~/.claude/tasks/<listId>/<n>.json, in id order."""
    d = os.path.join(TASKS_DIR, list_id)
    items = []
    try:
        names = os.listdir(d)
    except OSError:
        return None
    for n in names:
        if not n.endswith(".json") or n.startswith("."):
            continue
        t = read_json(os.path.join(d, n), None)
        if not isinstance(t, dict) or t.get("status") == "deleted":
            continue
        items.append({"id": str(t.get("id") or n[:-5]), "subject": one_line(t.get("subject"), 300) or "",
                      "status": t.get("status") if t.get("status") in ("pending", "in_progress", "completed") else "pending"})

    def order(x):
        try:
            return (0, int(x["id"]))
        except ValueError:
            return (1, x["id"])

    items.sort(key=order)
    return items


def task_list_signature(list_id):
    d = os.path.join(TASKS_DIR, list_id)
    try:
        return tuple(sorted((n, mtime_ms(os.path.join(d, n)), file_size(os.path.join(d, n))) for n in os.listdir(d)
                            if n.endswith(".json")))
    except OSError:
        return None


def subagents_dir(tpath, sid):
    return os.path.join(os.path.dirname(tpath), sid, "subagents") if tpath else None


def read_subagents(tpath, sid):
    """agentId -> meta ({agentType, description, toolUseId, model, requestShape}) of the session's subagents."""
    d = subagents_dir(tpath, sid)
    out = {}
    try:
        names = os.listdir(d) if d else []
    except OSError:
        return out
    for n in names:
        if n.startswith("agent-") and n.endswith(".meta.json"):
            meta = read_json(os.path.join(d, n), None)
            if isinstance(meta, dict):
                out[n[len("agent-"):-len(".meta.json")]] = meta
    return out


class Follower(object):
    """`follow <sid>`: history, caughtUp, then live events until the reader goes."""

    TICK = 0.2

    def __init__(self, ref, from_offset=0, out=None, gone=None):
        self.ref = ref
        self.sid = ref.sid
        self.out = out or sys.stdout
        self.gone = gone or ReaderGone()
        self.tpath = ref.tpath
        self.pos = max(0, from_offset or 0)
        self.tev = TranscriptEvents(self.sid, self.tpath)
        self.subagents = {}  # agentId -> last emitted event
        self.metas = {}
        self.sub = None
        self.sub_lines = LineBuffer()
        self.screen = ScreenTracker()
        self.draft = None
        self.status = None
        self.clear_at = None
        self.last_render = 0.0
        self.session_json = None
        self.working = False  # the session is mid-turn (state "working"): lets drafts anchor without a spinner
        self.state_dirty = True
        self.next_state = 0.0
        self.next_live_check = 0.0
        self.next_poll = 0.0
        self.todo_sig = None
        self.todo_items = None
        self.state_sig = None
        self.state_min = 0.0
        self.facts = TranscriptFacts()

    # ── output ──
    def write(self, s):
        self.out.write(s)
        self.out.write("\n")

    def flush(self):
        self.out.flush()

    def emit(self, obj):
        self.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))

    def emit_line(self, s, offset):
        self.write('{"e":"line","line":%s,"offset":%d}' % (s, offset))

    # ── transcript ──
    def read_transcript(self, limit=None):
        """New complete lines from self.pos -> (events, consumed)."""
        evs = []
        if not self.tpath:
            return evs
        try:
            with open(self.tpath, "rb") as f:
                f.seek(self.pos)
                data = f.read(limit) if limit else f.read()
        except (OSError, IOError):
            return evs
        nl = data.rfind(b"\n")
        if nl < 0:
            return evs
        start = self.pos
        for raw in data[:nl + 1].split(b"\n")[:-1]:
            start += len(raw) + 1
            if raw.strip():
                evs.extend(self.tev.feed(raw, start))
        self.pos += nl + 1
        return evs

    def emit_events(self, evs):
        for ev in evs:
            if isinstance(ev, tuple):
                if ev[0] == "line":
                    self.emit_line(ev[1], ev[2])
                elif ev[0] == "landed":
                    self.on_landed()
            else:
                self.emit(ev)
                if ev.get("e") == "task":
                    self.update_subagents()

    def history(self):
        size = file_size(self.tpath) if self.tpath else -1
        if self.pos > max(size, 0):
            self.pos = 0  # replaced: start over
        evs = self.read_transcript()
        out = []
        total = 0
        for ev in evs:
            if isinstance(ev, tuple):
                if ev[0] == "line":
                    s = '{"e":"line","line":%s,"offset":%d}' % (ev[1], ev[2])
                else:
                    continue
            else:
                s = json.dumps(ev, ensure_ascii=False, separators=(",", ":"))
            out.append(s)
            total += len(s) + 1
        dropped = 0
        while total > TRANSCRIPT_MAX_BYTES and len(out) > 1:
            total -= len(out[0]) + 1
            out.pop(0)
            dropped += 1
        if dropped:
            out.insert(0, '{"e":"line","line":%s}' % json.dumps({"type": "tether-truncated", "droppedLines": dropped}))
        for s in out:
            self.write(s)

    # ── subagents / todos ──
    def subagent_event(self, aid, meta):
        background = meta.get("requestShape") == "background"
        tuid = meta.get("toolUseId") or ""
        if background:
            done = aid in self.tev.notified
        else:
            done = bool(tuid) and tuid in self.tev.results
        name, inp = self.tev.tool_uses.get(tuid) or (None, {})
        inp = inp or {}
        return {"e": "subagent", "agentId": aid, "agentType": meta.get("agentType") or inp.get("subagent_type") or "",
                "description": meta.get("description") or inp.get("description") or "", "toolUseId": tuid,
                "model": meta.get("model") or inp.get("model") or "", "background": background,
                "status": "done" if done else "running"}

    def update_subagents(self, rescan=False, emit=True):
        if rescan:
            self.metas = read_subagents(self.tpath, self.sid)
        for aid, meta in self.metas.items():
            ev = self.subagent_event(aid, meta)
            if self.subagents.get(aid) != ev:
                self.subagents[aid] = ev
                if emit:
                    self.emit(ev)

    def update_todos(self, force=False):
        sig = task_list_signature(self.sid)
        if sig is None and not force:
            return
        if sig == self.todo_sig and not force:
            return
        self.todo_sig = sig
        items = read_task_list(self.sid) if sig is not None else None
        if items is None:
            items = self.tev.todowrite
        if items is None or items == self.todo_items:
            return
        self.todo_items = items
        self.emit({"e": "todos", "listId": self.sid, "items": items})

    # ── state ──
    def state_signature(self):
        p = os.path.join(JOBS_DIR, self.ref.short, "state.json")
        sig = [mtime_ms(p), file_size(p), file_size(self.tpath) if self.tpath else -1]
        try:
            sig.append(tuple(sorted((n, mtime_ms(os.path.join(CLAUDE_SESSIONS, n))) for n in os.listdir(CLAUDE_SESSIONS))))
        except OSError:
            pass
        return sig

    def update_state(self, now, force=False):
        if not force and now < self.state_min:
            return
        sig = self.state_signature()
        if not force and not self.state_dirty and sig == self.state_sig and now < self.next_state:
            return
        self.state_sig = sig
        self.state_dirty = False
        self.next_state = now + 5.0
        self.state_min = now + 1.0
        try:
            self.ref = SessionRef(self.sid)
        except HelperError:
            return
        if self.ref.tpath and self.ref.tpath != self.tpath and not self.tpath:
            self.tpath = self.ref.tpath
            self.tev.tpath = self.tpath
        sess = self.ref.session(self.facts)
        s = json.dumps(sess, ensure_ascii=False, separators=(",", ":"))
        working = sess.get("state") == "working"
        if working != self.working:
            self.working = working
            self.screen.dirty = True
        if s != self.session_json:
            self.session_json = s
            self.write('{"e":"state","session":%s}' % s)

    # ── the daemon's screen stream ──
    def open_subscription(self):
        try:
            conn = DaemonConn()
            conn.send({"proto": DAEMON_PROTO, "op": "subscribe", "short": self.ref.short, "tail": FOLLOW_TAIL_CHUNKS})
        except DaemonError:
            return
        self.sub = conn
        self.sub_lines = LineBuffer()

    def close_subscription(self):
        if self.sub:
            self.sub.close()
        self.sub = None
        self.set_status(None)
        if self.draft is not None:
            self.clear_draft()

    def read_subscription(self):
        try:
            data = self.sub.recv(1.0)
        except DaemonError:
            data = b""
        if not data:
            self.close_subscription()
            self.state_dirty = True
            return
        for raw in self.sub_lines.feed(data):
            if not raw.strip():
                continue
            try:
                ev = decode_json_line(raw)
            except DaemonError:
                continue
            t = ev.get("type")
            if ev.get("ok") is False:
                self.close_subscription()
                self.state_dirty = True
                return
            if t == "snapshot":
                self.screen.feed_tail(ev.get("streamTail") or [])
            elif t == "stream":
                self.screen.feed(ev.get("line") or "")
            elif t == "state":
                self.state_dirty = True
            elif t == "settled":
                self.close_subscription()
                self.state_dirty = True
                return

    def set_status(self, st):
        if st != self.status:
            self.status = st
            if st:
                ev = {"e": "status", "verb": st["verb"]}
                if st.get("elapsedS") is not None:
                    ev["elapsedS"] = st["elapsedS"]
                if st.get("tokens") is not None:
                    ev["tokens"] = st["tokens"]
                self.emit(ev)
            else:
                self.emit({"e": "status"})

    def clear_draft(self):
        self.draft = None
        self.clear_at = None
        self.emit({"e": "draftClear"})

    def landed_draft(self, text):
        k = text_key(text)
        return bool(k) and any(k in l for l in self.tev.landed)

    def on_landed(self):
        if self.draft is not None:
            self.clear_draft()

    def render(self, now):
        if not self.screen.dirty or now - self.last_render < 0.15:
            return
        self.last_render = now
        text, st = self.screen.read(self.working)
        self.set_status(st)
        if text and not self.landed_draft(text):
            self.clear_at = None
            if text != self.draft:
                self.draft = text
                self.emit({"e": "draft", "text": text})
        elif self.draft is not None:
            if text and self.landed_draft(text):
                self.clear_draft()
            elif st is None and self.clear_at is None:
                self.clear_at = now + 3.0  # the turn ended: give the transcript line a moment to land
            elif st is not None:
                self.clear_draft()

    # ── main loop ──
    def run(self):
        self.metas = read_subagents(self.tpath, self.sid)
        self.history()
        self.update_subagents(emit=True)
        self.update_todos(force=True)
        self.update_state(time.time(), force=True)
        self.write('{"e":"caughtUp","offset":%d}' % self.pos)
        self.flush()
        while True:
            now = time.time()
            if self.tpath is None and now >= self.next_poll:
                self.tpath = transcript_for(self.sid, self.ref.st)
                self.tev.tpath = self.tpath
            if self.tpath:
                if file_size(self.tpath) < self.pos:
                    return  # replaced: the app re-follows from scratch
                evs = self.read_transcript()
                if evs:
                    self.emit_events(evs)
                    self.update_subagents()
            if self.sub is None and now >= self.next_live_check:
                self.next_live_check = now + 1.5
                try:
                    alive = daemon_has(self.ref.short).get("alive")
                except DaemonError:
                    alive = False
                if alive and not self.ref.held_by_terminal():
                    self.open_subscription()
                    self.state_dirty = True
            if now >= self.next_poll:
                self.next_poll = now + 1.5
                metas = read_subagents(self.tpath, self.sid)
                if set(metas) != set(self.metas):
                    self.metas = metas
                self.update_subagents()
                self.update_todos()
            self.update_state(now)
            self.render(now)
            if self.clear_at is not None and now >= self.clear_at and self.draft is not None:
                self.clear_draft()
            self.flush()
            if self.sub is not None:
                try:
                    r, _w, _x = select.select([self.sub.sock], [], [], self.TICK)
                except (OSError, ValueError):
                    r = []
                    self.close_subscription()
                if r:
                    self.read_subscription()
                if self.gone.wait(0):
                    break
            elif self.gone.wait(self.TICK):
                break
        if self.sub:
            self.sub.close()


def follow_agent(ref, agent_id, from_offset, out=None, gone=None):
    """`follow <sid> --agent ID`: a subagent's transcript lines, caughtUp, then new lines."""
    if not AGENT_ID_RE.match(agent_id or ""):
        raise HelperError("Invalid agent id.")
    d = subagents_dir(ref.tpath, ref.sid)
    path = os.path.join(d, "agent-%s.jsonl" % agent_id) if d else None
    if not path or not os.path.isfile(path):
        raise coded_error("No subagent %s in this session." % agent_id, "ENOSESSION")
    f = Follower(ref, from_offset, out, gone)
    f.tpath = path
    f.tev = TranscriptEvents(ref.sid, path, sidechain_ok=True)
    f.history()
    f.write('{"e":"caughtUp","offset":%d}' % f.pos)
    f.flush()
    while True:
        if file_size(path) < f.pos:
            return
        for ev in f.read_transcript():
            if isinstance(ev, tuple) and ev[0] == "line":
                f.emit_line(ev[1], ev[2])
        f.flush()
        if f.gone.wait(0.3):
            return


def cmd_follow_v2(opts, arg):
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass
    ref = SessionRef(arg)
    off = int_opt(opts, "from", 0)
    if opts.get("agent"):
        return follow_agent(ref, opts["agent"], off)
    Follower(ref, off).run()


# ── writes ──

KEY_BYTES = {"shift-tab": b"\x1b[Z", "esc": b"\x1b", "enter": b"\r", "up": b"\x1b[A", "down": b"\x1b[B",
             "right": b"\x1b[C", "left": b"\x1b[D", "tab": b"\t", "space": b" ", "backspace": b"\x7f"}


def key_chunks(keys):
    """The protocol's keys -> raw byte chunks to type."""
    if not isinstance(keys, list) or not keys:
        raise HelperError("No keys given.")
    out = []
    for k in keys:
        if isinstance(k, dict) and isinstance(k.get("text"), str):
            if k["text"]:
                out.append(k["text"].encode("utf-8"))
        elif isinstance(k, str) and k in KEY_BYTES:
            out.append(KEY_BYTES[k])
        elif isinstance(k, str) and len(k) == 1 and k in "123456789":
            out.append(k.encode())
        else:
            raise HelperError("Unknown key: %s" % (json.dumps(k)[:40],))
    return out


def drain(att, seconds, quiet=None):
    """Reads (and drops) the attached terminal for up to seconds; stops early after quiet seconds of silence."""
    end = time.time() + seconds
    last = time.time()
    got = bytearray()
    while time.time() < end:
        d = att.read(0.05)
        if d is None:
            break
        if d:
            got.extend(d)
            last = time.time()
        elif quiet is not None and got and time.time() - last >= quiet:
            break
    return bytes(got)


def press_keys(short, chunks, gap=0.15):
    """Attaches to a live session like `claude attach`, types the chunks, detaches. The session keeps running."""
    with DaemonAttach(short, cols=KEY_COLS, rows=KEY_ROWS) as att:
        drain(att, 2.0, quiet=0.35)  # the repaint after attaching
        for c in chunks:
            att.send_keys(c)
            drain(att, gap)
        drain(att, 0.4)


class DaemonTui(Tui):
    """Tui over the daemon's attach (same interface as the `claude attach` pty: pump/send/mark/text/close)."""

    def __init__(self, short, cols=KEY_COLS, rows=KEY_ROWS):
        self.buf = bytearray()
        self.att = DaemonAttach(short, cols=cols, rows=rows)
        if not self.pump(10.0, until=u"❯".encode("utf-8")):
            self.close()
            raise HelperError("Couldn't open the session's terminal.")
        self.pump(1.0)

    def pump(self, seconds, until=None):
        end = time.time() + seconds
        while time.time() < end:
            d = self.att.read(0.05)
            if d is None:
                return False
            if d:
                self.buf.extend(d)
                if until is not None and until in self.buf:
                    return True
        return until is None

    def send(self, data, wait=0.8):
        self.att.send_keys(data)
        self.pump(wait)

    def close(self):
        self.att.close()


def ensure_daemon(opts):
    """Starts the daemon the way the CLI does (`claude daemon run --origin transient`) when it is not running."""
    try:
        return daemon_ping()
    except DaemonError as e:
        if e.code != "ENODAEMON":
            raise
    claude, login_path = resolve_claude(opts.get("claude"))
    if not claude:
        raise coded_error("The Claude Code daemon is not running and Claude Code was not found to start it.", "ENODAEMON")
    spawned_by = json.dumps({"label": "tether", "cwd": HOME, "pid": os.getpid()}, separators=(",", ":"))
    try:
        subprocess.Popen([claude, "daemon", "run", "--origin", "transient", "--spawned-by", spawned_by],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         cwd=HOME, env=claude_env(claude, login_path), start_new_session=True)
    except OSError as e:
        raise coded_error("Couldn't start the Claude Code daemon: %s" % e, "ENODAEMON")
    deadline = time.time() + 20
    while True:
        try:
            return daemon_ping()
        except DaemonError as e:
            if e.code != "ENODAEMON" or time.time() > deadline:
                raise
        time.sleep(0.5)


def with_images(text, images):
    """Appends uploaded image paths to the text the way a dropped file lands in a terminal."""
    paths = []
    for p in images or []:
        if not isinstance(p, str) or not p.startswith("/"):
            raise HelperError("Image paths must be absolute paths on this machine.")
        if not os.path.isfile(p):
            raise HelperError("The image %s is not on this machine." % p)
        paths.append(p.replace("\\", "\\\\").replace(" ", "\\ "))
    text = (text or "").strip()
    if not paths:
        return text
    return (text + " " if text else "") + " ".join(paths)


def wait_ready(short, seconds=20.0):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            if daemon_has(short).get("ready"):
                return True
        except DaemonError:
            pass
        time.sleep(0.3)
    return False


def reply_retrying(short, text, seconds=10.0):
    deadline = time.time() + seconds
    while True:
        try:
            return daemon_reply(short, text)
        except DaemonError as e:
            if e.code not in ("ENOSESSION", "EDAEMON") or time.time() > deadline:
                raise
        time.sleep(0.5)


def wait_session(sid, pred, seconds):
    """Polls the session until pred(Session) or timeout; returns the last Session."""
    deadline = time.time() + seconds
    s = None
    while True:
        try:
            s = session_now(sid)
        except HelperError:
            s = None
        if (s is not None and pred(s)) or time.time() > deadline:
            return s
        time.sleep(0.4)


def cmd_new(opts):
    req = read_request()
    cwd = native_cwd(req.get("cwd"))
    prompt = req.get("prompt")
    if not isinstance(prompt, str) or not prompt.strip():
        raise HelperError("A new session needs a first message.")
    if req.get("trust") is True:
        trust_folder(cwd)
    if not folder_trusted(cwd):
        raise coded_error("Claude Code does not trust %s yet." % cwd, "EUNTRUSTED")
    model = setting_arg(req.get("model"), MODEL_ARG_RE, "model")
    mode = setting_arg(req.get("permissionMode"), None, "permission mode")
    flags = []
    if model and model != "default":
        flags += ["--model", model]
    if mode and mode != "default":
        flags += ["--permission-mode", mode]
    text = with_images(prompt, req.get("images"))
    ensure_daemon(opts)
    images = bool(req.get("images"))
    # With images the first message goes in like a reply (a paste, so the paths become attached images).
    d = daemon_prompt_spec(cwd, None if images else text, flags=flags,
                           name=one_line(req.get("name"), 80) if isinstance(req.get("name"), str) else None)
    d["seed"]["intent"] = text
    r = daemon_dispatch(d, timeout_ms=20000)
    short, sid = r.get("short") or d["short"], d["sessionId"]
    if images:
        wait_ready(short)
        reply_retrying(short, text)
    deadline = time.time() + 8
    while time.time() < deadline and not os.path.isfile(os.path.join(JOBS_DIR, short, "state.json")):
        time.sleep(0.25)
    try:
        s = session_now(sid)
    except HelperError:
        s = make_session({"sid": sid, "jobs": [], "rec": {"short": short, "sessionId": sid, "cwd": cwd, "tempo": "active",
                                                         "createdAt": d["createdAt"]}, "term": None, "bg": None})
    # The daemon writes the respawn flags a moment later: report what was asked for meanwhile.
    s["model"] = s["model"] or (model if model and model != "default" else None)
    s["permissionMode"] = s["permissionMode"] or (mode if mode and mode != "default" else None)
    emit(s)


def wake(opts, ref):
    """Brings a retired session back under its own id: dispatch resume with the same short, no fork."""
    if not ref.tpath:
        raise coded_error("This session has no transcript to continue.", "ENOSESSION")
    st = ref.st or {}
    info = scan_head(ref.tpath)
    cwd = info.get("cwd") if info.get("cwd") and os.path.isdir(info["cwd"]) else None
    if not cwd:
        cwd = st.get("cwd") if isinstance(st.get("cwd"), str) and os.path.isdir(st["cwd"]) else None
    if not cwd:
        raise HelperError("The session's folder no longer exists on this machine.")
    ensure_daemon(opts)
    spec = daemon_resume_spec(ref.sid, cwd, flags=resume_flags(st.get("respawnFlags")), transcript_path=ref.tpath,
                              intent=st.get("intent") or "")
    if st.get("name"):
        spec["seed"]["name"] = st["name"]
    r = daemon_dispatch(spec, timeout_ms=25000)
    short = r.get("short") or spec["short"]
    try:
        daemon_await_ack(short, r.get("nonce"), 15000)
    except DaemonError as e:
        if e.code != "ETIMEOUT":
            raise
    wait_ready(short)
    return short


def cmd_send_v2(opts, arg):
    req = read_request()
    text = with_images(req.get("text") if isinstance(req.get("text"), str) else "", req.get("images"))
    if not text:
        raise HelperError("Nothing to send.")
    ref = SessionRef(arg)
    require_not_held(ref)
    if ref.live():
        try:
            daemon_reply(ref.short, text)
            emit({"ok": True, "woke": False})
            return
        except DaemonError as e:
            if e.code != "ENOSESSION":
                raise
    short = wake(opts, ref)
    reply_retrying(short, text)
    emit({"ok": True, "woke": True})


def live_ref(arg):
    ref = SessionRef(arg)
    require_not_held(ref)
    if not ref.live():
        raise coded_error("This session isn't running. Send it a message to wake it.", "ENOSESSION")
    return ref


def cmd_key(opts, arg):
    req = read_request()
    chunks = key_chunks(req.get("keys"))
    ref = live_ref(arg)
    press_keys(ref.short, chunks)
    emit({"ok": True})


def cmd_answer(opts, arg):
    req = read_request()
    decision = req.get("decision")
    if decision not in ("allow", "allow_always", "deny"):
        raise HelperError("decision must be allow, allow_always or deny.")
    ref = live_ref(arg)
    s = ref.session()
    if s["state"] != "needs_you":
        raise HelperError("There is no pending approval any more.")
    # "1" is always Yes, "2" the "don't ask again" variant; Esc always cancels (numbering of "No" varies).
    press_keys(ref.short, [{"allow": b"1", "allow_always": b"2", "deny": b"\x1b"}[decision]])
    msg = req.get("message")
    if decision == "deny" and isinstance(msg, str) and msg.strip():
        reply_retrying(ref.short, msg.strip())
    emit(wait_session(ref.sid, lambda x: x["state"] != "needs_you", 6.0) or s)


def question_plan(questions, answers):
    """[(kind, n_options, value)] keystroke plan for AskUserQuestion answers ({choices:[i…], other})."""
    if len(answers) != len(questions):
        raise HelperError("Answer every question (%d)." % len(questions))
    plan = []
    for q, a in zip(questions, answers):
        opts_n = len(q.get("options") or [])
        multi = bool(q.get("multiSelect"))
        other = a.get("other") if isinstance(a, dict) else None
        choices = [c for c in (a.get("choices") or []) if isinstance(c, int) and 0 <= c < opts_n] if isinstance(a, dict) else []
        if isinstance(other, str) and other.strip() and not multi:
            plan.append(("other", opts_n, other.strip().replace("\n", " ")))
        elif multi:
            if not choices:
                raise HelperError("Pick at least one option for: %s" % (q.get("question") or "question"))
            plan.append(("multi", opts_n, sorted(set(choices))))
        else:
            if len(choices) != 1:
                raise HelperError("Pick one option for: %s" % (q.get("question") or "question"))
            plan.append(("single", opts_n, choices[0]))
    return plan


def press_answers(tui, qs, questions, plan):
    """Types the plan into the question UI (single = digit; multi = digits + →; other = n+1, paste, Enter)."""
    if not goto_question(tui, qs, 0):
        raise HelperError("Couldn't find the first question on the machine's screen.")
    for i, (kind, opts_n, val) in enumerate(plan):
        if i and not goto_question(tui, qs, i):
            raise HelperError("Lost track of the questions on the machine's screen.")
        if kind == "single":
            tui.send(str(val + 1).encode(), 1.0)
        elif kind == "other":
            tui.send(str(opts_n + 1).encode(), 0.8)
            tui.send(b"\x1b[200~" + val.encode("utf-8") + b"\x1b[201~", 1.0)
            tui.send(b"\r", 1.0)
        else:
            for c in val:
                tui.send(str(c + 1).encode(), 0.35)
            tui.send(b"\x1b[C", 1.0)
    if len(questions) > 1 or any(k == "multi" for k, _n, _v in plan):
        for _ in range(3):
            if "Submitanswers" in tui.text(max(0, tui.mark() - 6000)):
                tui.send(b"1", 1.0)
                break
            tui.send(b"\x1b[C", 0.9)


def cmd_ask(opts, arg):
    req = read_request()
    answers = req.get("answers")
    if not isinstance(answers, list) or not answers:
        raise HelperError("No answers given.")
    ref = live_ref(arg)
    if ref.session()["state"] != "needs_you":
        raise HelperError("Claude isn't waiting for an answer any more.")
    short = ref.short
    st = job_state(short)
    factory = lambda: DaemonTui(short)  # noqa: E731
    qs, multi = ensure_multi(opts, short, st, tui_factory=factory)
    if not qs:
        raise HelperError("The pending prompt isn't a question.")
    questions = [dict(q, multiSelect=bool(multi[i]) if multi and i < len(multi) else bool(q.get("multiSelect")))
                 for i, q in enumerate(qs)]
    plan = question_plan(questions, answers)
    tui = factory()
    try:
        press_answers(tui, qs, questions, plan)
    finally:
        tui.close()
    s = wait_session(ref.sid, lambda x: x["state"] != "needs_you", 6.0)
    if s is None or s["state"] == "needs_you":
        raise HelperError("Claude is still waiting — the answer may not have gone through. Try again.")
    emit(s)


def cmd_interrupt(opts, arg):
    ref = live_ref(arg)
    press_keys(ref.short, [b"\x1b"])
    emit(wait_session(ref.sid, lambda x: x["state"] != "working", 3.0) or ref.session())


def wait_gone(short, field, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            if not daemon_has(short).get(field):
                return True
        except DaemonError:
            return True
        time.sleep(0.3)
    return False


def cmd_stop_v2(opts, arg):
    ref = SessionRef(arg)
    require_not_held(ref)
    if ref.live():
        try:
            daemon_kill(ref.short)
        except DaemonError as e:
            if e.code != "ENOSESSION":
                raise
        wait_gone(ref.short, "alive", 12.0)
    emit({"ok": True})


def cmd_rm_v2(opts, arg):
    ref = SessionRef(arg)
    require_not_held(ref)
    shorts = set(short for short, _st in ref.slot.get("jobs") or [])
    rec = ref.slot.get("rec")
    if rec:
        shorts.add(rec["short"])
    for short in sorted(shorts):
        try:
            daemon_kill(short, evict=True)
        except DaemonError as e:
            if e.code not in ("ENOSESSION", "ENODAEMON"):
                raise
        wait_gone(short, "present", 10.0)
        jd = os.path.join(JOBS_DIR, short)
        if NATIVE_ID_RE.match(short) and os.path.isdir(jd) and os.path.dirname(os.path.abspath(jd)) == os.path.abspath(JOBS_DIR):
            shutil.rmtree(jd, ignore_errors=True)
    if ref.tpath:
        removed = read_removed()
        removed[ref.sid] = file_size(ref.tpath)
        try:
            ensure_dir(TETHER_DIR)
            write_json_atomic(REMOVED_PATH, removed)
        except (OSError, IOError):
            pass
    emit({"ok": True})


# ───────────────────────────────────────── main ─────────────────────────────────────────

def parse_args(argv):
    opts = {}
    pos = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--claude", "--cwd", "--limit", "--before", "--agent", "--from") and i + 1 < len(argv):
            opts[a[2:]] = argv[i + 1]
            i += 2
            continue
        pos.append(a)
        i += 1
    return opts, pos


def main(argv):
    opts, pos = parse_args(argv)
    if not pos:
        raise HelperError("usage: tether_helper.py <command> [args]")
    cmd = pos[0]
    arg = pos[1] if len(pos) > 1 else None

    def need():
        if not arg:
            raise HelperError("%s needs an argument." % cmd)
        return arg

    if cmd == "version":
        emit({"version": HELPER_VERSION})
    elif cmd == "probe":
        cmd_probe(opts)
    elif cmd == "projects":
        cmd_projects(opts)
    elif cmd == "sessions":
        if "--legacy" in pos:
            cmd_sessions(opts)
        else:
            cmd_sessions_v2(opts)
    elif cmd == "transcript":
        cmd_transcript(opts, need())
    elif cmd == "history":
        cmd_history(opts, need())
    elif cmd == "start":
        cmd_start(opts)
    elif cmd == "runs":
        cmd_runs(opts)
    elif cmd == "watch":
        if "--legacy" in pos:
            cmd_watch(opts)
        else:
            cmd_watch_v2(opts)
    elif cmd == "follow":
        if is_session_arg(need()):
            cmd_follow_v2(opts, arg)
        else:
            cmd_follow(opts, arg, pos[2] if len(pos) > 2 else "0")
    elif cmd == "send":
        if is_session_arg(need()):
            cmd_send_v2(opts, arg)
        else:
            cmd_send(opts, arg)
    elif cmd == "input":
        cmd_input(opts, need())
    elif cmd == "stop":
        if is_session_arg(need()):
            cmd_stop_v2(opts, arg)
        else:
            cmd_stop(opts, arg)
    elif cmd == "new":
        cmd_new(opts)
    elif cmd == "key":
        cmd_key(opts, need())
    elif cmd == "answer":
        cmd_answer(opts, need())
    elif cmd == "ask":
        cmd_ask(opts, need())
    elif cmd == "interrupt":
        cmd_interrupt(opts, need())
    elif cmd == "rm":
        cmd_rm_v2(opts, need())
    elif cmd == "delete":
        cmd_delete(opts, need())
    elif cmd == "ls":
        cmd_ls(opts, arg)
    elif cmd == "commands":
        cmd_commands(opts)
    elif cmd == "daemon-status":
        cmd_daemon_status(opts)
    elif cmd == "rewind":
        cmd_rewind(opts)
    elif cmd == "native-list":
        cmd_native_list(opts)
    elif cmd == "native-start":
        cmd_native_start(opts)
    elif cmd == "native-reply":
        cmd_native_reply(opts, need())
    elif cmd == "native-send":
        cmd_native_send(opts, need())
    elif cmd == "native-answer":
        cmd_native_answer(opts, need())
    elif cmd == "native-interrupt":
        cmd_native_interrupt(opts, need())
    elif cmd == "native-mode":
        cmd_native_mode(opts, need())
    elif cmd == "native-ask":
        cmd_native_ask(opts, need())
    elif cmd == "native-question":
        cmd_native_question(opts, need())
    elif cmd == "native-stop":
        cmd_native_stop(opts, need())
    elif cmd == "native-rm":
        cmd_native_rm(opts, need())
    elif cmd == "native-timeline":
        cmd_native_timeline(opts, need())
    elif cmd == "native-logs":
        cmd_native_logs(opts, need())
    elif cmd == "native-follow":
        cmd_native_follow(opts, need(), pos[2] if len(pos) > 2 else None)
    else:
        raise HelperError("Unknown command: %s" % cmd)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except HelperError as e:
        err = {"error": str(e)}
        if getattr(e, "code", None):
            err["code"] = e.code
        if getattr(e, "daemon_code", None):
            err["daemonCode"] = e.daemon_code
        emit(err)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
    except BrokenPipeError:
        try:
            sys.stdout = open(os.devnull, "w")
        except (OSError, IOError):
            pass
        sys.exit(0)
    except Exception as e:  # noqa -- last-resort: always answer in JSON
        emit({"error": "%s: %s" % (type(e).__name__, e)})
        sys.exit(1)
