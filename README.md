<div align="center">

<img src="site/mark.svg" width="80" height="80" alt="Tether logo">

# Tether

### Claude Code in your pocket.

Start Claude Code sessions on your own computers, watch them work, and approve what they do, from your Android phone.

[![Latest release](https://img.shields.io/github/v/release/iwm911/Tether?sort=semver&label=download&color=D97757)](https://github.com/iwm911/Tether/releases/latest)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#-get-started)

**[⬇️ Download the APK](https://github.com/iwm911/Tether/releases/latest)** &nbsp;·&nbsp; [🌐 Website](https://iwm911.github.io/Tether/) &nbsp;·&nbsp; [🔒 Privacy](PRIVACY.md)

</div>

---

## 💡 The idea

You give Claude Code a big task and walk away. Ten minutes later it stops and waits:
*"Can I run `npm test`?"* It sits there until you're back at the keyboard.

**Tether sends that question to your phone.** Tap **Allow**, read the diff, or send a new instruction,
from the couch, the train, or the queue at the coffee shop. The session keeps running on your computer,
with your files, your tools and your setup. It's the same session `claude agents` shows there: Tether
is a mobile `claude attach`.

<table>
<tr>
<td width="33%" valign="top">

### 📲 Approve from anywhere
Permission requests show up as notifications with **Allow · Deny · Reply** buttons. No need to open
the app. The command stays hidden on the lock screen, and answering needs your phone unlocked.

</td>
<td width="33%" valign="top">

### 🖥️ Runs on your machine
Claude works in your real repo with your tools, MCP servers and private network. Tether only
connects to it over SSH.

</td>
<td width="33%" valign="top">

### 🔁 Survives anything
Sessions run in Claude Code's own background service on the computer. Phone asleep, signal lost, app
closed: the work carries on, and Tether picks up where it left off.

</td>
</tr>
<tr>
<td valign="top">

### 🚀 Start from the phone
Pick a machine, browse to a folder, type a prompt. You don't need anything running on the computer
beforehand.

</td>
<td valign="top">

### 🗂️ All your machines, all your sessions
Laptop, desktop and servers on one home screen. Open **any** Claude Code session, running or from
last week, including ones you started at your desk, and just reply.

</td>
<td valign="top">

### 🔓 No account, no server
Open source. Your phone talks straight to your computers. There's no Tether cloud in between.

</td>
</tr>
</table>

## 🤔 Why not Remote Control or cloud sessions?

Anthropic has two official ways to use Claude Code from a phone, and both are good:
[**Remote Control**](https://code.claude.com/docs/en/remote-control) (continue a session running on
your computer from the Claude app) and
[**cloud sessions**](https://code.claude.com/docs/en/claude-code-on-the-web) (Claude works in a VM
in Anthropic's cloud). Tether fits a different need:

| | **Tether** | Remote Control | Cloud sessions |
|---|:---:|:---:|:---:|
| Where Claude works | 🖥️ your computers | 🖥️ your computer | ☁️ Anthropic's cloud |
| Start a brand-new session from the phone | ✅ any machine, any folder | ⚠️ only if `claude remote-control` is already running there | ✅ GitHub repos |
| Keeps running with no terminal open | ✅ Claude Code's background service | ❌ the `claude` process must stay open (use tmux) | ✅ |
| Works with API keys, Bedrock, Vertex, LLM gateways | ✅ whatever your Claude Code uses | ❌ claude.ai subscription only | ❌ claude.ai subscription only |
| Your local tools, MCP servers, private network | ✅ | ✅ | ❌ cloud environment only |
| Needs GitHub | ❌ | ❌ | ✅ |
| Continue sessions you started at the desk | ✅ every session on disk | ⚠️ only ones with Remote Control on | ⚠️ via the Desktop app's hand-off |
| Connection path | phone ⇄ SSH ⇄ your computer | via Anthropic's relay | via Anthropic's cloud |
| Open source | ✅ Apache 2.0 | ❌ | ❌ |
| iPhone / browser | ❌ Android only | ✅ | ✅ |

**Use Tether** when you want your own machines, any login method, nothing pre-started, and no relay.
**Use the official apps** when you're on iOS or in a browser, or when you can't reach your computer
over SSH.

## ⚙️ How it works

<p align="center"><img src="docs/assets/how-it-works.png" width="820" alt="Tether on your phone connects over SSH to a small helper on your computer, which talks to Claude Code's background service in your repo"></p>

1. Tether connects to your computer over **SSH**. Your SSH key is encrypted with a key held in the phone's secure hardware.
2. It installs a tiny helper script (`~/.tether/bin`, Python standard library only).
3. The helper talks to **Claude Code's own background service** (the one behind `claude agents`): it
   starts sessions there, wakes stopped ones, and types what you send, like `claude attach` would. The
   work doesn't depend on the phone staying connected.
4. Tether streams the conversation to your phone word by word and sends back your messages, keys and
   approvals.

## 🚀 Get started

**You need:** an Android phone (8.0+), and a Mac or Linux computer you can SSH into with
[Claude Code](https://code.claude.com/docs/en/quickstart) 2.1.286 or later and `python3` installed. Tailscale or
WireGuard works well for reaching it from outside your home network.

1. **Install.** Download the APK from [Releases](https://github.com/iwm911/Tether/releases/latest)
   and open it. Allow "Install unknown apps" when asked.
2. **Add your computer.** Enter `user@host`. Tap **New key → Install on server** to set up
   key login with your password once.
3. **Start a session.** Tap **New agent**, pick a folder, type what you want done, and put your phone
   away. It'll tell you when Claude needs you.

Tether updates itself from GitHub Releases, and every update is verified before it installs.
It checks in the background about twice a day and sends a notification when a new version is out
(with an Update button; turn it off in Settings › Notifications › New Tether versions).

## 🔒 Security & privacy

- 🔑 **Keys never leave the phone.** They're encrypted with a key held in the Android Keystore.
  Optionally, *Keys only while unlocked* makes them unreadable whenever the phone is locked.
- 📵 **Nothing to approve from the lock screen.** Approval notifications hide the command until you
  unlock, and Allow / Deny / Reply only work on an unlocked phone.
- 🔗 **Links from Claude's output are filtered.** Only web and email links open, after showing you
  the real address.
- 🛡️ **Host keys are pinned.** If a server's key changes, you get a loud warning before connecting.
- 👆 **Optional app lock** with fingerprint, face or screen lock.
- ✋ **Claude Code's permission rules still apply.** Tether passes your approvals through; it never
  bypasses them.
- 📊 **Anonymous usage stats** (app opens, sessions started) via open-source
  [Aptabase](https://aptabase.com). Nothing is sent until you've seen the notice, and it's one tap to
  turn off. **Never** your machines, prompts or code. [Exactly what's sent →](PRIVACY.md)

Found a security issue? Please report it
[privately](https://github.com/iwm911/Tether/security/advisories/new).

## 📖 More

<details>
<summary><b>Full feature list</b></summary>

- **One kind of thing: a session.** Every Claude Code session on each machine, keyed by its session
  id, exactly like `claude agents`. Opening yesterday's session works like opening one that is running
  now: same id, same history, the reply box is always live. A stopped session wakes up under its own
  id when you send it a message.
- **Home screen for your sessions.** Sessions waiting on you come first, with inline Allow / Deny;
  then working ones with live status and their last message; then recent ones. Filter by machine and
  project.
- **A conversation, not a terminal.** Replies stream word by word; Markdown; tool calls as quiet
  one-line rows that expand to diffs, command output and file previews.
- **Everything a terminal can do.** Any slash command, Shift+Tab to change the permission mode,
  `/model` to switch models, Esc to stop the current turn, images (copied to the machine and pasted as
  a path), voice dictation, queued messages.
- **Every prompt is answerable.** Tool permissions (*Allow once*, *Always allow*, *Deny*, optionally
  telling Claude what to do instead), `AskUserQuestion` as tappable choices, and Claude Code's own
  dialogs (new project MCP servers, folder trust, notices) with their options and a key pad.
- **Live plans, subagents, tasks and messages.** Claude's todo list as a pinned checklist, subagents
  you can open read-only, background tasks, and messages to and from other sessions.
- **Terminal sessions too.** A `claude` session open in a terminal on the computer is listed with its
  live transcript; type `/bg` there to continue it from the phone.
- **Built for mobile networks.** SSH keepalives, instant reconnect on network changes, optional
  foreground service so approvals keep arriving.
- **Native design.** Jetpack Compose + Material 3, dark and light themes, dynamic colour, haptics.

</details>

<details>
<summary><b>Under the hood</b></summary>

- SSH via **sshj** + BouncyCastle; the helper is uploaded over SFTP to
  `~/.tether/bin/tether_helper.py` and re-uploaded when it changes.
- Every start, wake, message, key and stop goes through the Claude Code daemon's control socket
  (`claude daemon`; the helper starts it the way the CLI does when it isn't running). Tether never
  runs `claude` itself to drive a session.
- The session list comes from the daemon's jobs, the live-process registry and the transcripts in
  `~/.claude/projects`; a `watch` command streams changes plus a heartbeat to detect dead links.
- A `follow` command streams one session: its transcript lines, the reply being written (cut from the
  daemon's screen stream), the spinner, state, subagents, tasks, todos and peer messages.
- Secrets: AES-256-GCM with a key generated inside the Android Keystore. Android backup and device
  transfer are disabled.

Architecture and protocol notes: [`docs/SPEC.md`](docs/SPEC.md).

</details>

<details>
<summary><b>Build from source</b></summary>

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease    # → app/build/outputs/apk/release/
```

Needs JDK 17+ and the Android SDK, on a local disk (not a network share). For signed release builds,
put `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) and the keystore in the
project root; both are gitignored.

| Gradle property | Purpose |
|---|---|
| `-PtetherUpdateRepo=owner/name` | Repo the in-app updater follows (default `iwm911/Tether`) |
| `-PtetherAptabaseKey=…` | Aptabase app key; unset = no analytics at all |
| `-PtetherVersionCode=N` / `-PtetherVersionName=X.Y.Z` | Override the version |

</details>

<details>
<summary><b>Publishing a release</b></summary>

```bash
tools/publish_update.sh release --code N --name X.Y.Z --notes "What's new"
```

Builds the signed APK and creates GitHub release `vX.Y.Z` on the current pushed commit, with the APK
and an `update.json` manifest (version code + SHA-256). Needs the `gh` CLI and `keystore.properties`.
Installed apps offer the update on their next check (launch, or the twice-daily background check,
which also posts a notification).

**Beta channel.** `tools/publish_update.sh beta --code N --name X.Y.Z-beta.N --notes "…"` publishes a
release-signed pre-release `vX.Y.Z-beta.N`. Only apps with **Settings › Beta updates** on are offered it
(beta builds have it on), and they always get the newer of the latest stable and the latest beta.
Stable and beta share one version-code sequence, so each new code must be higher than every published
stable and beta release; the script checks this. `tools/publish_update.sh debug` publishes a
pre-release that only debug builds pick up.

</details>

## 🤝 Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## 📄 License

[Apache License 2.0](LICENSE). Tether is an independent project, not affiliated with or endorsed by
Anthropic.
