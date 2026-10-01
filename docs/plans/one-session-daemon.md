# Workplan: one-session model on the Claude Code daemon

Design doc: https://claude.ai/code/artifact/4a9356bb-56f7-4210-8f6c-c68f8e8f9a7e
Branch: `worktree-one-session-daemon`. Target Claude Code: 2.1.286, daemon `proto` 1.

## Goal

Tether works exactly like `claude agents`, on a phone, over SSH. There is one kind of thing, a **session**,
keyed by its session id (short id = first 8 hex chars). Opening yesterday's session behaves like opening
one that is running now: same id, same history, reply box always live, word-by-word streaming.

## Fixed decisions (do not revisit)

1. **Daemon only.** Every start, wake, message, key and stop goes through the daemon control socket.
   Tether never runs `claude` itself (no `claude -p`, no `claude --bg`, no CLI fallback). The only
   exception: starting the daemon itself when it is not running (`claude daemon` the way the CLI does).
2. **Live runs are removed** (`claude -p` stream-json, `~/.tether/runs`, fork lineage, `term-<pid>` ids).
3. **The phone is a mobile `claude attach`.** Anything an attached terminal can type or press, the phone
   can send: any slash command (as text), Shift+Tab (mode cycle), Esc (interrupt), approval / question
   keys, images (uploaded to the machine, path pasted like a dropped file).
4. **Terminal sessions behave like in `claude agents`:** listed with live status and a live transcript;
   while a terminal holds one, the reply box is replaced by "Open in a terminal on <machine> · type /bg
   there to continue here". No copies.
5. **Streaming** comes from the daemon `subscribe` screen stream, replayed through a terminal emulator in
   the helper; the transcript line replaces the draft when it lands.

## Daemon facts (verified on this machine, 2.1.286)

- Socket: `/tmp/cc-daemon-<uid>/<hash>/control.sock` (glob it; also `daemon.lock` has the pid). One JSON
  request line → one JSON reply line (`subscribe`/`attach` keep streaming). Same-uid peers only.
- Auth: `~/.claude/daemon/control.key` (hex string) sent as `auth` on `dispatch`, `reply`, `attach`,
  `permission-response`.
- Ops: `ping`, `list`, `has{short}`, `dispatch{d,timeoutMs,auth}`, `await-ack{short,nonce,timeoutMs}`,
  `reply{short,text,auth,nextTurn?}`, `subscribe{short,tail?}`, `attach{short,auth,cols,rows,caps?}`,
  `resize`, `kill{short,signal?,evict?}`, `permission-response` (a no-op stub in 2.1.286), housekeeping.
- Dispatch spec `d`: `{proto:1, short, nonce, sessionId, createdAt, source, cwd, launch, env:{},
  isolation:"none", respawnFlags:[...], seed?:{intent,name?}, cols?, rows?}`; `source` ∈
  shell|slash|fleet|spare|respawn; `launch` = `{mode:"prompt", args:["--session-id",uuid,...flags,prompt]}`
  or `{mode:"resume", sessionId, transcriptPath?, fork:false, flagArgs:[...]}`.
- `reply` only works on a live worker (`ENOJOB` when retired → dispatch resume with the same short first).
  When blocked or `nextTurn`, text goes over the rendezvous socket; else bracketed-paste + Enter into the pty.
- `subscribe` → `{type:"snapshot", record, streamTail:[...]}` then `{type:"stream", line}` (raw pty bytes),
  `{type:"state", patch}`, `{type:"settled", outcome}`. No key needed.
- `attach` → reply line then framed pty stream: 4-byte BE length, 1-byte kind (0 = data, 1 = ctrl JSON),
  payload. Client writes keys as data frames.
- Files: `~/.claude/jobs/<short>/state.json` (state, detail, tempo, needs, block.questions, inFlight,
  children, output, tokens, name, intent, respawnFlags, sessionId, linkScanPath),
  `jobs/<short>/timeline.jsonl`, `~/.claude/sessions/<pid>.json` (kind bg|interactive, status
  busy|idle|waiting, waitingFor, jobId, parkedJobId, sessionId, messagingSocketPath),
  `~/.claude/daemon/roster.json`, transcripts `~/.claude/projects/<slug>/<sid>.jsonl`, subagents
  `<slug>/<sid>/subagents/agent-<id>.jsonl` + `.meta.json` {agentType, description, toolUseId, model,
  requestShape}, background task output `/tmp/claude-<uid>/<slug>/<sid>/tasks/<id>.output`, task lists
  `~/.claude/tasks/<listId>/<n>.json`.
