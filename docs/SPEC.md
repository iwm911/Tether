# Tether — Claude Code in your pocket

Android app (Kotlin, Jetpack Compose, Material 3) that connects over **SSH** to the user's own
machines and lets them run, watch, steer and approve **Claude Code sessions** from their phone.
One kind of thing, a **session**, keyed by its session id, exactly like `claude agents`: the phone is a
mobile `claude attach` (design: `docs/plans/one-session-daemon.md`).
UX-first, design-first: this must feel like a premium, calm, fast, native app — not a terminal
port. Package `app.tether`, minSdk 26, targetSdk 35. Project root: `~/AndroidStudioProjects/Tether`.

---------------------------------------------------------------------------------------------------
## 1. Product & UX principles

1. **Glanceable first.** Home answers "what are my sessions doing, and does any need me?" in one look.
   "Needs you" (any blocking prompt) outranks everything — amber, top of list, pulsing dot.
2. **Conversation, not terminal.** A Claude Code session is rendered as a beautiful chat: assistant prose
   in comfortable type, tool calls as compact one-line rows ("● Read  src/App.kt", "● Bash  npm test")
   that expand into rich detail (diffs, command output, file previews). Never dump raw JSON on the user
   (raw JSON only behind an explicit "View raw" affordance).
3. **Thumb-reachable decisions.** Permission prompts appear as a bottom decision sheet/card with big
   targets: **Allow once**, **Always allow `<rule>`** (from `permission_suggestions`), **Deny** (with
   optional "tell Claude what to do instead" text). Also actionable from the notification.
4. **Survives the phone.** Sessions live in Claude Code's daemon on the remote machine; the phone can
   sleep, lose signal, be killed. Re-opening resumes from a transcript offset. Nothing is lost.
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
## 2. Architecture (see `core/Contracts.kt`, `core/Sessions.kt`, `AppContainer.kt`)

```
UI (Compose screens + ViewModels, per feature package)
   └─ LocalAppContainer.current  →  AppContainer (manual DI)
        ├─ SessionHub (remote/DefaultSessionHub)     ← screens + background service use this
        │    ├─ per machine: helper `watch`  → sessions: StateFlow<List<Session>> + events
        │    ├─ per session: helper `follow` → SessionReducer (+ TranscriptReducer) → ConversationState
        │    └─ SessionRemote (remote/HelperClaudeRemote) ─ runs tether_helper.py on the remote
        │         └─ SshManager (ssh/SshjManager, sshj + BouncyCastle)
        ├─ ClaudeRemote (same HelperClaudeRemote): probe, projects, ls, commands
        ├─ ConnectionRepository / KeyRepository / KnownHostsStore / SecretStore / SettingsRepository (data/)
        └─ HostKeyPromptBus (ssh/DefaultHostKeyPromptBus) ← HostKeyPromptHost dialog answers it
```
- ViewModels: `viewModel { FooViewModel(container, …) }` (lifecycle-viewmodel-compose `viewModel(initializer=)`),
  expose `StateFlow<UiState>`; collect with `collectAsStateWithLifecycle()`.
- No Hilt, no Room, no kapt/ksp. kotlinx.serialization for JSON (`Json { ignoreUnknownKeys = true }`).
- Wire types of the helper protocol live in `core/Sessions.kt`; every field has a default so a helper
  that adds or omits one still parses. `HelperContractTest` checks them against the helper's real output
  (`app/src/test/resources/fixtures/contract/`, written by `tools/helper_tests/test_contract.py`).

---------------------------------------------------------------------------------------------------
## 3. Claude Code facts the app relies on (2.1.286, daemon `proto` 1)

The full list (socket, ops, dispatch spec, files) is in `docs/plans/one-session-daemon.md`
("Daemon facts"). In short:
- **Everything goes through the daemon.** Start (`dispatch` with `launch.mode:"prompt"`), wake
  (`dispatch` `launch.mode:"resume"` with the same short id), message (`reply`), keys (`attach`, raw
  pty bytes: Shift+Tab `\x1b[Z`, Esc `\x1b`), stop (`kill`), remove (`kill` evict + job dir). Tether
  never runs `claude` itself to drive a session; the helper only starts `claude daemon` when it is not
  running (as the CLI does), and runs a read-only `claude -p` initialize to list slash commands.
- **Live screen.** `subscribe` streams the session's pty; the helper replays it through a small
  terminal emulator to cut the reply being written (draft) and the spinner line (status), and to read
  blocking dialogs (MCP servers, folder trust, notices) with their options.
- **Session sources.** `~/.claude/jobs/<short>/state.json` (daemon jobs), `~/.claude/sessions/<pid>.json`
  (live processes: `kind` bg | interactive; an interactive entry = a terminal holds the session),
  `~/.claude/daemon/roster.json`, and transcripts
  `~/.claude/projects/<cwd with every non-alphanumeric char → '-'>/<sessionId>.jsonl`.
