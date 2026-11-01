<h1 align="center">Claude Obhod</h1>

<p align="center"><b>🇬🇧 English</b> · <a href="README.ru.md">🇷🇺 Русский</a></p>

<p align="center">
  <b>Claude on Android, through your own server.</b><br>
  For Russia, China, North Korea and everyone else where Claude is blocked.<br>
  <sub>Version <b>1.0.0</b>, first public release</sub>
</p>

<p align="center">
  <a href="https://github.com/syntaxixr/claude-obhod/releases/latest"><img src="https://img.shields.io/github/v/release/syntaxixr/claude-obhod?label=version&color=D97757&style=flat-square" alt="version"></a>
  <a href="https://github.com/syntaxixr/claude-obhod/releases/latest"><img src="https://img.shields.io/github/downloads/syntaxixr/claude-obhod/total?label=downloads&color=D97757&style=flat-square" alt="downloads"></a>
  <a href="https://github.com/syntaxixr/claude-obhod/stargazers"><img src="https://img.shields.io/github/stars/syntaxixr/claude-obhod?label=stars&color=F5C452&style=flat-square" alt="stars"></a>
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+">
  <img src="https://img.shields.io/badge/license-MIT-6B655C?style=flat-square" alt="MIT">
</p>

<p align="center">
  <img src="docs/demo.gif" width="800" alt="How Claude Obhod works">
</p>

<p align="center">
  <a href="https://github.com/syntaxixr/claude-obhod/releases/latest"><b>⬇️ Download APK</b></a> ·
  <a href="docs/demo.mp4">🎬 Video (MP4)</a> ·
  <a href="#server-on-a-vds">🖥 Set up a server</a> ·
  <a href="https://t.me/synaps_ss">✈️ Telegram</a>
</p>

---

An Android app plus a small server that let you use Claude through your own server abroad (a VDS) or through a SOCKS5 proxy.

*Obhod* (Обход) is Russian for "workaround". The app speaks **English and Russian**: it picks your phone's language and you can switch any time with the 🌐 button.

Open the app and it checks your connection on five points. When everything is green you get the **"Open Claude"** button. Claude opens right inside the app, and the only connection that leaves the phone goes to your server: WebRTC, DNS and any direct requests are blocked, so Claude never sees the phone's real IP.

> [!CAUTION]
> **Don't use the official Claude app. That's what gets accounts banned.**
> The official Claude app sees the whole phone: **the SIM card (country and carrier), time zone, system language and region**. A proxy or VPN doesn't hide any of that, so it's obvious you're in Russia (China, North Korea…) and the account gets blocked.
> **Don't link a phone number from a blocked country to your account.** It's the same as writing down your country.
> Open Claude **only inside Claude Obhod**: it has no access to the SIM card, and the only IP the outside world sees is your server's.

> ⭐ **If this helped, give it a star.** It helps other people who need it find the project. Thanks!

## Who it's for

Claude isn't officially available in **Russia, China, North Korea**, Belarus, Iran and a number of other countries. If that's where you are, this is for you. All you need is a server in a country where Claude works (EU, US, UK) or a decent SOCKS5 proxy.

## Set it up your way

There's no shared server that everybody goes through. Everything is yours:

- **Your own server.** One command installs it on your VDS, and you get your own link with your own token.
- **Or your own proxy.** Any SOCKS5, in any common format.
- **Your own build.** Bake your link right into the APK and hand it to your family, so they don't have to paste anything ([how to build](#building-from-source)).
- **Your own tokens.** Want to let a friend in? Give them a separate token and switch it off whenever you like.
- **Open source.** Fork it, change it, translate it. MIT.

<p align="center">
  <img src="docs/screenshots/1-guide.png" width="200" alt="First-run guide">
  <img src="docs/screenshots/2-connect-vds.png" width="200" alt="Connecting: your own VDS">
  <img src="docs/screenshots/3-connect-socks5.png" width="200" alt="Connecting: SOCKS5">
  <img src="docs/screenshots/4-checks.png" width="200" alt="All checks passed">
</p>

---

## Features

- **Two ways to connect.** Your own VDS running the server from this repo (recommended), or any SOCKS5 proxy.
- **5 checks before you go in.** Internet, server, tunnel, exit country, Claude availability. If something is wrong, it won't let you in and tells you exactly what.
- **Claude inside the app.** Chats, files, voice input, downloads, all through your server.
- **Sign in with Google, Apple or email, also inside the app.** The Google window opens on top of Claude through the server, and the link from the sign-in email opens in the app instead of the phone's browser.
- **Network-level protection.** A system VPN that covers only this app (and the official Claude app): everything except the connection to your server is dropped.
- **First-run guide.** Three steps plus the rules for not getting banned.
- **Claude-style design.** Warm dark theme and a "breathing" Claude star while checks run.

## What's inside

| Folder | What it is |
|---|---|
| `server/` | Server for your VDS (Node.js). SOCKS5 and HTTPS proxy on one port, everything inside TLS, token auth |
| `android/` | The app |
| `docs/` | Screenshots and video |