- Terminal holds: registry entry `kind:"interactive"` with that `sessionId` and no `parkedJobId`.
  Resume of a held session is refused (`resume_session_live_elsewhere`) — so is ours.
- Peer messages in: transcript `queue-operation` `enqueue` lines whose content starts
  `<cross-session-message from=… from-name=…>`. Task completions: `<task-notification>` likewise.

## Helper ↔ app protocol (HELPER_VERSION 2.0.0) — the contract both tracks build against

All commands print JSON. Errors: `{"error": "<human sentence>", "code": "<CODE>"}` with codes
`ENODAEMON`, `EAUTH`, `EPROTO`, `EHELD`, `ENOSESSION`, `EUNTRUSTED`, `ETIMEOUT`, `EDAEMON`.

**Session object**
```json
{"sessionId":"uuid","short":"8hex","cwd":"/abs","name":"str","intent":"str|null",
 "state":"working|needs_you|idle|done|failed","waitingFor":"str|null",
 "pending":{"kind":"permission|question","toolUseId":"str","toolName":"str","summary":"str","inputJson":"str"}|null,
 "process":"live|retired","heldBy":"daemon|terminal|none","terminalPid":123|null,
 "startedAt":ms,"updatedAt":ms,"lastText":"str|null","tokens":123|null,"model":"str|null",
 "permissionMode":"str|null","inFlight":{"tasks":0,"queued":0,"kinds":[]},
 "children":[{"id":"3557","href":"https://…","kind":"pr"}],"gitBranch":"str|null"}
```
**Commands**
| Command | In | Out |
|---|---|---|
| `version` / `probe` / `projects` / `ls` / `commands` / `transcript` | unchanged | unchanged (`probe` adds `daemon:{running,proto,version,auth}`) |
| `daemon-status` | — | `{running,proto,version,auth:"ok|needs_login|unknown"}` |
| `sessions [--cwd P] [--limit N] [--before MS]` | — | `{"sessions":[Session…]}` newest first; jobs + registry + transcripts with no job |
| `watch` | — | first `{"snapshot":[Session…]}`, then `{"changed":[Session…],"removed":[sid…]}` per change, `{"hb":ms}` every 15 s |
| `follow <sid> [--agent ID] [--from OFFSET]` | — | event lines (below), `{"e":"caughtUp","offset":N}` after history, runs until the reader goes |
| `new` | `{cwd,prompt,model?,permissionMode?,images?:[remotePath]}` | Session, or error `EUNTRUSTED` |
| `send <sid>` | `{text,images?:[remotePath]}` | `{"ok":true,"woke":bool}`; `EHELD` when a terminal holds it |
| `key <sid>` | `{keys:["shift-tab"|"esc"|"enter"|"up"|"down"|"left"|"right"|"tab"|"space"|"1".."9"|{"text":"…"}]}` | `{"ok":true}` |
| `answer <sid>` | `{decision:"allow|allow_always|deny", message?}` | Session |
| `ask <sid>` | `{answers:[{choices:[i…],other:str|null}]}` (current format) | Session |
| `interrupt <sid>` | — | Session (Esc) |
| `stop <sid>` / `rm <sid>` | — | `{"ok":true}` (`rm` = kill evict + delete job dir) |

**Follow events** (one JSON object per line, field `e`):
- `{"e":"line","line":{…}}` — a transcript line (filtered exactly as `transcript` filters today), so the
  existing transcript reducer renders it. With `--agent ID` the lines come from that subagent's file.
- `{"e":"draft","text":"…"}` / `{"e":"draftClear"}` — the in-progress reply cut from the screen.
- `{"e":"status","verb":"Improvising…","elapsedS":12,"tokens":1200}` — the spinner line; `{"e":"status"}` clears.
- `{"e":"state","session":{Session}}` — whenever state.json / registry for this session changes.
- `{"e":"peer","dir":"in","from":"uds:…","fromName":"str","fromSessionId":"uuid|null","text":"…","at":ms}`
  (outgoing peers come from `SendMessage` tool_use lines; the app derives `dir:"out"`).
- `{"e":"subagent","agentId":"…","agentType":"…","description":"…","toolUseId":"…","model":"…","background":bool,"status":"running|done"}`
- `{"e":"task","taskId":"…","toolUseId":"…","kind":"shell|monitor|agent|other","status":"running|completed|failed|killed","summary":"…","outputFile":"…"}`
- `{"e":"todos","listId":"…","items":[{"id":"…","subject":"…","status":"pending|in_progress|completed"}]}`
- `{"e":"caughtUp","offset":N}`