- **Transcripts** are the history of every session, including ones started at the desk. Line types:
  `user` / `assistant` (`message:{id, model, content:[blocks], usage}` plus `uuid, parentUuid,
  timestamp, cwd, gitBranch, isSidechain`), `system` (local_command, compact_boundary, status…),
  `attachment` (skip), `queue-operation` (peer messages and task notifications arrive here),
  `last-prompt`, `ai-title`, `custom-title`, `summary`. `isSidechain:true` = subagent lines (their own
  files under `<sid>/subagents/`). User lines whose content is text are prompts; ones with
  `tool_result` are tool results (`toolUseResult` has the structured form, e.g. Edit/Write
  `structuredPatch`). Skip meta text (`<system-reminder>`, `<local-command…`, "Caveat:"), but render
  `<command-name>/foo</command-name>` as a slash command. A fixture: `transcript_session.jsonl`.
- **Permission mode** is not in state.json: the helper reads the job's `--permission-mode` flag (the
  daemon rewrites it on every Shift+Tab), else the transcript.
- **Folder trust** is not enforced by the daemon; the helper checks `~/.claude.json` itself (`EUNTRUSTED`).
- Remote PATH gotcha: non-interactive SSH does not load the user's shell rc. Resolve claude via
  (`Connection.claudePath`) → `command -v claude` in a login shell → `~/.local/bin/claude` →
  `~/.claude/local/claude` → `~/.npm-global/bin/claude` → `/usr/local/bin/claude` → `/opt/homebrew/bin/claude`.
- Claude Code refuses tool use under "sensitive" dirs like `~/.claude/…`; never start a session there.

---------------------------------------------------------------------------------------------------
## 4. Remote helper — `app/src/main/assets/tether_helper.py`

Single self-contained Python 3 (3.6+ compatible, stdlib only) script, uploaded by the app via SFTP to
`~/.tether/bin/tether_helper.py` when missing or when its content changes. `HELPER_VERSION` 2.0.0.
All commands print JSON to stdout; errors are `{"error": "<sentence>", "code": "<CODE>"}` with codes
`ENODAEMON EAUTH EPROTO EHELD ENOSESSION EUNTRUSTED ETIMEOUT EDAEMON`. The contract (Session object,
follow events) is in `docs/plans/one-session-daemon.md`.
- `version`, `probe` (host, claude, python, plan, `daemon:{running, proto, version, auth}`),
  `daemon-status`.
- `projects` → from ~/.claude/projects: real cwd, session count, last activity, exists, git branch.
- `sessions [--cwd P] [--limit N] [--before MS]` → `{"sessions":[Session…]}` newest first: daemon jobs
  + terminal sessions + transcripts with no job.
- `watch` → `{"snapshot":[…]}`, then `{"changed":[…],"removed":[sid…]}` per change, `{"hb":ms}` every 15 s.
- `follow <sid> [--agent ID] [--from OFFSET]` → event lines: `line` (a transcript line + offset),
  `draft` / `draftClear`, `status`, `state`, `peer`, `subagent`, `task`, `todos`, `caughtUp`.
- Writes: `new` {cwd, prompt, model?, permissionMode?, images?, trust?}, `send <sid>` {text, images?}
  (wakes a stopped session under its own id), `key <sid>` {keys}, `answer <sid>` {decision, message?},
  `ask <sid>` {answers}, `interrupt <sid>`, `stop <sid>`, `rm <sid>`. `EHELD` while a terminal holds it.
- `transcript <sessionId>`, `ls [path]` (DirListing), `commands [--cwd P]` (slash commands).
- Images the phone attaches are uploaded to `~/.tether/uploads/` and pasted as paths, like a file
  dropped on the terminal.
- Older helpers kept their own runs in `~/.tether/runs/`; 2.x ignores that folder. Those conversations
  are ordinary sessions now and show up through their transcripts.