```
 Phone                           Your server (EU/US/…)            claude.ai
┌──────────────────┐   TLS     ┌──────────────────┐   TCP 443  ┌──────────┐
│ Claude Obhod     │──────────▶│ server           │───────────▶│ Anthropic│
│  Claude inside   │  SOCKS5 + │  token check     │            └──────────┘
│  127.0.0.1:N     │   token   │  tunnel          │
└──────────────────┘           └──────────────────┘
```

TLS to claude.ai is end-to-end, straight from the phone. The server only sees an encrypted stream to `claude.ai:443` and can't read your chats.

## Two ways to connect

### A. Your own VDS (recommended)

You need a VDS in a country where Claude works (EU, US, UK, etc.). The phone-to-server connection is encrypted and looks like ordinary HTTPS from the outside, which makes it harder to block.

1. Install the server on the VDS (5 minutes, see below).
2. Copy the link it prints: `socks5://relay:TOKEN@address:port`.
3. In the app pick "Your own VDS" and paste the link.

### B. A regular SOCKS5 proxy

No VDS? A SOCKS5 proxy from any provider works. The app understands all the common formats:

```
socks5://login:password@host:port
login:password@host:port
host:port:login:password
host:port
```

In the app pick "Regular SOCKS5 proxy" and paste the address.

> Plain SOCKS5 isn't encrypted: your ISP can see which sites you connect to and may block the proxy. Free and shared proxies won't do, see "Don't get banned".

---

## Server on a VDS

You need Ubuntu/Debian, root, and a VDS that is **not** in Russia, Belarus, China, Hong Kong or Iran (Claude is closed there).

```bash
git clone https://github.com/syntaxixr/claude-obhod
cd claude-obhod/server

sudo bash install.sh 203.0.113.7              # by IP (self-signed certificate)
sudo bash install.sh 203.0.113.7 9443         # if 443 is taken (VPN, website), use another port
sudo bash install.sh relay.example.com        # have a domain → Let's Encrypt
```

