# Tether — Claude Code in your pocket

Android app (Kotlin, Jetpack Compose, Material 3) that connects over **SSH** to the user's own
machines and lets them run, watch, steer and approve **Claude Code agents** from their phone.
UX-first, design-first: this must feel like a premium, calm, fast, native app — not a terminal
port. Package `app.tether`, minSdk 26, targetSdk 35. Project root: `~/AndroidStudioProjects/Tether`.

---------------------------------------------------------------------------------------------------
## 1. Product & UX principles

1. **Glanceable first.** Home answers "what are my agents doing, and does any need me?" in one look.
   "Needs you" (permission pending) outranks everything — amber, top of list, pulsing dot.
2. **Conversation, not terminal.** A Claude Code run is rendered as a beautiful chat: assistant prose
   in comfortable type, tool calls as compact one-line rows ("● Read  src/App.kt", "● Bash  npm test")
   that expand into rich detail (diffs, command output, file previews). Never dump raw JSON on the user
   (raw JSON only behind an explicit "View raw" affordance).
3. **Thumb-reachable decisions.** Permission prompts appear as a bottom decision sheet/card with big
   targets: **Allow once**, **Always allow `<rule>`** (from `permission_suggestions`), **Deny** (with
   optional "tell Claude what to do instead" text). Also actionable from the notification.
4. **Survives the phone.** Agents run detached on the remote machine; the phone can sleep, lose signal,
   be killed. Re-opening re-attaches instantly from a byte offset. Nothing is lost.
5. **Reproduce Claude Code's feel.** The ✻ spinner with a shimmering verb ("Pondering…  14s · ↓ 1.2k
   tokens"), the mode cycle (Ask → Accept edits → Plan → Auto, the CLI's Shift+Tab) as a tappable
   chip, the todo list as a live checklist, `/` slash-command autocomplete, Esc = a Stop button.
6. **Motion with purpose.** Spring-based, 160–450 ms, no gratuitous bounce. Items animate in
   (`animateItem()`), status changes crossfade, the send button morphs into stop while working.
   Haptic ticks on send / approve / deny / mode change (respect `LocalHapticsEnabled`).