## Tracks and tasks

### H — Helper (Python, `app/src/main/assets/tether_helper.py`, tests in `tools/helper_tests/`)
- H1 Daemon client: socket discovery, key, `ping`, request/response, `subscribe`, `attach` frames,
  `dispatch` + `await-ack`, error mapping. Proto check.
- H2 `sessions` / `watch` / `daemon-status` / `probe` additions.
- H3 `follow` events: transcript lines, draft + status from screen (reuse `render_terminal` / `Screen`),
  state, peer, subagent, task, todos, caughtUp.
- H4 Writes: `new`, `send` (wake = dispatch resume same short, then reply), `key`, `answer`, `ask`,
  `interrupt`, `stop`, `rm`. EHELD for terminal-held sessions.
- H5 Remove old commands (`start`, `runs`, `watch`(old), `follow`(old), `send`(old), `input`, `history`,
  `native-*`, `record_fork`) — only in phase R, after the app no longer calls them.
- **Tests:** unit tests with fixtures (no daemon) for every parser; live tests (`TETHER_LIVE=1`) against
  this machine's daemon using throwaway `--model haiku` sessions in a scratch dir, always `rm`'d after.

### K — Kotlin core + remote (`core/`, `remote/`)
- K1 Models: `SessionRef`, `Session`, `SessionState`, `SessionProcess`, `Holder`, `SessionPending`,
  follow event types. Keep old types until phase R.
- K2 `SessionHub` (contracts) + implementation over `ClaudeRemote` helper calls: per-machine `watch`
  → `StateFlow<List<Session>>`, `open(ref)` → `StateFlow<ConversationState>` built by a reducer that
  consumes follow events (reuse the transcript reducer for `line`, overlay `draft`/`status`), and the
  write calls. Reconnect from `offset`.
- K3 Notifications / foreground service keyed on session id + state.
- **Tests:** JVM unit tests with fixtures for parsing and the reducer (draft→final replacement, peers,
  subagents, tasks, todos, questions, held sessions); extend `LocalE2ETest` into a daemon E2E
  (`TETHER_E2E=1`) that starts a haiku session, streams a draft, gets the final message, sends a second
  message after `stop` (wakes, same id), and removes it.

### U — UI (`ui/`)
- U1 Session screen: one screen for every session (merge `AgentScreen`, `SessionScreen`,
  `NativeAgentUi`); draft rendering; status line; composer always live; mode chip = Shift+Tab, model
  chip = `/model X`, Esc = stop button, images via upload + path; held-by-terminal bar; subagent rows
  open a read-only subagent view; peer message rows; task strip; todos.
- U2 Home: one list sorted needs-you then recent, machine/project filter chips, inline Allow/Deny,
  "in terminal" mark; Machine screen = filtered list. New session screen without kind picker.
- **Tests:** compose/unit tests where they exist; `./gradlew :app:assembleDebug` + unit tests green;
  emulator E2E (AVD `muxa35`, host at 10.0.2.2 over SSH, `seed.json` via DebugSeeder): start a session,
  watch it stream, reply, stop, reopen and reply again, screenshots of each screen.

### R — Removal and docs
- Delete live runs, `RunInfo`/`RunRef` run ids, `StartRunRequest` kinds, `native-*`, fork lineage,
  `SessionScreen`, kind picker, old helper commands; update README, site, `docs/SPEC.md`.

## Order

1. H1 (spike + client) — gate: live ping/list/dispatch/subscribe/reply/kill/resume verified.
2. H2–H4 ∥ K1–K2 (contract above).
3. K3 + U1 → U2 (sequential: one Gradle at a time).
4. Integration: daemon E2E (JVM) + emulator E2E.
5. R, then review → fix loop → final green run.

## Rules for every task

- Test as you go: no task is done until its tests pass and the output is in your report.
- Never more than one Gradle invocation at a time across agents (`flock /tmp/tether-gradle.lock ./gradlew …`).
- Live tests create sessions only with `--model haiku`-equivalent dispatch flags, in
  `~/tether-exp/live/`, and always remove them (`rm`), even on failure.
- Do not commit; the orchestrator commits. Do not touch files outside this worktree.
- Record any deviation from the protocol above in your report; the contract changes only through the orchestrator.
