# Privacy

Tether is built so that your code and conversations stay on your own machines. This page explains in
plain language what the app sends, where it goes, and how to turn it off.

## The short version

- Tether has **no servers and no accounts**. It connects directly from your phone to machines you
  own, over SSH.
- The app sends **anonymous usage analytics** via [Aptabase](https://aptabase.com), an open-source,
  privacy-first analytics service, so we can see which features get used and whether updates land.
- Analytics are **on by default**. The app shows a notice on Home, and nothing is sent until you acknowledge it, and you can turn them off
  with one tap in **Settings → Privacy**.
- We never collect anything that identifies you, your machines or your work.

## What is never collected

- Device identifiers, advertising IDs or any persistent install ID
- Accounts, names or email addresses (Tether has no accounts)
- Hostnames, IP addresses, ports or SSH usernames of your machines
- File paths, project or folder names
- Prompts, Claude's replies, code, diffs, command output or any other session content
- SSH keys, passwords, passphrases or host-key fingerprints

## What is collected (when analytics are on)

These are all the events the app sends. The code is in
[`Analytics.kt`](app/src/main/java/app/tether/analytics/Analytics.kt).

| Event | When | Extra properties |
|---|---|---|
| `first_open` | First launch after install | none |
| `app_updated` | First launch after an update | `from`: previous version code |
| `app_opened` | App comes to the foreground | `machines`: how many machines are saved, bucketed as `0`, `1`, `2-3`, `4+` |
| `machine_connected` | A saved machine's SSH link first comes up (once per machine per app run) | none |
| `agent_started` | You start or resume an agent | `model` (e.g. `default`, `opus`), `mode` (permission mode), `resumed` (yes/no), `images` (count) |
| `message_sent` | You send a message to an agent | `images` (count) |
| `permission_answered` | You answer a permission prompt | `decision`: `allow`, `always`, `deny` or `answer` |
| `conversation_branched` | You branch a conversation | `rewind` (yes/no) |

Every event also carries the **app version and build, Android version, locale** (e.g. `en-US`), a
**debug-build flag**, and a **random session id** that is generated on the phone and replaced after
an hour of inactivity. It is not tied to you or your device and can't follow you across sessions.

Aptabase uses the request's IP address to look up a coarse **country and region**, then discards the
IP; it doesn't use cookies or device fingerprinting.

**Nothing is sent before you've seen the notice.** Events wait in memory until you tap **OK** on the
notice card on Home. Tapping **Turn off** there (or later in Settings) discards them. Failed sends are
dropped, never stored.

## How to turn it off

Open **Settings → Privacy** and switch analytics off. Nothing more is sent from that point on. You
can switch it back on at any time.

Builds that don't include an Aptabase key send nothing at all. That covers forks and any build made
without `-PtetherAptabaseKey`.

## Where the data goes

Events go straight from the app to Aptabase's cloud: the **EU or US region**, depending on the region
of the app key the build was made with. They pass through no other service. Aptabase keeps the data
under [its own retention policy](https://aptabase.com/legal/privacy). Only aggregated counts are used
to decide what to improve in Tether.

## Other network traffic

- **Your machines, over SSH.** This is what the app does. Everything you see in Tether (sessions,
  agents, files) is read from your machines and shown on your phone. Private keys, passphrases and
  passwords are stored on the phone, encrypted with a key held in the Android Keystore. App data is
  excluded from Android cloud backups and device transfer.
- **GitHub.** To check for updates (on launch and about twice a day in the background), the app asks the GitHub Releases API for the latest release of
  `iwm911/Tether` (or a fork's configured repo) and downloads the APK from there. GitHub's
  [privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement)
  applies to those requests.
- **Voice dictation.** If you use it, Android's speech recognition service handles it, not Tether.

## Questions

Open an issue at [github.com/iwm911/Tether](https://github.com/iwm911/Tether/issues). Because the
app is open source, you can also check all of the above in the code.
