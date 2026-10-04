# Reach your computer from anywhere with Tailscale

Tether talks to your computer over SSH. At home on the same Wi-Fi that just works. To reach the same
computer from the train or the office, the simplest way is [Tailscale](https://tailscale.com): a free
private network between your own devices, with no ports to open on your router.

This takes about five minutes.

## 1. Put the computer and the phone on the same tailnet

1. Install Tailscale on the computer that runs Claude Code
   ([macOS](https://tailscale.com/download/mac), [Linux](https://tailscale.com/download/linux)) and sign in.
2. Install [Tailscale for Android](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on
   your phone and sign in with **the same account**.
3. On the computer, find its Tailscale name:

   ```sh
   tailscale status
   ```

   The first line is this machine, for example `100.101.102.103  workstation  you@  linux  -`. Use the
   name (`workstation`) if MagicDNS is on, which it is by default, or the `100.x.y.z` address.

## 2. Make sure the computer accepts SSH

- **macOS:** System Settings › General › Sharing › turn on **Remote Login**.
- **Linux:** install and start the OpenSSH server, for example on Ubuntu or Debian:

  ```sh
  sudo apt install openssh-server
  sudo systemctl enable --now ssh
  ```

Tether needs ordinary SSH with your user account. You don't need Tailscale SSH.

## 3. Add the computer in Tether

1. With Tailscale connected on the phone, open Tether and add a machine: `you@workstation` (your user
   name on the computer, then the Tailscale name from step 1).
2. Tap **New key → Install on server** and enter your computer password once. Tether installs its own
   key, and from then on logs in with the key, never the password.
3. Tether shows the server's host key the first time. It is pinned from then on, and you get a warning
   if it ever changes.

That's it. As long as Tailscale is on, Tether reaches the computer from any network, and your sessions
keep running on the computer even when the phone is offline.

## Troubleshooting

- **"Connection timed out":** check that Tailscale is connected on the phone (the key icon in the status
  bar) and that the computer shows as online in the Tailscale app.
- **"Connection refused":** SSH isn't running on the computer; see step 2.
- **The name doesn't resolve:** use the `100.x.y.z` address from `tailscale status` instead.
- **Several computers:** repeat step 3 for each one. They all show up on Tether's home screen.
