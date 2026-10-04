#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tether_helper.py -- remote side of the Tether Android app.

Installed by the app (over SFTP) at ~/.tether/bin/tether_helper.py and invoked over SSH exec
channels. Python 3.6+, standard library only. Every command prints JSON on stdout (one object or
array per line); failures print {"error": "...", "code": "..."} and exit with status 1.

One kind of thing: a Claude Code session, keyed by its session id (protocol 2.0.0,
docs/plans/one-session-daemon.md). Every start, wake, message, key and stop goes through the Claude
Code daemon's control socket; the helper never runs `claude` itself, except `claude daemon` when the
daemon is not running and the read-only `commands` lookup. <sid> is a session id or its 8-hex short.

Sessions
    sessions [--cwd P] [--limit N] [--before MS]   {"sessions": [Session]} newest first
    watch [--cwd P] [--limit N]  {"snapshot": [Session]}, then {"changed": [Session], "removed": [sid]}, {"hb": ms}
    follow <sid> [--agent ID] [--from OFFSET]      follow events: line (+offset), draft, draftClear, status, state,
                                 peer, subagent, task, todos, caughtUp {offset}
    new                          stdin {cwd, prompt, model?, permissionMode?, images?, trust?, name?} -> Session
    send <sid>                   stdin {text, images?} -> {ok, woke} (a retired session is resumed under its own id)
    key <sid>                    stdin {keys: ["shift-tab"|"esc"|"enter"|"up"|"down"|"left"|"right"|"tab"|"space"|"1".."9"|{text}]}
                                 -> {ok}; or stdin {mode: "<permission mode>"|""}: Shift+Tab until the footer shows
                                 that mode ("" = once) -> {ok, permissionMode} (the mode it landed on)
    answer <sid>                 stdin {decision: allow|allow_always|deny, message?, toolUseId?} -> Session
                                 (ESTALE when the open prompt is not toolUseId's)
    ask <sid>                    stdin {answers: [{choices: [i...], other}]} -> Session
    interrupt <sid>              Esc -> Session
    stop <sid> | rm <sid>        {"ok": true}
    daemon-status                {running, proto, version, auth: ok|needs_login|unknown, pid?, error?, code?}
  Errors carry "code": ENODAEMON EAUTH EPROTO EHELD ENOSESSION EUNTRUSTED ETIMEOUT EDAEMON, ESTALE (the prompt
  being answered is gone or was replaced) or EINVAL (the request itself was refused: bad keys, no text...).
  A needs_you Session's pending is a permission, a question, or a dialog cut from its screen
  ({kind:"dialog", dialog: mcp_servers|trust|other, title, body, options:[{label, checked?, key?}], keys}).

Machine
    version                      {"version": HELPER_VERSION}
    probe                        host / claude / python facts (+ daemon: {running, proto, version, auth})
    projects                     [ProjectSummary]
    transcript <sessionId>       raw transcript lines (filtered for size)
    ls [path]                    DirListing
    commands [--cwd P]           {commands: [{name, description, argumentHint}]} from claude's initialize reply
Global option: --claude PATH (explicit claude binary).

Older helpers kept Tether's own runs in ~/.tether/runs/; this one ignores that folder. Those
conversations are ordinary Claude Code sessions and show up through their transcripts.
"""

import codecs
import errno
import glob
import io
import json
import os
import platform
import random
import select
import re
import signal
import socket
import subprocess
import stat
import struct
import sys
import time

HELPER_VERSION = "2.0.0"

HOME = os.path.expanduser("~")
TETHER_DIR = os.path.join(HOME, ".tether")
CACHE_DIR = os.path.join(TETHER_DIR, "cache")
CLAUDE_PROJECTS = os.path.join(HOME, ".claude", "projects")

STATE_VERSION = 4
HEAD_BYTES = 1024 * 1024
TAIL_BYTES = 256 * 1024
TRANSCRIPT_MAX_BYTES = 12 * 1024 * 1024
TRANSCRIPT_STRING_CAP = 24 * 1024
LAST_TEXT_CAP = 200

SESSION_ID_RE = re.compile(r"^[0-9A-Za-z_-]{8,}$")
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


WORKTREE_MARK = "/.claude/worktrees/"


def project_root(cwd):
    """The project a cwd belongs to: a Claude Code worktree (<root>/.claude/worktrees/<name>) counts as <root>."""
    if not cwd:
        return cwd
    i = cwd.find(WORKTREE_MARK)
    return cwd[:i] if i > 0 else cwd


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


def session_title(info):
    for k in ("customTitle", "aiTitle", "summary"):
        if info.get(k):
            return one_line(info[k], 120)
    for k in ("firstPrompt", "lastPrompt"):
        if info.get(k):
            return one_line(info[k], 120)
    return "Untitled session"


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
        root = project_root(cwd)
        if root != cwd:
            cwd, branch = root, None  # a worktree's sessions count toward its project, not its branch
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


def queued_prompt_line(o):
    """A message the user typed while Claude was working reaches it mid-turn as a queued_command attachment, not
    a user line. Other attachments (hook context, task notifications, other sessions' messages) stay hidden."""
    a = o.get("attachment") if o.get("type") == "attachment" else None
    if not isinstance(a, dict) or a.get("type") != "queued_command" or a.get("commandMode") != "prompt" or a.get("isMeta"):
        return False
    origin = a.get("origin")
    return not isinstance(origin, dict) or origin.get("kind") in (None, "human")


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
    if (t not in KEEP_TYPES and not queued_prompt_line(o)) or (o.get("isSidechain") and not sidechain_ok):
        return None
    if before_ms is not None and t in ("user", "assistant", "system", "attachment"):
        ts = iso_to_ms(o.get("timestamp"))
        if ts is not None and ts >= before_ms:
            return None
    if t == "user" and b"saved as your default" in raw:
        o = session_only_model_line(o)
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


# ───────────────────────────────────────── shared bits ─────────────────────────────────────────

def rel_path(p, cwd):
    if not isinstance(p, str):
        return None
    if cwd and (p == cwd or p.startswith(cwd.rstrip("/") + "/")):
        r = os.path.relpath(p, cwd)
        return r if r != "." else os.path.basename(p)
    if p.startswith(HOME + "/"):
        return "~" + p[len(HOME):]
    return p


META_FIELD_RE = r"""\b%s\s*:\s*(['"`])((?:\\.|(?!\1).)*)\1"""


def script_meta_field(script, field):
    """A string field of a workflow script's `export const meta = {...}` literal, or None."""
    m = re.search(r"\bmeta\s*=\s*\{", script or "")
    if not m:
        return None
    block = re.sub(r"\bphases\s*:\s*\[.*?\]", "", script[m.end():m.end() + 6000], count=1, flags=re.S)  # their titles
    f = re.search(META_FIELD_RE % re.escape(field), block, re.S)
    return re.sub(r"\\(.)", r"\1", f.group(2)) if f else None


def workflow_title(inp):
    """What a Workflow call runs, in words: its description (the input's, else the script meta's), else its name."""
    if not isinstance(inp, dict):
        return None

    def s(k):
        return inp.get(k) if isinstance(inp.get(k), str) else ""

    return one_line(s("description"), 160) or one_line(script_meta_field(s("script"), "description"), 160) or \
        one_line(script_meta_field(s("script"), "name"), 160) or one_line(s("name"), 160) or \
        one_line(re.sub(r"\.js$", "", os.path.basename(s("scriptPath"))), 160) or None


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
    if tool == "Workflow":
        return workflow_title(inp) or tool
    for v in inp.values():
        if isinstance(v, str) and v.strip():
            return one_line(v, 160)
    return tool


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


# ───────────────────────────────────────── Claude Code's files ─────────────────────────────────────────
# ~/.claude/jobs/<short>/state.json (daemon jobs), ~/.claude/sessions/<pid>.json (the live-process
# registry), ~/.claude.json (folder trust).

JOBS_DIR = os.path.join(HOME, ".claude", "jobs")
CLAUDE_SESSIONS = os.path.join(HOME, ".claude", "sessions")
CLAUDE_JSON = os.path.join(HOME, ".claude.json")
NATIVE_ID_RE = re.compile(r"^[0-9A-Za-z_-]{4,64}$")
ANSI_RE = re.compile(r"\x1b\[[0-9;?<=>]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78DEHMc]")


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


# ── requests ──

def read_request():
    raw = sys.stdin.read()
    try:
        req = json.loads(raw) if raw.strip() else {}
    except ValueError:
        raise HelperError("The request is not valid JSON.")
    if not isinstance(req, dict):
        raise HelperError("The request is not a JSON object.")
    return req


def session_cwd(v):
    cwd = os.path.abspath(os.path.expanduser(v or HOME))
    if not os.path.isdir(cwd):
        raise HelperError("The folder %s does not exist." % cwd)
    claude_dir = os.path.join(HOME, ".claude")
    if cwd == claude_dir or cwd.startswith(claude_dir + "/"):
        raise HelperError("Claude Code will not work inside ~/.claude. Pick another folder.")
    return cwd


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


# ── reading a session's prompts (transcript + screen) ──

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


# The mode line under the prompt, as DaemonTui.text() reads it (whitespace removed), e.g. "⏸ manual mode on",
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


def read_questions_from_tui(tui_factory):
    """Renders the session's screen, walking the question tabs with → (answers nothing)."""
    tui = tui_factory()
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


def _norm(s):
    return re.sub(r"\s+", "", s or "")


CHECKBOX_RE = re.compile("\\d\\.\\[(|\u2714|x|X)\\]")  # an option row's box, e.g. "1.[ ]Label" with spaces removed


def current_question(text, qs):
    """(index, is_multi) of the question on screen, or (None, None) (e.g. the Submit tab).
    text is the rendered screen (DaemonTui.screen_text()). Match fuzzily anyway, as the question may not fit the
    screen: whole option labels that appear, plus the longest run of the question text on screen."""
    import difflib
    if "Readytosubmityouranswers?" in text or "Reviewyouranswers" in text:
        return None, None  # the Submit review lists the questions and their answers
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
    Position is tracked on the Tui (tui.qcur). The screen is replayed in full, not read from the output
    since the press: switching between two similar questions repaints only the characters that differ,
    which leaves too little of either to recognise."""
    if not hasattr(tui, "qcur"):
        tui.qcur, multi = current_question(tui.screen_text(), qs)
        if seen is not None and tui.qcur is not None:
            seen[tui.qcur] = multi
    for _ in range(2 * len(qs) + 2):
        if tui.qcur == target:
            return True
        tui.send(b"\x1b[D" if (tui.qcur is None or tui.qcur > target) else b"\x1b[C", 0.9)
        c2, multi = current_question(tui.screen_text(), qs)
        tui.qcur = c2  # None = the Submit tab (or unreadable): keep stepping left
        if seen is not None and c2 is not None:
            seen[c2] = multi
    return tui.qcur == target


def ensure_multi(agent_id, st, tui_factory):
    qs = question_block(st)
    if not qs:
        c = read_json(qcache_path(agent_id), {}) or {}
        if isinstance(c.get("questions"), list) and c.get("questions") and c.get("status_at") == st.get("updatedAt"):
            qs = c["questions"]
            return qs, [bool(q.get("multiSelect")) for q in qs]
        qs = read_questions_from_tui(tui_factory)
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
        tui = tui_factory()
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


# ───────────────────────────────────────── ls ─────────────────────────────────────────

VISIBLE_DOTS = (".github", ".config", ".claude", ".vscode", ".devcontainer")


COMMANDS_TTL = 10 * 60 * 1000

# Claude Code's own slash commands and bundled skills (2.1.287, as its initialize reply lists them). Tether never
# runs `claude` to ask (decision 1: the daemon is the only way in), so these are listed here and the rest is read
# from disk the way the CLI finds it: user / project commands and skills, enabled plugins.
BUILTIN_COMMANDS = (
    ("clear", "Start a new session with empty context; previous session stays on disk", "[name]"),
    ("compact", "Free up context by summarizing the conversation so far", "<optional custom summarization instructions>"),
    ("context", "Show current context usage", ""),
    ("model", "Set the AI model for Claude Code", "<model>"),
    ("effort", "Set effort level for model usage", "<low|medium|high|xhigh|max|auto>"),
    ("fast", "Toggle fast mode", "[on|off]"),
    ("focus", "Toggle focus view: just your prompt, summary, and response", "[on|off]"),
    ("config", "Set a setting by key", "key=value"),
    ("output-style", "List output styles or switch to one", "[style]"),
    ("color", "Set the prompt bar color for this session", "[red|blue|green|yellow|purple|orange|pink|cyan|default]"),
    ("autocompact", "Configure the auto-compact window size", "[auto|<tokens>]"),
    ("rename", "Rename the current conversation", "[name]"),
    ("recap", "Generate a one-line session recap now", ""),
    ("goal", "Set a goal — keep working until the condition is met", ""),
    ("init", "Initialize a new CLAUDE.md file with codebase documentation", ""),
    ("mcp", "Manage MCP servers", "[reconnect|enable|disable [<server>|all]]"),
    ("usage", "Show session cost, plan usage, and what's contributing to your limits", ""),
    ("insights", "Generate a report analyzing your Claude Code sessions", ""),
    ("debug", "Enable debug logging for this session and help diagnose issues", "[issue description]"),
    ("doctor", "Health-check the Claude Code setup and fix issues", "[prompt-audit [<path>]]"),
    ("code-review", "Review the current diff, or a PR number/branch/path target, for correctness bugs",
     "[low|medium|high|xhigh|max|ultra] [--fix] [--comment] [<pr#>|<branch>|<path>]"),
    ("security-review", "Complete a security review of the pending changes on the current branch", ""),
    ("simplify", "Review the changed code for reuse, simplification and efficiency, then apply the fixes", "[<target>]"),
    ("verify", "Verify that a code change actually does what it's supposed to by exercising it end-to-end", ""),
    ("batch", "Research and plan a large-scale change, then execute it in parallel across worktree agents", "<instruction>"),
    ("loop", "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo)", "[interval] [prompt]"),
    ("schedule", "Create, update, list, or run scheduled cloud agents (routines)", ""),
    ("ultrareview", "Start a cloud agent that finds and verifies bugs in your branch", ""),
    ("fewer-permission-prompts", "Add an allowlist of common read-only commands to reduce permission prompts", ""),
    ("auto-mode-setup", "Teach auto mode about your environment, plus optional rule tweaks", ""),
    ("update-config", "Configure the Claude Code harness via settings.json", ""),
    ("run", "Launch and drive this project's app to see a change working", ""),
    ("claude-api", "Reference for the Claude API / Anthropic SDK", ""),
    ("list-agents", "List subagents, teammates, and other Claude sessions you can message", ""),
    ("reload-plugins", "Activate pending plugin changes in the current session", "[--force]"),
    ("reload-skills", "Pick up skills added or changed on disk during this session", ""),
    ("skill-doctor", "Show which loaded skills are unused and costing context", ""),
    ("import", "Import config from another AI coding agent", ""),
)


def front_matter(path, limit=16384):
    """{key: value} of a markdown file's leading `---` block (flat `key: value` lines), plus "_body": the first
    non-empty line after it."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read(limit)
    except (OSError, IOError, TypeError):
        text = None
    out = {}
    if not text:
        return out
    lines = text.splitlines()
    i = 0
    if lines and lines[0].strip() == "---":
        i = 1
        while i < len(lines) and lines[i].strip() != "---":
            m = re.match(r"^([A-Za-z][\w-]*)\s*:\s*(.*)$", lines[i])
            i += 1
            if not m:
                continue
            v = m.group(2).strip()
            if v in (">", "|", ">-", "|-", ">+", "|+"):  # a block scalar: the indented lines under it
                block = []
                while i < len(lines) and lines[i].strip() != "---" and (not lines[i].strip() or lines[i][:1] in " \t"):
                    block.append(lines[i].strip())
                    i += 1
                v = " ".join(b for b in block if b)
            elif len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            out[m.group(1).lower()] = v
        i += 1
    for l in lines[i:]:
        if l.strip():
            out["_body"] = l.strip().lstrip("#").strip()
            break
    return out


def command_entry(name, fm, prefix=""):
    desc = one_line(fm.get("description") or fm.get("_body") or "", 300) or ""
    if prefix:
        desc = "(%s) %s" % (prefix, desc) if desc else "(%s)" % prefix
    return {"name": name, "description": desc, "argumentHint": one_line(fm.get("argument-hint") or "", 200) or ""}


def scan_command_dir(d, prefix=""):
    """`<d>/**/<name>.md` commands: sub/name.md is "sub:name"."""
    out = []
    if not d or not os.path.isdir(d):
        return out
    for root, dirs, files in os.walk(d, followlinks=True):
        dirs[:] = sorted(x for x in dirs if not x.startswith("."))[:50]
        rel = os.path.relpath(root, d)
        ns = [] if rel == "." else rel.split(os.sep)
        for f in sorted(files):
            if not f.endswith(".md") or f.startswith("."):
                continue
            p = os.path.join(root, f)
            if not os.path.isfile(p):
                continue  # a dangling link
            name = ":".join(ns + [f[:-3]])
            out.append(command_entry((prefix + ":" + name) if prefix else name, front_matter(p), prefix))
        if len(out) > 300:
            break
    return out


def scan_skill_dir(d, prefix="", depth=1):
    """`<d>/<skill>/SKILL.md` skills (depth 2: one more folder level, as synced skills are laid out)."""
    out = []
    if not d or not os.path.isdir(d):
        return out
    try:
        names = sorted(os.listdir(d))
    except OSError:
        return out
    for n in names[:300]:
        sub = os.path.join(d, n)
        if n.startswith(".") or not os.path.isdir(sub):
            continue
        p = os.path.join(sub, "SKILL.md")
        if os.path.isfile(p):
            fm = front_matter(p)
            name = fm.get("name") or n
            out.append(command_entry((prefix + ":" + name) if prefix else name, fm, prefix))
        elif depth > 1:
            out += scan_skill_dir(sub, prefix, depth - 1)
    return out


def enabled_plugins(cwd):
    """[(plugin name, install path)] of the plugins enabled for cwd (user + project settings)."""
    enabled = {}
    for p in (os.path.join(claude_config_dir(), "settings.json"), os.path.join(cwd, ".claude", "settings.json"),
              os.path.join(cwd, ".claude", "settings.local.json")):
        s = read_json(p, None)
        ep = s.get("enabledPlugins") if isinstance(s, dict) else None
        if isinstance(ep, dict):
            for k, v in ep.items():
                if isinstance(k, str):
                    enabled[k] = v is True
    installed = read_json(os.path.join(claude_config_dir(), "plugins", "installed_plugins.json"), None)
    plugins = installed.get("plugins") if isinstance(installed, dict) else None
    out = []
    for key, on in sorted(enabled.items()):
        if not on or not isinstance(plugins, dict):
            continue
        entries = [e for e in plugins.get(key) or [] if isinstance(e, dict) and isinstance(e.get("installPath"), str)]
        mine = [e for e in entries if e.get("scope") != "project" or e.get("projectPath") == cwd]
        if mine:
            out.append((key.split("@", 1)[0], mine[-1]["installPath"]))
    return out


def list_commands(cwd):
    """Slash commands for a session in cwd: built-ins, then project and user commands / skills, then plugins."""
    seen = set()
    out = []

    def add(items):
        for c in items:
            if c["name"] and c["name"] not in seen:
                seen.add(c["name"])
                out.append(c)

    home_claude = claude_config_dir()
    add(scan_command_dir(os.path.join(cwd, ".claude", "commands")))
    add(scan_skill_dir(os.path.join(cwd, ".claude", "skills")))
    add(scan_command_dir(os.path.join(home_claude, "commands")))
    add(scan_skill_dir(os.path.join(home_claude, "skills")))
    add(scan_skill_dir(os.path.join(home_claude, "skills", "synced"), depth=2))
    for name, path in enabled_plugins(cwd):
        add(scan_command_dir(os.path.join(path, "commands"), name))
        add(scan_skill_dir(os.path.join(path, "skills"), name))
    add({"name": n, "description": d, "argumentHint": a} for n, d, a in BUILTIN_COMMANDS)
    return out


def cmd_commands(opts):
    """Slash commands Claude Code offers in a folder: its built-ins plus what it would load from disk (user and
    project commands and skills, enabled plugins). Read from files, never by running `claude` (decision 1).
    Cached per folder for a few minutes."""
    cwd = os.path.abspath(os.path.expanduser(opts.get("cwd") or HOME))
    if not os.path.isdir(cwd):
        cwd = HOME
    key = re.sub(r"[^A-Za-z0-9]", "-", cwd)[-120:]
    cache_path = os.path.join(CACHE_DIR, "commands2-%s.json" % key)
    cached = read_json(cache_path)
    if isinstance(cached, dict) and cached.get("cwd") == cwd and now_ms() - (cached.get("at") or 0) < COMMANDS_TTL:
        return emit({"commands": cached.get("commands") or []})
    cmds = list_commands(cwd)
    try:
        ensure_dir(CACHE_DIR)
        write_json_atomic(cache_path, {"cwd": cwd, "at": now_ms(), "commands": cmds})
    except OSError:
        pass
    emit({"commands": cmds})


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


def owned_private_dir(path):
    """path is a real directory (not a symlink) owned by this user that nobody else can write to."""
    try:
        st = os.lstat(path)
    except OSError:
        return False
    return stat.S_ISDIR(st.st_mode) and st.st_uid == os.getuid() and not (st.st_mode & 0o022)


def owned_socket(path):
    """path is a unix socket (not a symlink) owned by this user, in a private directory of ours."""
    try:
        st = os.lstat(path)
    except OSError:
        return False
    return stat.S_ISSOCK(st.st_mode) and st.st_uid == os.getuid() and owned_private_dir(os.path.dirname(path))


def find_daemon_socket():
    """Path of this user's daemon control socket, or None. The daemon puts it at
    /tmp/cc-daemon-<uid>/<sha256(config dir)[:8]>/control.sock; when that is missing (another config dir
    resolution), glob, preferring the socket held by the daemon.lock pid, then the newest.
    /tmp is world-writable: the runtime dir, the hash dir and the socket must all be ours and private (anyone
    could pre-create /tmp/cc-daemon-<uid> and get the control key sent to their socket)."""
    root = daemon_runtime_root()
    if not os.path.lexists(root):
        return None
    if not owned_private_dir(root):
        raise DaemonError("%s is not a private directory owned by you; refusing to talk to a daemon there." % root,
                          "ENODAEMON")
    expected = os.path.join(root, daemon_socket_hash(), "control.sock")
    if owned_socket(expected):
        return expected
    found = [p for p in glob.glob(os.path.join(root, "*", "control.sock")) if owned_socket(p)]
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


def peer_uid(sock):
    """The uid of the process at the other end of a connected unix socket (Linux SO_PEERCRED), or None where
    the platform can't tell."""
    opt = getattr(socket, "SO_PEERCRED", None)
    if opt is None:
        return None
    try:
        raw = sock.getsockopt(socket.SOL_SOCKET, opt, struct.calcsize("3i"))
        _pid, uid, _gid = struct.unpack("3i", raw)
        return uid
    except (OSError, struct.error):
        return None


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
        uid = peer_uid(self.sock)
        if uid is not None and uid != os.getuid():
            self.close()
            raise DaemonError("The process at %s belongs to another user; refusing to talk to it." % self.path,
                              "ENODAEMON")
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
    down_until = None
    while True:
        try:
            c = DaemonConn(path)
        except DaemonError as e:
            # Refused / missing while the daemon restarts or upgrades itself: retry ~8 s when it looks like a
            # daemon is (or was just) there; fail at once when there is no daemon at all.
            if e.code != "ENODAEMON" or not retry_starting or not daemon_may_be_restarting(path):
                raise
            down_until = down_until or time.time() + DAEMON_RESTART_GRACE
            if time.time() >= down_until:
                raise
            time.sleep(0.4)
            continue
        with c:
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


DAEMON_RESTART_GRACE = 8.0


def daemon_may_be_restarting(path=None):
    """The daemon is coming back rather than absent: its daemon.lock pid is alive, or its socket / lock was
    touched in the last minute (a restart or self-upgrade between two daemon processes)."""
    lock = daemon_lock() or {}
    pid = lock.get("pid") if isinstance(lock.get("pid"), int) else None
    if pid and pid_alive(pid):
        return True
    recent = now_ms() - 60000
    try:
        sock = path or find_daemon_socket()
    except DaemonError:
        return False  # not ours: nothing to wait for
    for p in (sock, os.path.join(claude_config_dir(), "daemon.lock")):
        if p and os.path.exists(p) and mtime_ms(p) > recent:
            return True
    return False


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
    """A session id (uuid) or short (8 hex)."""
    v = (v or "").strip().lower()
    return bool(UUID_RE.match(v) or SHORT_RE.match(v))


def wake_flags(flags, tpath):
    """resume_flags plus the model the transcript last ran on. The job's --model flag is not reliable: the CLI
    rewrites respawnFlags (on Shift+Tab, after a resume) and can drop the launch --model or write the settings
    default there (seen: launched --model haiku, after a wake the flags said opus while replies came from haiku)."""
    out = resume_flags(flags)
    facts = scan_tail_facts(read_tail(tpath, TAIL_BYTES)) if tpath else {}
    mode = facts.get("permissionMode")
    if mode in PERMISSION_MODES and not flag_value(out, "--permission-mode"):
        # The CLI can leave respawnFlags empty (seen right after a launch with --permission-mode default):
        # without the flag a wake starts in the user's settings defaultMode, not the session's own mode.
        out += ["--permission-mode", mode]
    model = facts.get("model")
    if not model or not MODEL_ARG_RE.match(model):
        return out
    kept, i = [], 0
    while i < len(out):
        if out[i] == "--model":
            i += 2
            continue
        if out[i].startswith("--model="):
            i += 1
            continue
        kept.append(out[i])
        i += 1
    return kept + ["--model", model]


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


LAUNCH_FLAGS_PATH = os.path.join(TETHER_DIR, "launch_flags.json")
LAUNCH_FLAGS_KEEP = 200


def read_launch_flags():
    v = read_json(LAUNCH_FLAGS_PATH, {}) or {}
    return v if isinstance(v, dict) else {}


def remember_launch_flags(sid, flags):
    """The flags a session was started with here. The CLI rewrites the job's respawnFlags once it starts (to []
    while it sits on a startup dialog) and the roster forgets a worker when it stops, so a session stopped before
    its first turn would otherwise come back without its --model / --permission-mode."""
    d = read_launch_flags()
    if flags:
        d[sid] = {"flags": list(flags), "at": now_ms()}
    else:
        d.pop(sid, None)
    if len(d) > LAUNCH_FLAGS_KEEP:
        keep = sorted(d.items(), key=lambda kv: (kv[1].get("at") or 0) if isinstance(kv[1], dict) else 0, reverse=True)
        d = dict(keep[:LAUNCH_FLAGS_KEEP])
    try:
        ensure_dir(TETHER_DIR)
        write_json_atomic(LAUNCH_FLAGS_PATH, d)
    except (OSError, IOError):
        pass


def launch_flags_of(sid):
    e = read_launch_flags().get(sid)
    f = e.get("flags") if isinstance(e, dict) else None
    return [x for x in f if isinstance(x, str)] if isinstance(f, list) else []


def registry_records(registry):
    """Stand-in daemon records from the live background workers in the registry, for when the daemon can't be
    asked (restarting, self-upgrading, busy): a worker whose pid is alive is live, so a blip in the daemon never
    shows running sessions as retired (false "finished" alerts, a resume dispatched for a live session)."""
    out = {}
    for r in registry:
        short, sid = r.get("jobId"), r.get("sessionId")
        if r.get("kind") != "bg" or not (isinstance(short, str) and NATIVE_ID_RE.match(short)) or \
                not (isinstance(sid, str) and UUID_RE.match(sid)):
            continue
        out[short] = {"short": short, "sessionId": sid, "pid": r.get("pid"), "cwd": r.get("cwd"),
                      "createdAt": r.get("startedAt"), "state": "running", "fromRegistry": True,
                      "tempo": {"busy": "active", "waiting": "blocked"}.get(r.get("status"), "idle")}
    return out


class Sources(object):
    """One read of everything a Session is built from. records=None with daemon=False (or a daemon that can't
    be asked) falls back to registry_records."""

    def __init__(self, daemon=True, jobs=None, registry=None, records=None):
        self.jobs = job_states() if jobs is None else jobs
        self.registry = registry_entries() if registry is None else registry
        self.daemon = (daemon_records() if daemon else None) if records is None else records
        self.removed = read_removed()
        if self.daemon is None:
            self.daemon = registry_records(self.registry)

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
                slot(sid)["term"] = r
            elif r.get("kind") == "bg" and sid in by:
                by[sid]["bg"] = r
        for sid, flags in roster_launch_flags().items():
            if sid in by:
                by[sid]["launch"] = flags
        return by


def roster_launch_flags():
    """sessionId -> the flags its live worker was launched with (daemon roster.json). The job's respawnFlags can be
    empty right after a launch (the CLI rewrites them), so this is what tells the first turn's --model."""
    roster = read_json(os.path.join(claude_config_dir(), "daemon", "roster.json"), {}) or {}
    workers = roster.get("workers") if isinstance(roster, dict) else None
    out = {}
    for w in (workers.values() if isinstance(workers, dict) else []):
        d = w.get("dispatch") if isinstance(w, dict) else None
        launch = d.get("launch") if isinstance(d, dict) else None
        sid = d.get("sessionId") if isinstance(d, dict) else None
        if not isinstance(launch, dict) or not isinstance(sid, str):
            continue
        if launch.get("mode") == "resume":
            flags = launch.get("flagArgs")
        else:
            args = launch.get("args") if isinstance(launch.get("args"), list) else []
            flags = args[:args.index("--")] if "--" in args else args[:-1]
        if isinstance(flags, list):
            out[sid] = [f for f in flags if isinstance(f, str)]
    return out


# ── transcript facts (cached by size + mtime) ──

LOCAL_COMMAND_PREFIXES = ("<local-command-", "<command-name>", "<command-message>")


def local_command_line(o):
    """A user line that a slash command run inside Claude Code wrote (its caveat, name or output): no model turn."""
    msg = o.get("message") if isinstance(o.get("message"), dict) else {}
    content = msg.get("content")
    if isinstance(content, list):
        texts = [b.get("text") for b in content if isinstance(b, dict) and b.get("type") == "text"]
        if len(texts) != len(content):
            return False  # tool results, images: part of a model turn
        content = "\n".join(t for t in texts if isinstance(t, str))
    return isinstance(content, str) and content.lstrip().startswith(LOCAL_COMMAND_PREFIXES)


def scan_tail_facts(data):
    """lastText, model, permissionMode, PR links from the end of a transcript."""
    f = {"lastText": None, "model": None, "permissionMode": None, "prs": [], "lastEntry": None}
    for raw in iter_lines_bytes(data):
        if b'"type":"user"' in raw and b'"isSidechain":true' not in raw:
            o = parse_line(raw)
            if o and o.get("type") == "user" and not o.get("isSidechain"):
                f["lastEntry"] = "local" if local_command_line(o) else "user"
        if b'"type":"user"' in raw and b'"permissionMode"' in raw:
            # Each prompt records the mode it ran in; it can differ from the last permission-mode line (a wake
            # without the flag starts in the settings defaultMode).
            o = parse_line(raw)
            if o and o.get("type") == "user" and not o.get("isSidechain") and isinstance(o.get("permissionMode"), str):
                f["permissionMode"] = o["permissionMode"]
        elif b'"permission-mode"' in raw:
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
            f["lastEntry"] = "assistant"
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
            for k in ("lastText", "model", "permissionMode", "lastEntry"):
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
    """[(path, sid, stat)] of top-level transcripts, newest first (want_cwd: its project dir and its worktrees')."""
    files = []
    if not os.path.isdir(CLAUDE_PROJECTS):
        return files
    try:
        names = os.listdir(CLAUDE_PROJECTS)
    except OSError:
        names = []
    if want_cwd:
        own = project_dir_name(want_cwd)
        trees = project_dir_name(want_cwd.rstrip("/") + WORKTREE_MARK)
        names = [n for n in names if n == own or n.startswith(trees)]
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


def session_state(held, live, st, rec, term, bg, last_entry=None):
    """last_entry = the transcript's last main-thread entry ("user", "assistant", "local" for a slash command's
    output), from the tail facts."""
    if held == "terminal":
        return {"busy": "working", "waiting": "needs_you"}.get((term or {}).get("status"), "idle")
    if live:
        tempo = (rec or {}).get("tempo") or st.get("tempo")
        status = (bg or {}).get("status")
        # A startup dialog (project MCP servers, trust) blocks before the worker registers in ~/.claude/sessions:
        # its state.json says blocked ("send a prompt to start") while the daemon's record still says active.
        if tempo == "blocked" or status == "waiting" or (st.get("tempo") == "blocked" and bg is None):
            return "needs_you"
        if status == "busy":
            return "working"
        if tempo == "active":
            # A worker woken (resumed) by a slash command such as `/model x` runs no model turn, so the daemon's
            # record stays "active" for good while the worker itself says idle. Its transcript ends on the
            # command's own output: the command is over.
            in_flight = st.get("inFlight") if isinstance(st.get("inFlight"), dict) else {}
            if status == "idle" and last_entry == "local" and st.get("tempo") != "active" and \
                    not in_flight.get("tasks") and not in_flight.get("queued"):
                return "idle"
            return "working"
        if ((rec or {}).get("state") or st.get("state")) in ("starting", "resuming"):
            return "working"
        return "idle"
    end = (rec or {}).get("outcome") or st.get("state")
    if end in ("failed", "crashed", "error"):
        return "failed"
    return "done"


# ── blocking dialogs (decision 5): cut from the session's screen ──

DIALOG_WAITING = ("dialog open", "input needed")
# A background worker's registry status while it sits at its prompt (nothing on screen waits for a key).
BG_AT_PROMPT = ("idle", "shell")
DIALOG_RULE_CHARS = frozenset(u"─━▔▁═ ")
# A permission prompt fences the command / diff in dashed rules ("Bash command" / ╌╌╌ / touch x / ╌╌╌ / Do you
# want to proceed?): inside the body, not the dialog's top edge.
DIALOG_INNER_RULE_CHARS = frozenset(u"╌┄┈╍┅┉ ")
DIALOG_OPT_NUM_RE = re.compile(u"^\\s*(❯\\s*)?(\\d)\\.\\s+(\\S.*?)\\s*$")
DIALOG_OPT_BOX_RE = re.compile(u"^\\s*(❯\\s*)?\\[([^\\]]?)\\]\\s+(\\S.*?)\\s*$")
DIALOG_HINT_RE = re.compile(u"(?:^|·)\\s*(?:Enter|Esc|Space|Tab|Shift\\+Tab|↑/↓|↑↓|←/→|Ctrl\\+\\w)\\s+to\\s+\\w")
DIALOG_CHECKED = u"✔✓√xX■◼●"
DIALOG_KEYS_LIST = ["up", "down", "enter", "esc"]
DIALOG_KEYS_CHECKLIST = ["up", "down", "space", "enter", "esc"]
DIALOG_SCREEN_TTL = 1.5
_dialog_screens = {}  # short -> (time, lines)


def dialog_rule(line):
    t = line.strip()
    return len(t) > 20 and set(t) <= DIALOG_RULE_CHARS


def dialog_inner_rule(line):
    t = line.strip()
    return len(t) > 20 and set(t) <= DIALOG_INNER_RULE_CHARS


def box_edge(line):
    """A rule edge of the prompt box, plain or carrying the session name ("──── my session ─")."""
    t = line.strip()
    return len(t) > 20 and t[0] in u"─━" and t[-1] in u"─━"


def prompt_box_at(lines, i):
    """lines[i] is the prompt box's "❯ <typed text>" line: right under a box edge (a ❯ in a dialog is the list
    cursor, under another row)."""
    if not lines[i].lstrip().startswith(u"❯"):
        return False
    j = i - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    return j >= 0 and box_edge(lines[j])


def prompt_box_shown(lines, window=12):
    """The ordinary prompt box is at the bottom of the screen: no dialog is drawn (yet: the registry can say
    "dialog open" a moment before the dialog replaces the prompt box)."""
    rows = [l.rstrip() for l in lines or []]
    while rows and not rows[-1].strip():
        rows.pop()
    lo = max(0, len(rows) - window)
    return any(prompt_box_at(rows, i) for i in range(lo, len(rows)))


def dialog_kind(title, body):
    if re.search(r"\bMCP servers?\b.*\bfound\b", title, re.I):
        return "mcp_servers"
    if re.search(r"\btrust\b", title, re.I) or re.search(r"\bDo you trust\b|\bone you trust\b", body, re.I):
        return "trust"
    return "other"


def cut_dialog(lines):
    """{kind:"dialog", dialog, title, body, options, keys} for the dialog drawn at the bottom of a rendered
    screen (it replaces the prompt box: a rule, a title, text, a numbered list or a checklist, a key hint), or
    None. Numbered options carry their digit as key; checklist rows carry checked (toggled with Space)."""
    lines = [l.rstrip() for l in lines or []]
    while lines and not lines[-1].strip():
        lines.pop()
    for r in range(len(lines) - 1, -1, -1):
        if not dialog_rule(lines[r]):
            if prompt_box_at(lines, r):
                return None  # the prompt box is up: anything above it is history
            continue
        block = lines[r + 1:]
        opts = [i for i, l in enumerate(block) if DIALOG_OPT_NUM_RE.match(l) or DIALOG_OPT_BOX_RE.match(l)]
        hints = [i for i, l in enumerate(block) if DIALOG_HINT_RE.search(l)]
        if not opts and not hints:
            continue
        width = len(lines[r])
        texts = [i for i, l in enumerate(block) if l.strip()]
        ti = texts[0] if texts and texts[0] not in opts and texts[0] not in hints else None
        title = block[ti].strip() if ti is not None else ""
        body_lines = block[(ti + 1) if ti is not None else 0:min(opts + hints)]
        indent = min([len(l) - len(l.lstrip()) for l in body_lines if l.strip() and not dialog_inner_rule(l)] or [0])
        out, prev = [], ""
        for raw in body_lines:
            if dialog_inner_rule(raw):
                raw = ""  # a fence around the command: a paragraph break, never joined to the text as a wrap
            text = raw[indent:].rstrip() if raw.strip() else ""
            if out and text and out[-1] and soft_wrapped(prev, text, width):
                out[-1] = out[-1] + " " + text.strip()
            elif text or (out and out[-1]):
                out.append(text)
            prev = raw
        body = "\n".join(out).strip("\n")
        options, checklist = [], False
        for i in opts:
            m = DIALOG_OPT_NUM_RE.match(block[i])
            if m:
                options.append({"label": m.group(3), "key": m.group(2)})
                continue
            m = DIALOG_OPT_BOX_RE.match(block[i])
            checklist = True
            options.append({"label": m.group(3), "checked": bool(m.group(2)) and m.group(2) in DIALOG_CHECKED})
        keys = DIALOG_KEYS_CHECKLIST if checklist or not options else DIALOG_KEYS_LIST
        return {"kind": "dialog", "dialog": dialog_kind(title, body), "title": title, "body": body,
                "options": options, "keys": list(keys)}
    return None


def screen_fallback(lines, cap=16):
    """The bottom of the screen as plain text, for a dialog cut_dialog can't read."""
    rows = [l.rstrip() for l in lines or [] if l.strip() and not dialog_rule(l) and not dialog_inner_rule(l)][-cap:]
    indent = min([len(l) - len(l.lstrip()) for l in rows] or [0])
    return "\n".join(l[indent:] for l in rows)


def fetch_screen(short, settle=0.4):
    """The session's current screen (lines) from a short subscribe: the snapshot's ring tail plus whatever
    streams in during `settle` seconds. Cached briefly (watch / sessions ask for every blocked session)."""
    hit = _dialog_screens.get(short)
    if hit and time.time() - hit[0] < DIALOG_SCREEN_TTL:
        return hit[1]
    tr = ScreenTracker()
    try:
        with DaemonConn() as c:
            c.send({"proto": DAEMON_PROTO, "op": "subscribe", "short": short, "tail": FOLLOW_TAIL_CHUNKS})
            first = c.read_line(5.0)
            if not first or first.get("ok") is False or first.get("type") != "snapshot":
                return None
            tr.feed_tail(first.get("streamTail") or [])
            end = time.time() + settle
            while time.time() < end:
                try:
                    ev = c.read_line(max(0.05, end - time.time()))
                except DaemonError:
                    break
                if ev is None:
                    break
                if ev.get("type") == "stream":
                    tr.feed(ev.get("line") or "")
    except DaemonError:
        return None
    lines = tr.screen.lines()
    _dialog_screens[short] = (time.time(), lines)
    return lines


def session_dialog(short, st, reg, screen=None):
    """pending {kind:"dialog", ...} for a blocking startup / session dialog (decision 5), cut from the
    session's screen. screen: a callable giving the current screen lines (follow keeps one); otherwise a
    short subscribe reads it. A blocked session whose screen can't be read as a dialog gets the screen text
    and the key pad. short: the live worker's short (None when nothing live can be asked)."""
    if not short:
        return None
    lines = screen() if screen else fetch_screen(short)
    d = cut_dialog(lines) if lines else None
    if d:
        return d
    # The screen-text fallback needs a registered worker that says it waits (before registering, a worker's
    # state.json says blocked between dispatch and its first prompt too). Any wait counts, not only "dialog open":
    # a goal proposal, a sandbox network request or a prompt the transcript can't name is still on screen.
    reg = reg or {}
    blocked = bool(reg) and (reg.get("waitingFor") in DIALOG_WAITING or reg.get("status") == "waiting" or
                             (st or {}).get("tempo") == "blocked")
    if blocked and lines and not prompt_box_shown(lines):
        return {"kind": "dialog", "dialog": "other", "title": "", "body": screen_fallback(lines), "options": [],
                "keys": list(DIALOG_KEYS_CHECKLIST)}
    return None


def session_pending(short, st, reg, tpath, cwd, live_short=None, screen=None):
    """What a needs_you session is waiting for: a question, a tool permission, or a dialog (read from the live
    worker live_short's screen)."""
    wf = (reg or {}).get("waitingFor")
    q = question_pending(short, st) if short else None
    if q:
        return dict(q, kind="question")
    if wf in (None, "permission prompt") and tpath:
        pt = pending_tool_use(tpath, cwd)
        if pt:
            return dict(pt, kind="question" if pt.get("toolName") == "AskUserQuestion" else "permission")
    return session_dialog(live_short, st, reg, screen)


def cwd_hint(info, st, rec, reg, path):
    return info.get("cwd") or st.get("cwd") or (rec or {}).get("cwd") or reg.get("cwd") or \
        (decode_dir_name(os.path.basename(os.path.dirname(path))) if path else HOME)


def make_session(slot, facts=None, tfile=None, screen=None):
    """The protocol's Session object for one slot. tfile = (path, stat) when the caller already found it;
    screen = a callable giving the session's current screen lines (follow keeps one), for dialogs."""
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
    state = session_state(held, live, st, rec, term, bg, tail.get("lastEntry"))
    reg = term or bg or {}
    pending = None
    if state == "needs_you":
        pending = session_pending(short, st, reg, path, cwd_hint(info, st, rec, reg, path),
                                  rec["short"] if live and not term else None, screen)
        if pending is None and live and not term and bg is None and (rec or {}).get("tempo") != "blocked":
            # Only the worker's state.json says blocked, before it registered: a startup dialog when one is on
            # its screen, else the moment between dispatch and the first prompt landing (seen on every new
            # session): still starting.
            state = "working"
    elif state == "working" and live and not term and bg is None:
        # A worker that has not registered yet is still starting: it may sit on a startup dialog while its
        # state.json is a previous run's (a relaunch into the same job dir says "stopped" / idle) and the daemon's
        # record says active. Only its screen tells.
        d = session_dialog(rec["short"], st, None, screen)
        if d:
            state, pending = "needs_you", d
    # Claude ended its turn handing work back ("blocked" with a needs note, e.g. "rebuild and test on the phone"):
    # the worker is idle at its prompt, nothing on screen waits for a key. Still the user's move (claude agents
    # lists it as blocked), but answered by a normal message, not a key pad. Idle at its prompt is "idle", or
    # "shell" while a background task (a Monitor, a background command) still runs (seen on 2.1.289).
    handoff = state == "needs_you" and pending is None and live and not term and \
        (bg or {}).get("status") in BG_AT_PROMPT and not st.get("block")
    cwd = cwd_hint(info, st, rec, reg, path)
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
    # The session's own start: every wake makes a new daemon record (and may make a new job), so the live
    # record's createdAt is only the fallback.
    job_created = [iso_to_ms(j[1].get("createdAt")) for j in slot.get("jobs") or [] if isinstance(j[1], dict)]
    job_created = [t for t in job_created if t]
    started = info.get("firstAt") or (min(job_created) if job_created else None) or (rec or {}).get("createdAt") or \
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
        "handoff": bool(handoff),
        # A hand-off's ready-made answer (e.g. "! gh pr merge 16"), as claude agents pre-fills its reply box.
        "suggestedReply": (one_line(st.get("suggestedReply"), 400) or None) if handoff else None,
        "pending": pending,
        "process": "live" if (live or term) else "retired",
        "heldBy": held,
        "terminalPid": term.get("pid") if term else None,
        "startedAt": int(started or 0),
        "updatedAt": int(updated or started or 0),
        "lastText": trim(output.get("result"), LAST_TEXT_CAP) if isinstance(output.get("result"), str) else tail.get("lastText"),
        "tokens": st.get("tokens") if isinstance(st.get("tokens"), int) else None,
        # What the transcript last ran on wins: the job's --model flag can be stale or the settings default
        # (see wake_flags); the flag only covers the first turn, before any reply landed.
        "model": tail.get("model") or flag_value(flags, "--model") or
        (flag_value(slot.get("launch"), "--model") if live else None),
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
        if want_cwd and project_root(os.path.normpath(s["cwd"] or "")) != want_cwd:
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
            raise coded_error("Invalid session id.", "ENOSESSION")
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

    def session(self, facts=None, screen=None):
        return make_session(self.slot, facts, screen=screen)

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
    records_ok = 0.0
    while True:
        t = time.time()
        sig = watch_signature(want_cwd)
        if t - last_records >= 5 or sig != last_sig:
            fresh = daemon_records()
            last_records = t
            if fresh is not None:
                records, records_ok = fresh, t
            elif t - records_ok > WATCH_RECORDS_STALE:
                records = None  # down for a while: registry_records stand in (see Sources)
        if sig != last_sig or t - last_build >= 10 or last is None:
            last_sig = sig
            last_build = t
            src = Sources(daemon=False, records=records)
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


WATCH_RECORDS_STALE = 30.0  # seconds a daemon blip may last before watch stops trusting its last list


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
# The turn's closing line ("✻ Crunched for 10s · done 9:41 PM", "✻ Worked for 1s"): at column 0, unlike the
# reply's own lines (indented under the ●). Nothing below it belongs to the reply.
TURN_DONE_RE = re.compile(u"^\\s?[·✢✳✶✻✽*∗]\\s+\\S[^…]*\\bfor\\s+(?:\\d+h\\s*)?(?:\\d+m\\s*)?\\d+s\\b")
MESSAGE_GLYPHS = (u"●", u"⏺")  # ● (Linux) / ⏺ (macOS)
TOOL_LINE_RE = re.compile(r"^[A-Za-z_][\w.:-]*\(.*\)?\s*$|.*\(ctrl\+o to expand\)\s*$")
LIST_START_RE = re.compile(u"^(?:[-*+•]\\s|\\d+[.)]\\s|#|>|\\||```|⎿)")
# A tool header's name: "Bash", "TodoWrite", "Web Search", "context7 - resolve-library-id (MCP)", "mcp__x__y".
# While the input streams the CLI shows the bare name ("● Bash"); then "● Bash(cmd", wrapped over rows when long,
# for a moment before its "⎿" line. Neither is a reply.
TOOL_NAMES = frozenset(("Agent", "Bash", "Edit", "Explore", "Fetch", "Glob", "Grep", "List", "Monitor", "PowerShell",
                        "Read", "Search", "Skill", "Task", "Update", "Web Fetch", "Web Search", "Workflow", "Write"))
TOOL_NAME_RE = re.compile(r"^(?:[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+|\S.* \(MCP\)|mcp__\w+)$")


def is_tool_header(row):
    """The first row of a block is a tool call's header (bare name, or name followed by "(")."""
    t = row.strip()
    mcp = t.find(" (MCP)")
    end = mcp + 6 if mcp > 0 else (t.find("(") if "(" in t else len(t))
    name = t[:end]
    return name in TOOL_NAMES or bool(TOOL_NAME_RE.match(name))


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


def right_aligned_chrome(line):
    """A short line pushed far right (the token counter above the prompt box), not part of a reply."""
    t = line.strip()
    lead = len(line) - len(line.lstrip())
    return bool(t) and lead >= 40 and lead > 2 * len(t)


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
    for k in range(1, len(block)):
        if TURN_DONE_RE.match(block[k]):
            block = block[:k]  # the turn ended (the state lags): the closing line is not part of the reply
            break
    # Right-aligned chrome just above the prompt box ("…        34781 tokens", seen live with a statusline
    # setup): not reply text. Reply lines sit at the ● indent, never 40+ columns in.
    while block and (not block[-1].strip() or right_aligned_chrome(block[-1])):
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
    if is_tool_header(rows[0]):
        return None, status  # a tool call still drawing (● Bash / a wrapped ● Bash(long cmd), no ⎿ yet)
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
        self.workflows = {}  # workflow runId -> {taskId, toolUseId} (from the Workflow tool's result)
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
                                                          "monitor" if summary.startswith("Monitor") else
                                                          "workflow" if summary.startswith("Dynamic workflow") else "other")
        ev = {"e": "task", "taskId": tid, "toolUseId": tuid or prev.get("toolUseId") or "", "kind": kind,
              "status": status, "summary": summary,
              "outputFile": tag_value(body, "output-file") or prev.get("outputFile") or self.task_output_path(tid) or ""}
        for k in ("name", "runId"):
            if prev.get(k):
                ev[k] = prev[k]
        if kind == "agent" and status != "running":
            self.notified.add(tid)
        if prev == ev:
            return None
        self.tasks[tid] = ev
        return ev

    def kind_of(self, tool_use_id):
        name = (self.tool_uses.get(tool_use_id) or (None, None))[0]
        return {"Bash": "shell", "Monitor": "monitor", "Agent": "agent", "Task": "agent", "Workflow": "workflow"}.get(name)

    def tool_result(self, b, tur):
        tid = b["tool_use_id"]
        self.results.add(tid)
        name, inp = self.tool_uses.get(tid) or (None, {})
        inp = inp or {}
        task_id = kind = None
        extra = {}
        if isinstance(tur, dict):
            if tur.get("taskType") == "local_workflow" and isinstance(tur.get("taskId"), str):
                task_id, kind = tur["taskId"], "workflow"
                if isinstance(tur.get("workflowName"), str) and tur["workflowName"]:
                    extra["name"] = tur["workflowName"]
                if isinstance(tur.get("runId"), str) and tur["runId"]:
                    extra["runId"] = tur["runId"]
                    self.workflows[tur["runId"]] = {"taskId": task_id, "toolUseId": tid}
            elif isinstance(tur.get("backgroundTaskId"), str):
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
        if kind == "workflow":
            summary = one_line(tur.get("summary") or workflow_title(inp) or tur.get("workflowName"), 300) or ""
        else:
            summary = one_line(inp.get("description") or inp.get("command") or inp.get("prompt") or
                               (tur.get("description") if isinstance(tur, dict) else None), 300) or ""
        ev = {"e": "task", "taskId": task_id, "toolUseId": tid, "kind": kind, "status": "running",
              "summary": summary, "outputFile": (m.group(1) if m else None) or self.task_output_path(task_id) or ""}
        ev.update(extra)
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


WF_RUN_RE = re.compile(r"^wf_[0-9A-Za-z_-]{1,64}$")
_journals = {}  # journal path -> ((mtime, size), {agentId: {label, phase, done}})


def read_journal(path):
    """agentId -> {label, phase, done} from a workflow run's journal.jsonl (started / result lines); cached by
    mtime and size, since a long run's journal is read on every poll."""
    sig = (mtime_ms(path), file_size(path))
    hit = _journals.get(path)
    if hit and hit[0] == sig:
        return hit[1]
    agents = {}
    try:
        with open(path, "rb") as f:
            for raw in f:
                o = parse_line(raw)
                aid = o.get("agentId") if isinstance(o, dict) else None
                if not isinstance(aid, str):
                    continue
                a = agents.setdefault(aid, {})
                if o.get("type") == "started":
                    for k in ("label", "phase"):
                        if isinstance(o.get(k), str) and o[k]:
                            a[k] = o[k]
                elif o.get("type") == "result":
                    a["done"] = True
    except (OSError, IOError):
        pass
    _journals[path] = (sig, agents)
    return agents


_metas = {}  # agent meta path -> (mtime, meta): a long session's runs hold hundreds, read on every poll


def read_meta_cached(path):
    sig = mtime_ms(path)
    hit = _metas.get(path)
    if hit and hit[0] == sig:
        return hit[1]
    meta = read_json(path, None)
    _metas[path] = (sig, meta)
    return meta


def workflow_runs_dir(tpath, sid):
    d = subagents_dir(tpath, sid)
    return os.path.join(d, "workflows") if d else None


def read_workflow_agents(tpath, sid):
    """agentId -> meta of the agents of the session's workflow runs (subagents/workflows/wf_<run>/agent-*), each
    with its run (workflowRunId), phase, label (as description) and whether the journal has its result (done)."""
    d = workflow_runs_dir(tpath, sid)
    out = {}
    try:
        runs = sorted(os.listdir(d)) if d else []
    except OSError:
        return out
    for run in runs:
        if not WF_RUN_RE.match(run):
            continue
        rd = os.path.join(d, run)
        try:
            names = sorted(os.listdir(rd))
        except OSError:
            continue
        journal = read_journal(os.path.join(rd, "journal.jsonl"))
        for n in names:
            if not (n.startswith("agent-") and n.endswith(".meta.json")):
                continue
            aid = n[len("agent-"):-len(".meta.json")]
            meta = read_meta_cached(os.path.join(rd, n))
            if not isinstance(meta, dict):
                continue
            j = journal.get(aid) or {}
            meta = dict(meta, workflowRunId=run, done=bool(j.get("done")))
            if j.get("label"):
                meta["description"] = j["label"]
            if j.get("phase") or meta.get("workflowPhase"):
                meta["workflowPhase"] = j.get("phase") or meta.get("workflowPhase")
            out[aid] = meta
    return out


def read_subagents(tpath, sid):
    """agentId -> meta ({agentType, description, toolUseId, model, requestShape}) of the session's subagents,
    workflow agents included (see read_workflow_agents)."""
    d = subagents_dir(tpath, sid)
    out = {}
    try:
        names = os.listdir(d) if d else []
    except OSError:
        names = []
    for n in names:
        if n.startswith("agent-") and n.endswith(".meta.json"):
            meta = read_json(os.path.join(d, n), None)
            if isinstance(meta, dict):
                out[n[len("agent-"):-len(".meta.json")]] = meta
    for aid, meta in read_workflow_agents(tpath, sid).items():
        out.setdefault(aid, meta)
    return out


def subagent_transcript(tpath, sid, agent_id):
    """The path of a subagent's own transcript: subagents/agent-<id>.jsonl, or a workflow run's."""
    d = subagents_dir(tpath, sid)
    if not d:
        return None
    p = os.path.join(d, "agent-%s.jsonl" % agent_id)
    if os.path.isfile(p):
        return p
    wd = workflow_runs_dir(tpath, sid)
    try:
        runs = sorted(os.listdir(wd))
    except OSError:
        return None
    for run in runs:
        p = os.path.join(wd, run, "agent-%s.jsonl" % agent_id)
        if WF_RUN_RE.match(run) and os.path.isfile(p):
            return p
    return None


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
        self.needs_you = False  # blocked: screen changes may change its dialog pending
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
        run = meta.get("workflowRunId")
        if run:
            return self.workflow_agent_event(aid, meta, run)
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

    def workflow_agent_event(self, aid, meta, run):
        """A workflow agent: done once the run's journal has its result, or once the whole run stopped."""
        wf = self.tev.workflows.get(run) or {}
        task = self.tev.tasks.get(wf.get("taskId")) or {}
        done = bool(meta.get("done")) or task.get("status") not in (None, "running")
        ev = {"e": "subagent", "agentId": aid, "agentType": meta.get("agentType") or "",
              "description": meta.get("description") or "", "model": meta.get("model") or "", "background": False,
              "status": "done" if done else "running", "workflowRunId": run}
        if wf.get("toolUseId"):
            ev["toolUseId"] = wf["toolUseId"]
        if meta.get("workflowPhase"):
            ev["phase"] = meta["workflowPhase"]
        return ev

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
        # While subscribed, a dialog is cut from the screen this follow already keeps (no second subscribe).
        sess = self.ref.session(self.facts, screen=self.screen.screen.lines if self.sub is not None else None)
        s = json.dumps(sess, ensure_ascii=False, separators=(",", ":"))
        self.needs_you = sess.get("state") == "needs_you"
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
                if self.needs_you:
                    self.state_dirty = True  # a dialog's screen changed (a checkbox toggled, the cursor moved)
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
        # The screen still shows a landed message whole, or its end when its top scrolled away: equal or a
        # suffix. Not any substring: a new reply that opens like an earlier one ("Let me…") must still stream.
        return bool(k) and any(l.endswith(k) for l in self.tev.landed)

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
    def prime_workflows(self):
        """A follow resumed past the start never reads the Workflow calls, results and notifications before it: learn
        them (no events) so a run's agents keep their tool call and finish with their run."""
        if not self.tpath or self.pos <= 0:
            return
        try:
            with open(self.tpath, "rb") as f:
                data = f.read(self.pos)
        except (OSError, IOError):
            return
        for raw in data.split(b"\n"):
            if b'"Workflow"' in raw or b"local_workflow" in raw or b"<task-notification>" in raw:
                self.tev.feed(raw, 0)
        self.tev.landed.clear()

    def run(self):
        self.metas = read_subagents(self.tpath, self.sid)
        if self.pos > max(file_size(self.tpath) if self.tpath else -1, 0):
            self.pos = 0
        self.prime_workflows()
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
    path = subagent_transcript(ref.tpath, ref.sid, agent_id)
    if not path:
        raise coded_error("No subagent %s in this session." % agent_id, "ENOSESSION")
    f = Follower(ref, from_offset, out, gone)
    f.tpath = path
    f.tev = TranscriptEvents(ref.sid, path, sidechain_ok=True)
    # The subagent's own `subagent` event (type, description, running / done) comes from the parent session: its
    # meta file and the parent transcript's tool result / task notification, read here and nothing emitted.
    parent = Follower(ref, 0, out=io.StringIO(), gone=f.gone)

    def own_event():
        parent.read_transcript()
        meta = read_subagents(ref.tpath, ref.sid).get(agent_id)
        return parent.subagent_event(agent_id, meta) if isinstance(meta, dict) else None

    f.history()
    last = own_event()
    if last:
        f.emit(last)
    f.write('{"e":"caughtUp","offset":%d}' % f.pos)
    f.flush()
    next_poll = time.time() + 1.5
    while True:
        if file_size(path) < f.pos:
            return
        for ev in f.read_transcript():
            if isinstance(ev, tuple) and ev[0] == "line":
                f.emit_line(ev[1], ev[2])
        if time.time() >= next_poll:
            next_poll = time.time() + 1.5
            ev = own_event()
            if ev and ev != last:
                last = ev
                f.emit(ev)
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


class DaemonTui(object):
    """The session's screen over the daemon's attach, driven step by step and read back (pump/send/mark/text/close)."""

    def __init__(self, short, cols=KEY_COLS, rows=KEY_ROWS):
        self.buf = bytearray()
        self.screen, self.screen_fed = Screen(rows, cols), 0
        self.screen_dec = codecs.getincrementaldecoder("utf-8")("replace")
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

    def mark(self):
        return len(self.buf)

    def text(self, since=0):
        """Screen output since a mark, ANSI-free with ALL whitespace removed (the TUI positions
        words with cursor moves, so spaces are unreliable)."""
        t = ANSI_RE.sub("", bytes(self.buf[since:]).decode("utf-8", "replace"))
        return re.sub(r"\s+", "", t)

    def screen_text(self):
        """The screen as it stands now (replayed from all output so far), whitespace removed like text()."""
        self.screen.feed(self.screen_dec.decode(bytes(self.buf[self.screen_fed:])))
        self.screen_fed = len(self.buf)
        return re.sub(r"\s+", "", "\n".join(self.screen.lines()))

    def close(self):
        self.att.close()


def ensure_daemon(opts):
    """Starts the daemon the way the CLI does (`claude daemon run --origin transient`) when it is not running."""
    try:
        return daemon_ping()
    except DaemonError as e:
        if e.code != "ENODAEMON":
            raise
    try:
        claude, login_path = resolve_claude(opts.get("claude"))
    except HelperError as e:
        raise coded_error("The Claude Code daemon is not running and can't be started: %s" % e, "ENODAEMON")
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
    cwd = session_cwd(req.get("cwd"))
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
    if mode:
        # Always explicit, "default" too: without the flag the CLI starts in the user's settings defaultMode
        # (e.g. "auto"), not in the mode the phone showed and asked for.
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
    remember_launch_flags(sid, flags)
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
    s["permissionMode"] = s["permissionMode"] or mode or None
    emit(s)


def wake(opts, ref, text=None, paste=False):
    """Brings a retired session back under its own id: dispatch resume with the same short, no fork. A session
    stopped before its first turn ran (blocked on a startup dialog) has no transcript to resume: it starts again
    under the same id with text as its first prompt. Returns (short, sent): sent when text went in at launch."""
    if not ref.tpath:
        return relaunch(opts, ref, text, paste), text is not None and not paste
    st = ref.st or {}
    info = scan_head(ref.tpath)
    cwd = info.get("cwd") if info.get("cwd") and os.path.isdir(info["cwd"]) else None
    if not cwd:
        cwd = st.get("cwd") if isinstance(st.get("cwd"), str) and os.path.isdir(st["cwd"]) else None
    if not cwd:
        raise coded_error("The session's folder no longer exists on this machine.", "ENOSESSION")
    ensure_daemon(opts)
    spec = daemon_resume_spec(ref.sid, cwd, flags=wake_flags(st.get("respawnFlags"), ref.tpath), transcript_path=ref.tpath,
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
    return short, False


def relaunch(opts, ref, text, paste=False):
    """A job that never ran a turn (no transcript): launch it again under its own session id, in its own
    folder, with its replayable flags; text is the first prompt (pasted after start when paste)."""
    st = ref.st or {}
    cwd = st.get("cwd") if isinstance(st.get("cwd"), str) else None
    if text is None or not ref.slot.get("jobs") or st.get("sessionId") not in (None, ref.sid):
        raise coded_error("This session has no transcript to continue.", "ENOSESSION")
    if not cwd or not os.path.isdir(cwd):
        raise coded_error("The session's folder no longer exists on this machine.", "ENOSESSION")
    ensure_daemon(opts)
    flags = resume_flags(st.get("respawnFlags")) or resume_flags(roster_launch_flags().get(ref.sid)) or \
        resume_flags(launch_flags_of(ref.sid))
    d = daemon_prompt_spec(cwd, None if paste else text, flags=flags, session_id=ref.sid,
                           name=st.get("name") if isinstance(st.get("name"), str) else None)
    d["seed"]["intent"] = st.get("intent") if isinstance(st.get("intent"), str) and st.get("intent") else text
    r = daemon_dispatch(d, timeout_ms=25000)
    short = r.get("short") or d["short"]
    if paste:
        wait_ready(short)
    return short


def require_no_startup_dialog(ref):
    """A new session blocked on a startup dialog (project MCP servers, trust) still holds its first prompt in
    its launch args: the CLI queues that only once the dialog closes, so a message sent now would reach Claude
    before it. Refuse until the dialog is answered."""
    if ref.tpath and scan_head(ref.tpath).get("firstPrompt"):
        return
    s = ref.session()
    if s.get("state") == "needs_you" and (s.get("pending") or {}).get("kind") == "dialog":
        raise coded_error("Claude Code is waiting on a startup prompt in this session. Answer it first; your "
                          "first message is sent once it closes.", "EINVAL")


def cmd_send_v2(opts, arg):
    req = read_request()
    text = with_images(req.get("text") if isinstance(req.get("text"), str) else "", req.get("images"))
    if not text:
        raise HelperError("Nothing to send.")
    ref = SessionRef(arg)
    require_not_held(ref)
    m = MODEL_CMD_RE.match(text)
    guard = model_guard_begin(ref.sid, ref.tpath, m.group(1)) if m else None
    try:
        woke = send_text(opts, ref, text, bool(req.get("images")))
    except BaseException:
        if guard:
            model_guard_release(guard[0])
        raise
    if guard:
        model_guard_spawn(guard[0], ref.sid, guard[1])
    emit({"ok": True, "woke": woke})


def send_text(opts, ref, text, paste):
    """Types text into the session, waking it first when it is retired. Returns whether it woke."""
    if ref.live():
        require_no_startup_dialog(ref)
        try:
            daemon_reply(ref.short, text)
            return False
        except DaemonError as e:
            if e.code != "ENOSESSION":
                raise
    short, sent = wake(opts, ref, text, paste=paste)
    if not sent:
        reply_retrying(short, text)
    return True


# ── the model chip changes THIS session only ──
# Claude Code (2.1.287) also saves `/model X` typed in an interactive session as the machine's default for new
# sessions (the "model" key of ~/.claude/settings.json). The phone's model chip means this session only, so a
# `/model X` sent through `send` is guarded: the key is read before sending and put back exactly once the session
# has applied the switch (its transcript gets the command's output), by a detached `model-guard` process because
# the switch may wait on a "Switch model?" dialog the phone answers later. Guards share one record of the
# original value, so back-to-back switches restore the value from before the first.

MODEL_CMD_RE = re.compile(r"^\s*/model\s+(\S+)\s*$")
MODEL_GUARD_PATH = os.path.join(TETHER_DIR, "model_guard.json")
MODEL_GUARD_SECONDS = 600
MODEL_GUARD_SETTLE = 1.5


def claude_settings_path():
    return os.path.join(claude_config_dir(), "settings.json")


class model_guard_lock(object):
    def __enter__(self):
        import fcntl
        ensure_dir(TETHER_DIR)
        self.f = open(MODEL_GUARD_PATH + ".lock", "a")
        fcntl.flock(self.f, fcntl.LOCK_EX)
        return self

    def __exit__(self, *exc):
        self.f.close()  # releases the lock


def settings_model_key(raw):
    """(present, value) of settings.json's "model" key; None when the text is not a JSON object."""
    if raw is None:
        return (False, None)
    try:
        obj = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(obj, dict):
        return None
    return ("model" in obj, obj.get("model"))


def read_guard():
    g = read_json(MODEL_GUARD_PATH)
    if not isinstance(g, dict) or not isinstance(g.get("tokens"), dict) or not isinstance(g.get("original"), dict):
        return None
    now = time.time()
    g["tokens"] = dict((k, v) for k, v in g["tokens"].items() if isinstance(v, dict) and (v.get("until") or 0) > now)
    return g if g["tokens"] else None


def model_guard_begin(sid, tpath, target):
    """Records settings.json's model key (unless a guard already holds the value from before an earlier switch)
    and the model `/model` will save (target). Returns (token, transcript offset to watch from)."""
    try:
        offset = os.path.getsize(tpath) if tpath else 0
    except OSError:
        offset = 0
    token = "%016x" % random.getrandbits(64)
    with model_guard_lock():
        g = read_guard()
        if g is None:
            g = {"original": {"raw": read_text(claude_settings_path())}, "tokens": {}}
        g["tokens"][token] = {"sid": sid, "target": target, "until": time.time() + MODEL_GUARD_SECONDS}
        write_json_atomic(MODEL_GUARD_PATH, g)
    return token, offset


def model_guard_release(token):
    with model_guard_lock():
        g = read_guard()
        if g is None:
            remove_quietly(MODEL_GUARD_PATH)
            return
        g["tokens"].pop(token, None)
        if g["tokens"]:
            write_json_atomic(MODEL_GUARD_PATH, g)
        else:
            remove_quietly(MODEL_GUARD_PATH)


def remove_quietly(path):
    try:
        os.remove(path)
    except OSError:
        pass


def write_text_like(path, text, like_path):
    """Atomic write keeping the permission bits of the file it replaces."""
    tmp = "%s.tether%d" % (path, os.getpid())
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    try:
        os.chmod(tmp, stat.S_IMODE(os.stat(like_path).st_mode))
    except OSError:
        pass
    os.replace(tmp, path)


def model_guard_restore():
    """Puts settings.json's "model" key back to the guarded value, touching nothing else. Byte-identical to the
    original when nothing else changed meanwhile. Only undoes what a guarded `/model X` wrote: when the key holds
    anything but one of the guarded targets (the user changed the default meanwhile, or the switch was refused),
    the file is left alone. Returns True when it wrote."""
    with model_guard_lock():
        g = read_guard()
        if g is None:
            return False
        path = claude_settings_path()
        orig_raw = g["original"].get("raw")
        cur_raw = read_text(path)
        orig, cur = settings_model_key(orig_raw), settings_model_key(cur_raw)
        if orig is None or cur is None or orig == cur:
            return False
        targets = set(str(t.get("target")).lower() for t in g["tokens"].values() if t.get("target"))
        if not cur[0] or str(cur[1]).lower() not in targets:
            return False  # not the value a guarded switch saved: someone else's change, keep it
        cur_obj = json.loads(cur_raw)
        fixed = dict(cur_obj)
        if orig[0]:
            fixed["model"] = orig[1]  # replaced in place: the key keeps its position
        else:
            fixed.pop("model", None)
        if orig_raw is not None and fixed == json.loads(orig_raw):
            write_text_like(path, orig_raw, path)
        elif orig_raw is None and not fixed:
            remove_quietly(path)  # the CLI created the file just for the model
        else:
            text = json.dumps(fixed, indent=2, ensure_ascii=False) + ("\n" if cur_raw.endswith("\n") else "")
            write_text_like(path, text, path)
        return True


def model_command_output(tpath, offset):
    """The output lines of slash commands in the transcript past offset: once there is one, the switch is applied
    (or refused)."""
    if not tpath:
        return []
    try:
        with open(tpath, "rb") as f:
            f.seek(offset)
            data = f.read()
    except OSError:
        return []
    out = []
    for raw in iter_lines_bytes(data):
        if b"<local-command-std" in raw:
            o = parse_line(raw)
            msg = o.get("message") if o and o.get("type") == "user" and isinstance(o.get("message"), dict) else {}
            content = msg.get("content")
            if isinstance(content, str) and content.lstrip().startswith(("<local-command-stdout>", "<local-command-stderr>")):
                out.append(o)
    return out


MODEL_SAVED_SUFFIX = " and saved as your default for new sessions"
MODEL_GUARD_LINES_PATH = os.path.join(TETHER_DIR, "model_guard_lines.json")


def remember_guarded_lines(lines):
    """The uuids of `/model` outputs whose "saved as your default" the guard undid (the transcript keeps saying so)."""
    uuids = [o.get("uuid") for o in lines if isinstance(o.get("uuid"), str) and MODEL_SAVED_SUFFIX in
             (o["message"].get("content") or "")]
    if not uuids:
        return
    with model_guard_lock():
        cur = read_json(MODEL_GUARD_LINES_PATH, []) or []
        cur = [u for u in cur if isinstance(u, str) and u not in uuids] + uuids
        write_json_atomic(MODEL_GUARD_LINES_PATH, cur[-200:])


def model_save_undone(o):
    """A `/model` output line whose save to the machine default a guard undid (or is undoing right now)."""
    if o.get("uuid") in (read_json(MODEL_GUARD_LINES_PATH, []) or []):
        return True
    g = read_guard()
    return bool(g) and any(t.get("sid") == o.get("sessionId") for t in g["tokens"].values())


def session_only_model_line(o):
    """Rewrites a guarded `/model` output to what happened: the switch is for this session only."""
    msg = o.get("message")
    content = msg.get("content") if isinstance(msg, dict) else None
    if isinstance(content, str) and MODEL_SAVED_SUFFIX in content and model_save_undone(o):
        msg["content"] = content.replace(MODEL_SAVED_SUFFIX, " for this session only")
    return o


def model_guard_watch(token, sid, offset, seconds=MODEL_GUARD_SECONDS, poll=0.3):
    """Until the session's transcript shows the switch done (then a short settle for the settings write), or
    seconds pass: keeps settings.json's model key at the guarded value."""
    end = time.time() + seconds
    done_at = None
    tpath = None
    try:
        while time.time() < end:
            model_guard_restore()
            tpath = tpath or find_transcript(sid)
            if done_at is None:
                lines = model_command_output(tpath, offset)
                if lines:
                    done_at = time.time()
                    remember_guarded_lines(lines)
            if done_at is not None and time.time() - done_at >= MODEL_GUARD_SETTLE:
                break
            time.sleep(poll)
        model_guard_restore()
    finally:
        model_guard_release(token)


def model_guard_spawn(token, sid, offset):
    if os.environ.get("TETHER_MODEL_GUARD_INLINE"):
        model_guard_watch(token, sid, offset, seconds=float(os.environ["TETHER_MODEL_GUARD_INLINE"]), poll=0.05)
        return
    try:
        subprocess.Popen([sys.executable, os.path.abspath(__file__), "model-guard", token, sid, str(offset)],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         close_fds=True, start_new_session=True)
    except OSError:
        model_guard_watch(token, sid, offset, seconds=20)


def cmd_model_guard(pos):
    if len(pos) < 4:
        raise HelperError("usage: model-guard <token> <sid> <offset>")
    model_guard_watch(pos[1], pos[2], int(pos[3]))


def live_ref(arg):
    ref = SessionRef(arg)
    require_not_held(ref)
    if not ref.live():
        raise coded_error("This session isn't running. Send it a message to wake it.", "ENOSESSION")
    return ref


MODE_NAMES = tuple(m for _l, m in SCREEN_MODES)


def read_mode(tui, since, settle=0.9):
    """The mode the footer shows after a Shift+Tab (since a mark). Waits out a bounce: on a model without auto
    mode the CLI shows "auto mode on", then "auto mode unavailable for this model" and falls back to manual."""
    tui.pump(settle)
    mode = screen_mode(tui.text(since))
    if mode == "auto":
        tui.pump(0.8)
        mode = screen_mode(tui.text(since)) or mode
    return mode


def cycle_mode(short, target=None, max_presses=6):
    """Shift+Tab in the session's terminal, reading the footer after each press: once (target None), or until
    the footer shows target. The mode cycle depends on the model and settings (auto mode is not offered on
    every model), so it is read back instead of counted. Returns the mode it landed on."""
    tui = DaemonTui(short)
    try:
        cur = screen_mode(tui.text())
        if target is not None and cur == target:
            return cur
        seen = set([cur]) if cur else set()
        for _ in range(max_presses):
            m = tui.mark()
            tui.att.send_keys(KEY_BYTES["shift-tab"])
            cur = read_mode(tui, m) or cur
            if target is None or cur == target:
                return cur
            if cur in seen:
                break  # went all the way round
            seen.add(cur)
    finally:
        tui.close()
    raise coded_error("%s mode isn't offered in this session (Shift+Tab never reached it)." % target, "EINVAL")


def cmd_key(opts, arg):
    req = read_request()
    mode = req.get("mode")
    if mode is not None:
        # {mode: "<permission mode>"}: Shift+Tab until the footer shows it; {mode: ""}: one Shift+Tab. Both
        # answer with the mode the footer shows afterwards.
        if not isinstance(mode, str) or (mode and mode not in MODE_NAMES):
            raise HelperError("Unknown permission mode: %s" % (json.dumps(mode)[:40],))
        ref = live_ref(arg)
        emit({"ok": True, "permissionMode": cycle_mode(ref.short, mode or None)})
        return
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
        raise coded_error("There is no pending approval any more.", "ESTALE")
    # The keys answer whatever prompt is open now: with the prompt the phone showed (toolUseId), refuse when
    # another prompt has taken its place (a stale notification must never approve a newer prompt).
    want = req.get("toolUseId")
    if isinstance(want, str) and want:
        got = (s.get("pending") or {}).get("toolUseId")
        if got != want:
            raise coded_error("Claude is asking something else now. Open the session to see it.", "ESTALE")
    press_keys(ref.short, [answer_key(ref.short, decision)])
    msg = req.get("message")
    if decision == "deny" and isinstance(msg, str) and msg.strip():
        reply_retrying(ref.short, msg.strip())
    emit(wait_session(ref.sid, lambda x: x["state"] != "needs_you", 6.0) or s)


ALWAYS_ALLOW_RE = re.compile(r"don.t ask again|always allow|allow all|accept edits|auto-approve|for this session", re.I)


def permission_options(short):
    """The numbered options of the prompt on the session's screen ([{label, key}]), or None when unreadable."""
    lines = fetch_screen(short)
    d = cut_dialog(lines) if lines else None
    opts = [o for o in (d or {}).get("options") or [] if o.get("key")]
    return opts or None


def answer_key(short, decision):
    """The key for a permission decision, read off the prompt itself: option 2 is not always "don't ask again"
    (Bash: "always allow access to <dir>", Write: "switch to accept edits", ExitPlanMode: "manually approve
    edits", a plain Yes / No prompt: No). Esc always cancels; "1" is Yes on every permission prompt."""
    if decision == "deny":
        return b"\x1b"
    opts = permission_options(short)
    yes = [o for o in opts or [] if re.match(r"(?i)yes\b", o.get("label") or "")]
    if decision == "allow":
        if opts is not None and not any(o["key"] == "1" for o in yes):
            raise coded_error("The prompt on the machine's screen has no plain Yes. Open the session to answer it.",
                              "ESTALE")
        return b"1"
    for o in yes:
        if o["key"] != "1" and ALWAYS_ALLOW_RE.search(o.get("label") or ""):
            return o["key"].encode()
    if opts is None:
        raise coded_error("Couldn't read the prompt on the machine's screen to find its \"always allow\" choice.",
                          "ESTALE")
    raise coded_error("This prompt has no \"always allow\" choice. Allow it once or open the session.", "EINVAL")


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
        other = other.strip().replace("\n", " ") if isinstance(other, str) and other.strip() else None
        if other and not multi:
            plan.append(("other", opts_n, other))
        elif multi and other:
            plan.append(("multi_other", opts_n, (sorted(set(choices)), other)))
        elif multi:
            if not choices:
                raise HelperError("Pick at least one option for: %s" % (q.get("question") or "question"))
            plan.append(("multi", opts_n, sorted(set(choices))))
        else:
            if len(choices) != 1:
                raise HelperError("Pick one option for: %s" % (q.get("question") or "question"))
            plan.append(("single", opts_n, choices[0]))
    return plan


def focus_type_something(tui, opts_n):
    """Moves a multi-select question's cursor onto its "Type something" row. Digits only toggle there, and
    ↑/↓ wrap through the option rows, so press ↑ until the footer offers the row's editor ("ctrl+g to edit")."""
    for _ in range(opts_n + 1):
        m = tui.mark()
        tui.send(b"\x1b[A", 0.6)
        if "ctrl+gtoedit" in tui.text(m):
            return
    raise HelperError("Couldn't reach the typed answer on the machine's screen.")


def press_answers(tui, qs, questions, plan):
    """Types the plan into the question UI (single = digit; multi = digits + →; other = n+1, paste, Enter;
    multi with a typed answer = digits, ↑ to "Type something", paste (that checks it), ↓ to Submit, Enter)."""
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
        elif kind == "multi_other":
            choices, text = val
            for c in choices:
                tui.send(str(c + 1).encode(), 0.35)
            focus_type_something(tui, opts_n)
            tui.send(b"\x1b[200~" + text.encode("utf-8") + b"\x1b[201~", 1.0)
            tui.send(b"\x1b[B", 0.6)
            tui.send(b"\r", 1.0)
        else:
            for c in val:
                tui.send(str(c + 1).encode(), 0.35)
            tui.send(b"\x1b[C", 1.0)
        # Every answer moves the TUI on to the next tab by itself; stepping there again would land on the
        # Submit review, whose list of the questions reads like a question.
        tui.qcur = i + 1 if i + 1 < len(plan) else None
    if len(questions) > 1 or any(k in ("multi", "multi_other") for k, _n, _v in plan):
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
        raise coded_error("Claude isn't waiting for an answer any more.", "ESTALE")
    short = ref.short
    st = job_state(short)
    factory = lambda: DaemonTui(short)  # noqa: E731
    qs, multi = ensure_multi(short, st, factory)
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
    if launch_flags_of(ref.sid):
        remember_launch_flags(ref.sid, None)
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
        cmd_sessions_v2(opts)
    elif cmd == "transcript":
        cmd_transcript(opts, need())
    elif cmd == "watch":
        cmd_watch_v2(opts)
    elif cmd == "follow":
        cmd_follow_v2(opts, need())
    elif cmd == "new":
        cmd_new(opts)
    elif cmd == "send":
        cmd_send_v2(opts, need())
    elif cmd == "key":
        cmd_key(opts, need())
    elif cmd == "answer":
        cmd_answer(opts, need())
    elif cmd == "ask":
        cmd_ask(opts, need())
    elif cmd == "interrupt":
        cmd_interrupt(opts, need())
    elif cmd == "stop":
        cmd_stop_v2(opts, need())
    elif cmd == "rm":
        cmd_rm_v2(opts, need())
    elif cmd == "ls":
        cmd_ls(opts, arg)
    elif cmd == "commands":
        cmd_commands(opts)
    elif cmd == "daemon-status":
        cmd_daemon_status(opts)
    elif cmd == "model-guard":
        cmd_model_guard(pos)
    else:
        raise HelperError("Unknown command: %s" % cmd)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except HelperError as e:
        # Every error carries a code: a plain HelperError is a request the helper refused (bad input).
        err = {"error": str(e), "code": getattr(e, "code", None) or "EINVAL"}
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
        emit({"error": "%s: %s" % (type(e).__name__, e), "code": "EDAEMON"})
        sys.exit(1)