The script installs Node.js 20 (if it's missing or older), creates a certificate, writes `config.json` with a random token, starts the `claude-relay` service and **prints the link for the app**.

```bash
cd /opt/claude-relay
node src/index.mjs link      # show the link again
node src/index.mjs doctor    # self-check: 8 items, should end with «ВСЁ ЗЕЛЁНОЕ» (all green)
journalctl -u claude-relay -n 50 --no-pager   # logs
```

Don't forget to open the port in the firewall: `ufw allow 9443/tcp` (and in your hosting panel, if it has its own firewall).

<details>
<summary>Something already runs on the VDS (VPN, website)</summary>

Install by IP on a free port: `install.sh IP 9443`. Installing by domain needs port 80 for certbot, so the script stops nginx/apache/caddy. On a server that needs them, install by IP.

Check that the port is free: `ss -ltnp | grep ':9443 '` should print nothing.
</details>

<details>
<summary>Updating the server</summary>

```bash
cd claude-obhod && git pull
sudo cp -r server/src /opt/claude-relay/ && sudo systemctl restart claude-relay
```

`config.json` and tokens stay the same. Older app versions keep working.
</details>

<details>
<summary>Several people on one server</summary>

`auth.tokens` in `/opt/claude-relay/config.json` is a list. Give everyone their own token (`openssl rand -hex 24`) so you can cut off one person without touching the rest. After editing run `systemctl restart claude-relay`; links: `node src/index.mjs link`.

But remember the "one server, one person" rule below: to Claude, everyone coming from the same IP looks connected.
</details>

---

## The app

Download the APK from [Releases](https://github.com/syntaxixr/claude-obhod/releases/latest), open it on the phone and tap "Install" (allow installing from unknown sources). Android 8 or newer.

**First run:**
1. The "How to use" guide: scroll through and tap **"Got it, let's go"**. The 🌐 button in the corner switches the language.
2. Pick "Your own VDS" or "Regular SOCKS5" and paste the link (there's a "Paste from clipboard" button).
3. If the server was installed by IP, turn on "Allow self-signed certificate".
4. Wait for five checkmarks, then tap **"Open Claude"**.
5. Android will ask once about notifications and VPN. Allow both. That's the protection described below.

| Check | What it checks |
|---|---|
| Internet on the phone | whether there's a network |
| Server | the server answers and accepts the token / login |
| Tunnel | the connection through the server works |
| Internet exit | the country the internet sees (not RU/BY/CN/…) |
| Claude available | claude.ai and the API open, no region block |

### Signing in

- **Google / Apple:** the sign-in window opens inside the app, on top of Claude, also through the server.
- **Email:** Claude sends you an email with a link. On Android 12+ you need to allow the app to open claude.ai links once: on your first sign-in the app shows a "Sign in by email" dialog → "Set up" → "Add link" → tick **claude.ai**. After that the button in the email opens Claude in the app, not in the browser.
  Works without the setup too: long-press the sign-in button in the email → "Copy link", then go back to the app and it opens the link by itself.

### Protection: only the server goes out

The "Open Claude" button first turns on **protection**, a system VPN that covers only Claude Obhod and the official Claude app. All their traffic except the connection to your server goes into the tunnel and is dropped there. Other apps on the phone aren't affected.

Tested on an emulator while recording all of the phone's traffic:

| Check | Without protection | With protection |
|---|---|---|
| WebRTC leak (browserleaks.com/webrtc) | real IP visible | only the server's IP, "No Leak" |
| DNS queries for Anthropic domains at the ISP | yes | none |
| Connections to Claude and Google sign-in bypassing the server | — | none |
| Signing in to claude.ai | works | works, Cloudflare lets you through |

Inside the page there's one more layer: WebRTC is stripped before the page loads, the WebView has no "direct" fallback, every link opens inside (through the server), downloads go through the server, geolocation is denied, and the language sent to sites is `en-US` (`ru-LV` in Russian mode).

**What the app doesn't hide.** Sites see the phone's time zone as it is: spoofing it was tried, but Cloudflare catches it and won't let you into Claude. The least noticeable option is Claude inside Claude Obhod.

> [!CAUTION]
> **The official Claude app** (the "Open in Claude app" link) runs under the same protection, but it reads **the SIM card (country and carrier), time zone, language and region of the phone** on its own. That's exactly what bans come from, and the server's IP doesn't help here. The app shows a warning dialog before opening it. Better not to install the official app on this phone at all.

**Android limitation:** only one VPN can run at a time. If another VPN client is on, it gets switched off while protection is running.

---

## Don't get banned

The app shows these rules on first run (and under the "Guide" button).

- ✓ **Always the same server.** One account, one IP. No hopping between VPNs and proxies.
- ✓ **A server in a country where Claude works:** EU, US, UK. If the "Internet exit" check is red, the server won't do.
- ✕ **Don't use this account outside the app**, neither on the phone nor on a computer directly.
- ✕ **Don't use the official Claude app.** It sees the SIM card, time zone and phone language, and that's what gets you banned. Only Claude inside Claude Obhod.
- ✕ **Don't link a phone number from a blocked country** to the account.
- ✕ **Don't share your link** and don't use free proxies: whatever other people do through your IP gets tied to your account. The link with the token is access to your server.

---

## Building from source

You need the Android SDK and JDK 17–21 (or Android Studio).

```bash
cd android
./gradlew assembleRelease

# personal build: the link is baked in, nobody has to paste anything
./gradlew assembleRelease -PrelayLink="socks5://relay:TOKEN@203.0.113.7:9443" -PrelaySelfSigned=true
```

For a personal build you can also set the token on the server in advance: `RELAY_TOKEN=TOKEN bash install.sh 203.0.113.7 9443`.

The signing key (`android/app/relay-release.jks`) is not in git. Without it you get an unsigned APK. To make your own key:

```bash
keytool -genkeypair -v -keystore app/relay-release.jks -alias relay -keyalg RSA -keysize 4096 -validity 10000
```

The key password comes from the `RELAY_KEYSTORE_PASS` environment variable or a `relayKeystorePass=…` line in `~/.gradle/gradle.properties` (never committed).

---

## If something is red

| What's red | What to check |
|---|---|
| Server: "not a Claude Obhod server (HTTP …)" | another program is on that port (often a VPN), or the server isn't installed |
| Server: "connection refused" / timeout | the server isn't running, or the firewall blocks the port |
| Server: "didn't answer as SOCKS5" | old version on the VDS, update the server |
| Server: certificate error | installed by IP, turn on "self-signed certificate" |
| Server: "token / login rejected" | the link was copied incompletely; run `node src/index.mjs link` on the VDS |
| Tunnel | the server has no internet: `curl -s https://api.country.is` on the VDS |
| Internet exit / Claude | the server is in an unsupported country, you need another one |
| Cloudflare "Verify you are human" | tick the box, that's normal |
| The email link opened in the browser | claude.ai links aren't allowed for the app, see "Signing in" |

---

## Honestly about the risks

Anthropic doesn't offer Claude in every country. Using it from an unsupported country through a VPN or proxy breaks their terms, and the account can be blocked. The app hides your IP but doesn't make that kind of use allowed: the decision and the responsibility are yours.

This project isn't affiliated with Anthropic, doesn't modify the official Claude app and doesn't give access to Claude without an account and subscription.

## Author

- Telegram: [@synaps_ss](https://t.me/synaps_ss)
- GitHub: [syntaxixr](https://github.com/syntaxixr)

Questions and bugs go to [Issues](https://github.com/syntaxixr/claude-obhod/issues) or Telegram. Pull requests welcome, including translations of the app.

## License

MIT, see [LICENSE](LICENSE).

---

<p align="center">
  <b>⭐ Star the repo. It's the best way to say thanks.</b><br>
  Thanks to everyone who uses it, reports bugs and shares it with friends.<br><br>
  <b>Enjoy! 🧡</b>
</p>