- Tests: `tools/helper_tests/` (unit tests with a fake home and a scripted daemon; `TETHER_LIVE=1` runs
  live ones against this machine's daemon with throwaway haiku sessions).

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
| `ui.home` | `HomeScreen(onOpenSession: (SessionRef)->Unit, onNewAgent: (String?)->Unit, onOpenMachine: (String)->Unit, onOpenMachines, onAddMachine, onOpenSettings)` | D |
| `ui.machine` | `MachineScreen(connectionId: String, onBack, onOpenSession: (SessionRef)->Unit, onNewAgent: (String, String?)->Unit, onEdit: (String)->Unit)` | D |
| `ui.newagent` | `NewAgentScreen(connectionId: String?, cwd: String?, onBack, onStarted: (SessionRef)->Unit, onAddMachine)` | D |
| `ui.chat` | `SessionChatScreen(ref: SessionRef, agentId: String?, onBack, onOpenMachine: (String)->Unit, onOpenSubagent: (String)->Unit)` | E |

(all `onBack`-style params are `() -> Unit`). Every session, running or not, opens on
`SessionChatScreen`; `agentId` opens one of its subagents read-only.

### Onboarding (C)
2–3 swipeable pages with a large serif headline, a simple animated illustration drawn in Compose
(e.g. phone ⟷ machine nodes joined by the clay tether line, pulses travelling along it), page dots,
"Add your first machine" primary CTA, "Skip" text button. Explain: runs on YOUR machines over SSH;
approve actions from anywhere; sessions keep running when your phone sleeps.

### Machines + editor + keys (C)
- Machines list: cards with `MachineAvatar`, name, `user@host:port`, link state dot, claude version,
  "N running". Swipe or overflow for edit/delete (confirm delete). FAB/CTA add.
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
- Header: greeting + settings icon; machine and project filter chips.
- One list of sessions from `sessions.sessions`: **needs you** first (amber-edged rows with the
  pending tool summary and inline Allow / Deny via `sessions.answer`; questions and dialogs open the
  session), then by most recent activity. Rows show title, project, machine, state, last message,
  an "in terminal" mark for terminal-held sessions.
- Empty state (no machines): welcoming EmptyState → add machine. Machines but no sessions: hero card
  "Start an agent" with recent projects quick-start. "Connecting to your machines…" while connecting.
- Pull-to-refresh, extended FAB "New agent" that shrinks on scroll; per-machine errors from
  `sessions.machineErrors` as a subtle banner.

### Machine detail (D)
Header: avatar, name, user@host, link state, claude version, hostname. The same session list filtered
to this machine, plus its **Projects** (from `remote.listProjects`). Tap a session → SessionChatScreen.
Primary action "New agent here". Overflow: edit machine, disconnect.

### New agent (D)
A focused composer-style screen (slides up): machine picker (chips), **directory picker** (recent
projects list + "Browse…" opening a remote file browser bottom sheet using `remote.listDir`,
breadcrumb, git badge, "Use this folder"), model chips (from FallbackModels), permission mode
selector with descriptions (warn on Bypass), big multiline prompt field with voice dictation button
(`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via `rememberLauncherForActivityResult`), optional image
attach (Photo Picker `PickVisualMedia`), and a "Start agent" button → `sessions.new(...)` →
`onStarted(ref)`. An untrusted folder (`EUNTRUSTED`) asks before trusting it. Remember last used
machine/dir/model/mode (`NewAgentPrefs`). There is no kind picker: every session is a daemon session.

### Session conversation (E) — the heart of the app
- Top bar: back, title + machine name/status, model & context meter; overflow: copy session id,
  open machine, stop (retires the worker; the next message wakes it with the same id), remove.
- Todo strip: when `todos` non-empty, a collapsible "Plan · 2/5" card pinned under the top bar with
  a thin progress bar; expands to the checklist (✓ done struck-through, ◐ in-progress in clay with
  activeForm text, ○ pending).
- Message list (`LazyColumn`, stable keys, `animateItem()`), auto-scroll to bottom while at bottom,
  "↓ New activity" pill when scrolled up:
  - User: right-aligned soft bubble (`colors.userBubble`), queued badge.
  - Assistant text: full-width Markdown (write your own renderer: headings, paragraphs, bold/italic,
    inline code, fenced code blocks with language label + copy + horizontal scroll + light syntax
    highlighting, bullet/numbered lists, blockquotes, links (open via UriHandler), tables (horizontal
    scroll), hr). The reply being written (`draft` from `follow`) shows as a streaming row with a
    soft blinking caret and is replaced by the transcript line when it lands.
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
- Working line: the session's spinner line (`status` from `follow`: verb, elapsed, tokens) at the
  list end while it works.
- Composer (bottom, `imePadding`), always live: mode chip (one tap = Shift+Tab through `sessions.key`),
  model chip (sends `/model X`), attach image (uploaded, path pasted), voice, multiline field, send
  button that morphs into a **Stop** button while working (Esc via `sessions.interrupt`). Typing `/`
  shows a slash command popup (`sessions.slashCommands`). Sending while working queues the message.
  Sending to a stopped session wakes it (same id).
- Held by a terminal: the composer is replaced by "Open in a terminal on <machine> · type /bg there to
  continue here"; the transcript stays live.
- Blocking prompts: permission / question panels, and a dialog panel for `pending.kind:"dialog"`
  (native MCP-servers checklist and trust panel, otherwise the screen text + a key pad).
- Subagent rows open a read-only subagent view; peer messages, background tasks and todos have their
  own rows / strips.
- Link states: small banner "Reconnecting…" when link not Connected; errors with Retry.

---------------------------------------------------------------------------------------------------
## 6. Background & notifications (`app.tether.service`)

- `ServiceController.install(app, container)`: observes `container.sessions.sessions` + settings +
  process lifecycle (`ProcessLifecycleOwner`); when app is backgrounded and `backgroundWatch` and any
  session is live (working or needs you, not held by a terminal) → start `AgentWatchService`
  (foreground, ongoing low-importance notification "2 agents working · 1 needs you") and call
  `sessions.setBackgroundWatch(true)`; stop it when none live or app foregrounded.
- Notifications from `container.sessions.events` (only when the app is not in the foreground):
  channel "Needs approval" (high importance) with actions **Allow**, **Deny** and **Tell Claude**
  (`NotificationActionReceiver` → `sessions.answer`; questions and dialogs open the session), channel
  "Agent updates" for turn done / failed. Content intent opens `MainActivity` with
  `EXTRA_CONNECTION_ID` / `EXTRA_SESSION_ID`.
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
