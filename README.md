# Tether — Claude Code in your pocket

An Android app that connects to your own machines over SSH and lets you start, watch, steer and
approve Claude Code agents from your phone. Agents run detached on the machine, so they keep
working when the phone sleeps, loses signal or the app is killed.

## Install

`dist/Tether-1.3.0.apk` (release-signed, minSdk 26 / Android 8+).

- Copy it to the phone and open it (allow "Install unknown apps" for your file manager), or
- `adb install dist/Tether-1.3.0.apk`

Release builds are signed with your own key: create `keystore.properties` (storeFile, storePassword,
keyAlias, keyPassword) next to a keystore in the project root — both are gitignored. Updates must be
signed with the same key to install over an existing copy.

## Machine requirements

- SSH server reachable from the phone (LAN, Tailscale/WireGuard, etc.)
- Claude Code installed and logged in (`claude --version` works for that user)
- `python3` (the app installs a small stdlib-only helper at `~/.tether/bin/tether_helper.py`)

## First connection

1. Add machine → host (paste `user@host:port` to fill everything), Key → **New key** →
   **Install on server** (uses your password once) — or import an existing private key.
2. **Test connection** walks through reach → host key → sign in → find Claude Code.
3. Trust the host key fingerprint when asked.

## Two kinds of agents

| | Live (recommended) | Background |
|---|---|---|
| Runs as | `claude -p` stream-json, driven by Tether | native `claude --bg` |
| Approve tool use from the phone | ✅ panel + notification actions | ❌ approvals happen on the computer |
| Streaming, interrupt, mode/model switch | ✅ | stop only |
| Shows in `claude agents` on the computer | — | ✅ |
| Reply | any time (queues while busy) | when it's idle/done |

Past sessions (including ones started on the desktop) are browsable per machine and can be
continued from the phone.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease    # → app/build/outputs/apk/release/
```
The project must live on a local disk (not the CIFS share). See `docs/SPEC.md` for the architecture,
the verified Claude Code protocol notes and the design direction.

## Updates (built in)

Tether updates itself from your own machines — nothing is hosted anywhere else.

```bash
tools/publish_update.sh release --notes "What's new"      # bump with --code N --name X.Y.Z
```
This builds the signed release and stages it in `~/.tether/app/` (APK + `update.json` with the
SHA-256). Every phone that has this machine saved sees "Tether X.Y.Z is available" on Home at its
next launch (or Settings → Updates → Check now), downloads it over SFTP, verifies the checksum and
installs it after Android's confirmation (first time: allow "Install unknown apps" for Tether).
To serve it from other machines too: `rsync -a ~/.tether/app/ other-host:.tether/app/`.
Builds must be signed with `tether-release.jks` or Android refuses the update.