7. **Design language: "warm terminal".** Charcoal ink (#131211) / cream paper (#FAF9F5), a single clay
   accent (#D97757), serif display headings (Source Serif), Inter UI, JetBrains Mono for anything the
   machine produced. Hairline borders (1dp `TetherTheme.colors.hairline`) instead of shadows/elevation.
   Generous gutters (`Space.gutter` = 20dp). Rounded 16–22dp cards. Dark is the default theme; light
   must also look great. Edge-to-edge: handle insets (`WindowInsets.safeDrawing`, `imePadding()`).
8. **Every state designed.** Empty, loading (skeleton shimmer, not a lone spinner), offline/unreachable
   (with retry), error (human sentence + detail expander), long content (truncate + expand).
9. **Accessibility.** contentDescription on icon buttons, 48dp min touch targets, sufficient contrast,
   respects font scale.

Use the shared design system — **do not fork it**:
- `app.tether.ui.theme`: `TetherTheme` (+ `TetherTheme.colors` extended colours, `TetherTheme.type.mono`
  / `.monoSmall` / `.eyebrow`), `Space`, `Motion`, `Palette`, fonts `Inter`/`Serif`/`Mono`.
- `app.tether.ui.components.Core.kt`: `TetherCard`, `TetherTopBar`, `PageHeader`, `SectionHeader`,
  `EmptyState`, `PrimaryButton`, `SecondaryButton`, `TagChip`, `StatusDot`, `StatusPill`,
  `RunStatus.color()/label/isLive`, `ClaudeSpinner`, `ShimmerText`, `WorkingIndicator`,
  `MachineAvatar`, `accentColor()`, `TetherTextField`, `ListRow`, `ToggleRow`, `CodeChip`, `Hairline`,
  `Modifier.accentEdge`, `rememberHaptics()`, formatters `relativeTime`, `formatElapsed`, `formatCost`,
  `compactNumber`, `prettyPath`, `projectName`.
You may add **private** components inside your own package. If you think a shared component is
missing, create it in your own package (e.g. `ui/home/HomeComponents.kt`); integration will hoist.

---------------------------------------------------------------------------------------------------
## 2. Architecture (fixed — see `core/Models.kt`, `core/Contracts.kt`, `AppContainer.kt`)

```
UI (Compose screens + ViewModels, per feature package)
   └─ LocalAppContainer.current  →  AppContainer (manual DI)
        ├─ AgentHub (remote/DefaultAgentHub)        ← screens + background service use this
        │    └─ ClaudeRemote (remote/HelperClaudeRemote) ─ runs tether_helper.py on the remote
        │         └─ SshManager (ssh/SshjManager, sshj + BouncyCastle)
        ├─ ConnectionRepository / KeyRepository / KnownHostsStore / SecretStore / SettingsRepository (data/)
        └─ HostKeyPromptBus (ssh/DefaultHostKeyPromptBus) ← HostKeyPromptHost dialog answers it
```
- ViewModels: `viewModel { FooViewModel(container, …) }` (lifecycle-viewmodel-compose `viewModel(initializer=)`),
  expose `StateFlow<UiState>`; collect with `collectAsStateWithLifecycle()`.
- No Hilt, no Room, no kapt/ksp. kotlinx.serialization for JSON (`Json { ignoreUnknownKeys = true }`).
- Contracts in `core/` are frozen. If you truly need an extra field, add it with a default value and
  mention it in your final report.

---------------------------------------------------------------------------------------------------
## 3. Verified Claude Code protocol facts (CLI 2.1.282 — tested on this machine 2026-09-25)

Real captures live in `app/src/test/resources/fixtures/` — **read them**:
`stream_multiturn_partial_interrupt.jsonl`, `stream_init_setmode.jsonl`,
`stream_permission_request.jsonl`, `transcript_session.jsonl`.

**Persistent agent process** (verified): stdin fed from an append-only file keeps the process alive
across turns:
```
tail -n +1 -f in.jsonl | claude -p --input-format stream-json --output-format stream-json \
   --verbose --include-partial-messages --permission-prompt-tool stdio \
   [--model M] [--permission-mode P] [--resume SID [--fork-session]]  > out.jsonl 2> err.log
```
- `--permission-prompt-tool stdio` is REQUIRED for live permission prompts (`--permission-prompts host`
  alone auto-denies). First line written to in.jsonl must be the initialize request:
  `{"type":"control_request","request_id":"init_1","request":{"subtype":"initialize"}}`
  Its `control_response.response` contains: `commands[{name,description,argumentHint}]` (slash
  commands), `agents[]`, `models[{value,displayName,description,…}]`, `current_permission_mode`,
  `pid`, `session_state`, `account` (never display email unless user opens About).
- User message line: `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"…"}]}}`.
  Images: add content blocks `{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"…"}}`.
  Sending while the agent is busy queues it (transcript shows `queue-operation` enqueue/dequeue).
- Output events (one JSON per line):
  - `system/status` {status, permissionMode}, `system/init` {cwd, session_id, tools, model, permissionMode, …}
  - `system/thinking_tokens` {estimated_tokens} — live thinking estimate (drive the working line).
  - `stream_event` {event:{type: message_start | content_block_start{index, content_block{type text|thinking|tool_use{id,name}}} |
    content_block_delta{index, delta{type text_delta{text} | thinking_delta{thinking} | input_json_delta{partial_json} | signature_delta}} |
    content_block_stop | message_delta{usage} | message_stop}, parent_tool_use_id}
  - `assistant` {message:{id, model, content:[ONE block], usage}, parent_tool_use_id} — one event PER
    content block; several share a `message.id`. These are the authoritative final blocks (they arrive
    after the partial stream for that block) → replace the streamed draft.
  - `user` {message:{content:[{type:tool_result, tool_use_id, content (string or [{type:text,text}]), is_error}]},
    tool_use_result (structured: e.g. Write/Edit `{type:"create"|"update", filePath, content, structuredPatch[{oldStart,oldLines,newStart,newLines,lines["+x","-y"," z"]}]}`,
    Bash `{stdout, stderr, interrupted}`), parent_tool_use_id} — also echoes of user text are NOT emitted
    unless --replay-user-messages.
  - `system/permission_denied` {tool_name, tool_use_id, message} (auto-denials / safety checks).
  - `control_request` {request_id, request:{subtype:"can_use_tool", tool_name, display_name, input,
    description, permission_suggestions[{type:"addRules",rules[{toolName,ruleContent}],behavior,destination} |
    {type:"addDirectories",directories[],destination}], blocked_path, tool_use_id}}
    → answer by appending to in.jsonl:
    `{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"behavior":"allow","updatedInput":<input object>,"updatedPermissions":[<suggestions chosen>]}}}`
    or `{"behavior":"deny","message":"…","interrupt":false}`. (`updatedInput` required — echo input.)
    A `control_cancel_request` {request_id} may appear → mark that prompt CANCELLED.
  - `control_response` (answers to OUR requests: initialize, interrupt `{still_queued:[]}`, set_*).
  - `result` {subtype: success | error_*, is_error, result, total_cost_usd (cumulative for process),
    duration_ms, duration_api_ms, num_turns, session_id, permission_denials[], usage} — one per turn.
    The process stays alive after `result`, waiting for the next user line.
  - `rate_limit_event` {rate_limit_info{status, rateLimitType, unifiedWindows{five_hour{utilization,resetsAt},…}}}
- Our control requests (append to in.jsonl, unique request_id):
  interrupt `{"subtype":"interrupt"}`, `{"subtype":"set_permission_mode","mode":"plan"}`,
  `{"subtype":"set_model","model":"sonnet"}` — all verified working live.
- Permission modes accepted by `--permission-mode`: acceptEdits, auto, bypassPermissions, manual,
  dontAsk, plan (+ "default" reported in status). Map to `core.PermissionMode`.
- **Transcripts** (history of every Claude Code session incl. desktop ones):
  `~/.claude/projects/<cwd with every non-alphanumeric char → '-'>/<sessionId>.jsonl`. Line types:
  `user` / `assistant` (same message shapes as above, plus `uuid, parentUuid, timestamp, cwd, gitBranch,
  isSidechain`), `attachment` (skip), `queue-operation`, `last-prompt` {lastPrompt}, `ai-title` {aiTitle},
  `custom-title` {customTitle}, `summary`, `atis-latch` (skip). `isSidechain:true` = subagent lines.
  User lines whose content is a string or text blocks are real prompts; ones with `tool_result` are
  tool results. Skip user text that starts with `<system-reminder>`, `<command-name>`, `<local-command`, or
  "Caveat:" (meta) — but render `<command-name>/foo</command-name>` as a slash-command chip.
- Remote PATH gotcha: non-interactive SSH does not load the user's shell rc. Resolve claude via
  (`Connection.claudePath`) → `command -v claude` in a login shell → `~/.local/bin/claude` →
  `~/.claude/local/claude` → `~/.npm-global/bin/claude` → `/usr/local/bin/claude` → `/opt/homebrew/bin/claude`.
- Claude Code refuses tool use under "sensitive" dirs like `~/.claude/…`; never put a run's cwd there.

---------------------------------------------------------------------------------------------------
## 4. Remote helper — `app/src/main/assets/tether_helper.py` (owned by the remote agent)

Single self-contained Python 3 (3.6+ compatible, stdlib only) script, uploaded by the app via SFTP to
`~/.tether/bin/tether_helper.py` when missing or when its `HELPER_VERSION` differs. All commands print
JSON to stdout. State dir `~/.tether/runs/<runId>/` with `meta.json`, `in.jsonl`, `out.jsonl`,
`err.log`, `pid` (process-group leader), `exit`. Commands (suggested):
- `probe` → hostname, os, home, claude path/version, python version.
- `projects` → from ~/.claude/projects: real cwd (read from transcript lines' `cwd`, not the lossy dir
  name), session count, last activity, exists, git branch.
- `sessions [--cwd P] [--limit N]` → SessionSummary list, newest first (title from custom-title >
  ai-title > first real prompt; read only what's needed — tail/head of big files).
- `transcript <sessionId>` → raw lines of the transcript file (find by globbing projects/*/<id>.jsonl).
- `start <json>` → create run dir, write initialize line to in.jsonl (+ first user message if any),
  spawn **detached** (`setsid`/`start_new_session`, `nohup`) a bash runner:
  `cd CWD && tail -n +1 -f in.jsonl | CLAUDE … > out.jsonl 2> err.log; echo $? > exit; kill 0`
  (kill 0 reaps the tail). Return RunInfo JSON.
- `runs` → RunInfo list (status derived: pid dead → ENDED/FAILED(exit≠0 & no result); unanswered
  can_use_tool (request_id in out.jsonl not in in.jsonl and not cancelled) → AWAITING_PERMISSION;
  last significant event after last user line is `result` → IDLE; else WORKING). Include lastText,
  cost, turns, sessionId (from system/init), pending permission summary. Must be cheap: cache per
  run by file size in `~/.tether/runs/<id>/.state.json`, parse only the new bytes.
- `watch` → long-running: every ~1 s check sizes/mtimes, print the full `runs` JSON line whenever
  anything changed (and a heartbeat line `{"hb":ts}` every 15 s so the app can detect dead links).
- `send <runId>` → reads raw JSONL lines from stdin, appends to in.jsonl (with fcntl lock).
- `stop <runId>` → SIGTERM the process group, then SIGKILL after 3 s. `delete <runId>` → stop + rm dir.
- `ls [path]` → DirListing JSON (dirs first, hide dotfiles except a few, mark git repos).
- The app tails with `tail -c +<offset+1> -F ~/.tether/runs/<id>/out.jsonl` over an exec channel.

---------------------------------------------------------------------------------------------------
## 5. Screens (entry composables — signatures fixed, called from `ui/nav/TetherNavHost.kt`)

| Package | Composable | Owner |
|---|---|---|
| `ui.onboarding` | `OnboardingScreen(onAddMachine: () -> Unit, onFinish: () -> Unit)` | C |
| `ui.connections` | `MachinesScreen(onBack, onAdd, onOpen: (String)->Unit, onEdit: (String)->Unit)` | C |
| `ui.connections` | `ConnectionEditorScreen(connectionId: String?, onBack, onSaved: (String)->Unit, onManageKeys)` | C |
| `ui.connections` | `KeysScreen(onBack)` | C |
| `ui.connections` | `HostKeyPromptHost()` (root-level dialog host) | C |
| `ui.settings` | `SettingsScreen(onBack, onOpenKeys, onOpenMachines)` | C |
| `ui.lock` | `AppLockGate(content: @Composable () -> Unit)` | C |
| `ui.home` | `HomeScreen(onOpenAgent: (RunRef)->Unit, onNewAgent: (String?)->Unit, onOpenMachine: (String)->Unit, onOpenMachines, onAddMachine, onOpenSettings)` | D |
| `ui.machine` | `MachineScreen(connectionId: String, onBack, onOpenAgent: (RunRef)->Unit, onOpenSession: (String, String)->Unit, onNewAgent: (String, String?)->Unit, onEdit: (String)->Unit)` | D |
| `ui.newagent` | `NewAgentScreen(connectionId: String?, cwd: String?, resumeSessionId: String?, onBack, onStarted: (RunRef)->Unit, onAddMachine)` | D |
| `ui.chat` | `AgentScreen(ref: RunRef, onBack, onOpenMachine: (String)->Unit)` | E |
| `ui.chat` | `SessionScreen(connectionId: String, sessionId: String, onBack, onContinue: (RunRef)->Unit)` | E |

(all `onBack`-style params are `() -> Unit`).

### Onboarding (C)
2–3 swipeable pages with a large serif headline, a simple animated illustration drawn in Compose
(e.g. phone ⟷ machine nodes joined by the clay tether line, pulses travelling along it), page dots,
"Add your first machine" primary CTA, "Skip" text button. Explain: runs on YOUR machines over SSH;
approve actions from anywhere; agents keep running when your phone sleeps.

### Machines + editor + keys (C)
- Machines list: cards with `MachineAvatar`, name, `user@host:port`, link state dot, claude version,
  "N agents running". Swipe or overflow for edit/delete (confirm delete). FAB/CTA add.
- Editor: name, host, port, username; auth segmented control **Key | Password**. Key mode: pick an
  existing key or "Generate new key" inline (Ed25519) → shows public key with Copy + Share, and an
  **"Install on server"** action that uses a password once (dialog) to append the key to
  authorized_keys, then switches auth to key. Import key (paste text or pick file via SAF
  `OpenDocument`) with optional passphrase. Advanced (collapsed): claude binary path, default
  directory, accent colour swatches. **Test connection** runs `SshManager.test` and shows the
  `TestStep` checklist animating step by step (✓ / spinner / ✗ with the error sentence), ending with
  "Claude Code 2.1.x found on <hostname>". Save enabled when required fields valid.
- Keys: list of keys (name, algorithm, fingerprint in mono, created), copy/share public key, delete
  (warn if used by a machine), generate/import.
- HostKeyPromptHost: observes `container.hostKeyPrompts.pending`; first-time: calm dialog "Trust this
  machine?" with key type + fingerprint (mono, grouped) → Trust / Cancel. Changed key: red warning
  "Host key changed — this could be an attack" with old vs new fingerprint; default action is Cancel.

### Settings (C) + App lock (C)
Sections: Appearance (Theme: System/Dark/Light segmented; Dynamic colour; Code text size slider),
Agents (default model, default permission mode, show thinking, compact tool rows), Notifications
(permission requests, turn complete, background watch), Security (biometric lock, known hosts list
with remove), Machines & keys links, About (version, "Tether is an independent client for Claude
Code"). `AppLockGate`: when `biometricLock` on, show a blurred/branded lock screen and
`BiometricPrompt` (BIOMETRIC_STRONG or DEVICE_CREDENTIAL) on start and after >60 s in background.
MainActivity is a `FragmentActivity`.

### Home (D) — the command centre
- Header: serif "Agents" (or greeting "Good evening" as eyebrow) + settings icon.
- **Needs you** section (only when any AWAITING_PERMISSION): prominent amber-edged cards with the
  pending tool summary ("wants to run `npm test`") and inline quick Allow / Deny buttons (call
  `agents.respond`) — approve without opening the chat.
- **Running** section: agent cards — project name (serif-ish title), machine avatar + name, status
  pill, live WorkingIndicator when WORKING, last assistant text (2 lines), elapsed/cost meta.
- **Recent** section: idle / ended agents (collapsible), swipe to remove ended.
- Machine strip: horizontal row of machine chips with link-state dots → machine screen; "+" chip.
- Empty state (no machines): welcoming EmptyState → add machine. (Machines but no agents): hero
  card "Start an agent" with recent projects quick-start.
- Pull-to-refresh (`PullToRefreshBox`), extended FAB "New agent" that shrinks on scroll.
- Machine unreachable errors from `agents.machineErrors` shown as a subtle banner per machine.

### Machine detail (D)
Header: avatar, name, user@host, link state, claude version, hostname. Tabs or sections:
**Agents** on this machine (live runs), **Projects** (from `remote.listProjects`, sorted by recent,
each expands/opens to its sessions), **Sessions** (recent sessions across projects: title, project,
relative time, message count, "open on desktop?" badge when `recentlyActive`). Tap session →
SessionScreen. Primary action "New agent here". Overflow: edit machine, disconnect.

### New agent (D)
A focused composer-style screen (slides up): machine picker (chips), **directory picker** (recent
projects list + "Browse…" opening a remote file browser bottom sheet using `remote.listDir`,
breadcrumb, git badge, "Use this folder"), model chips (from FallbackModels), permission mode
selector with descriptions (warn on Bypass), big multiline prompt field with voice dictation button
(`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via `rememberLauncherForActivityResult`), optional image
attach (Photo Picker `PickVisualMedia`), and a "Start agent" button → `agents.start(...)` →
`onStarted(ref)`. When `resumeSessionId` is set, show "Continuing: <session title>" header and pass
`resumeSessionId`. Remember last used machine/dir/model/mode (SharedPreferences in your package is fine).

### Agent conversation (E) — the heart of the app
- Top bar: back, project name + machine name/status, model & context meter; overflow: rename?,
  copy session id, open machine, stop agent (confirm), view raw log toggle.
- Todo strip: when `todos` non-empty, a collapsible "Plan · 2/5" card pinned under the top bar with
  a thin progress bar; expands to the checklist (✓ done struck-through, ◐ in-progress in clay with
  activeForm text, ○ pending).
- Message list (`LazyColumn`, stable keys, `animateItem()`), auto-scroll to bottom while at bottom,
  "↓ New activity" pill when scrolled up:
  - User: right-aligned soft bubble (`colors.userBubble`), queued badge.
  - Assistant text: full-width Markdown (write your own renderer: headings, paragraphs, bold/italic,
    inline code, fenced code blocks with language label + copy + horizontal scroll + light syntax
    highlighting, bullet/numbered lists, blockquotes, links (open via UriHandler), tables (horizontal
    scroll), hr). Streaming text shows a soft blinking caret.
  - Thinking: collapsed row "✻ Thought for 6s" (italic, faint) → expands (hide entirely if
    `showThinking` false).
  - ToolCall: one-line row with status glyph (spinner while running, ✓ green, ✗ red, ⊘ denied),
    tool-specific icon + verb + mono target (`Read  src/App.kt`, `Edit  build.gradle (+3 −1)`,
    `Bash  ./gradlew test`, `Grep  "foo" in src/`, `Glob  **/*.kt`, `WebFetch  host`, `WebSearch "q"`,
    `Task  <description>` with nested children, `TodoWrite` → hide row (drives the todo strip)).
    Tap to expand: Bash = command block + output (stdout/stderr, collapsed after 12 lines with
    "Show all"); Edit/MultiEdit = unified diff with line numbers from `structuredPatch` (fallback:
    old_string/new_string diff) in add/del colours; Write = file preview (first 40 lines); Read =
    path + line range + (result collapsed); others = pretty key/value input + text result.
  - Permission: inline card (amber hairline) summarising what Claude wants + the decision buttons;
    when answered, collapses to "Allowed once · Bash npm test" / "Denied". The newest pending one is
    ALSO shown as a sticky decision panel above the composer so it's always reachable.
  - TurnSummary: tiny centred meta line "✓ 3 turns · 12.4s · $0.04" or error text in danger colour.
  - Notice: centred small caps line (mode changed, interrupted, session started…).
- Working line: `WorkingIndicator(since = workingSince, tokens = thinkingTokens)` at the list end
  while WORKING.
- Composer (bottom, `imePadding`): mode chip (tap cycles PermissionMode.cycle via
  `agents.setPermissionMode`, long-press shows all modes incl. Bypass), model chip (sheet from
  `state.models` or FallbackModels → `agents.setModel`), attach image, voice, multiline field
  ("Message Claude…"), send button that morphs into a **Stop** (square) button while WORKING
  (→ `agents.interrupt`). Typing `/` shows a slash command popup filtered from `state.commands`.
  Sending while working is allowed (queues) — show it with a "queued" badge.
- Ended agent: composer replaced by "This agent has ended · Resume" (start a new run with
  `resumeSessionId = state.sessionId`, then `onContinue`-style navigate — use `onBack` + open new
  ref? → AgentScreen gets the new RunRef from agents.start; navigate by calling a lambda you get
  via a local `LocalAgentNavigator`? Keep it simple: AgentScreen may call `agents.start` and then
  swap its own `ref` state to the new run in-place).
- Link states: small banner "Reconnecting…" when link not Connected; errors with Retry.
- SessionScreen: read-only transcript (`agents.transcript`) rendered with the same components, top
  bar title = session title, and a bottom "Continue this conversation" composer — sending starts a
  run with `resumeSessionId` + prompt and calls `onContinue(ref)`.

---------------------------------------------------------------------------------------------------
## 6. Background & notifications (owned by A, `app.tether.service`)

- `ServiceController.install(app, container)`: observes `container.agents.agents` + settings +
  process lifecycle (`ProcessLifecycleOwner`); when app is backgrounded and `backgroundWatch` and any
  agent is live (STARTING/WORKING/AWAITING_PERMISSION) → start `AgentWatchService` (foreground,
  type dataSync, ongoing low-importance notification "2 agents working · 1 needs you") and call
  `agents.setBackgroundWatch(true)`; stop it when none live or app foregrounded.
- Notifications from `container.agents.events` (only when app not in foreground, or the event's
  agent screen is not open): channel "Needs approval" (high importance) with actions **Allow** and
  **Deny** (`NotificationActionReceiver` → `agents.respond`), channel "Agent updates" for turn
  complete / ended. Content intent opens `MainActivity` with `EXTRA_CONNECTION_ID` / `EXTRA_RUN_ID`.
- `DebugSeeder.run(context, container)` (debug builds only): if
  `context.getExternalFilesDir(null)/seed.json` exists → import `{keys:[{id,name,privateKey}],
  connections:[Connection JSON…], settings:{onboardingDone:true}}` then delete the file. Used for
  automated end-to-end tests on the emulator.

---------------------------------------------------------------------------------------------------
## 7. Rules for builder agents

- Write ONLY inside your owned packages/files (listed in your task). Never edit `core/`,
  `ui/theme/`, `ui/components/Core.kt`, `AppContainer.kt`, `MainActivity.kt`, `TetherApp.kt`,
  `ui/nav/` — if something there blocks you, work around it and report.
- **Do not run Gradle** during the build phase (other agents' files are mid-flight; concurrent
  builds fight over locks). Integration compiles everything afterwards. Write code carefully:
  correct imports, no pseudo-code, no TODO stubs for core behaviour, Compose BOM 2024.12.01 /
  Material3 1.3.x APIs, Kotlin 2.1.
- Never use git worktrees. Do not commit (the orchestrator commits).
- Quality bar: production-ready, polished, every state handled. This is the product.

---------------------------------------------------------------------------------------------------
## 8. Visual direction update (user request, 2026-09-25): "look like Claude Cowork — sleek"

The reference is the Claude desktop / Cowork apps. Concretely:
- Palette already retuned in `Theme.kt`: dark = warm greys (#262624 background, #30302E cards/input,
  #1F1E1D code), light = cream #FAF9F5 with white cards. Default theme follows the system.
- **Assistant prose is set in the serif** (`Serif`, regular 16.5sp / 26sp line height) — like
  claude.ai. UI chrome stays Inter; code stays JetBrains Mono.
- Greeting on Home: the clay spark mark + serif "Good evening" headline, centred-feeling calm.
- **The composer is the hero**: one big rounded (24–28dp) card on `surfaceContainer`, soft shadow in
  light mode / hairline in dark, placeholder "Reply to Claude…" / "How can I help you today?",
  chips (mode, model, attach) inside the card's bottom row, round clay send button.
- User messages: compact rounded bubble (`userBubble`), right aligned. Assistant: no bubble, full
  width, serif, generous line height.
- Minimal chrome: flat top bars, no filled app bars, few dividers, lots of whitespace, soft
  rounded surfaces rather than heavy borders (hairlines only where grouping needs it).
- Tool calls: quiet one-line rows in muted Inter/mono, like Claude's collapsible "Used tool" rows.
- Motion: gentle fades/slides; nothing bouncy.
- Brand mark: `R.drawable.ic_launcher_foreground` — a clay 10-ray spark. Reuse it (Icon painter)
  for the greeting, empty states and lock screen.
