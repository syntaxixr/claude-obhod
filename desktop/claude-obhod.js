// Claude Обход для компьютера: Claude Code (CLI) через свой сервер.
//
// Запустил — программа проверяет связь, поднимает локальный прокси и открывает
// Claude Code. Весь его трафик идёт только через твой сервер (VDS) или SOCKS5.
// Сервер тот же, что и у приложения для телефона: ссылка socks5://relay:ТОКЕН@адрес:порт.
//
//   ClaudeObhod.exe                 проверки + Claude Code
//   ClaudeObhod.exe -c              любые аргументы уходят в claude
//   ClaudeObhod.exe --setup         сменить ссылку
//   ClaudeObhod.exe --browser [url] браузер через сервер (вход в аккаунт, claude.ai)
//   ClaudeObhod.exe --shell         терминал, где всё идёт через сервер
//   ClaudeObhod.exe --install       ярлык на рабочем столе и профиль Windows Terminal
//   ClaudeObhod.exe --uninstall     убрать ярлык и профиль

"use strict";
const net = require("net");
const tls = require("tls");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const readline = require("readline");
const { spawn, spawnSync } = require("child_process");

const VERSION = "1.0.0";
const REPO = "https://github.com/syntaxixr/claude-obhod";
// assets.js делает build.ps1: значок и (для личной сборки) вшитая ссылка.
const ASSETS = (() => { try { return require("./assets.js"); } catch { return {}; } })();
const BAKED_LINK = ASSETS.link || "";

const IS_WIN = process.platform === "win32";
const HOME = os.homedir();
const DATA_DIR = path.join(process.env.APPDATA || path.join(HOME, ".config"), "ClaudeObhod");
const CONFIG = path.join(DATA_DIR, "config.json");
const LOG = path.join(DATA_DIR, "proxy.log");
const INSTALL_DIR = path.join(process.env.LOCALAPPDATA || DATA_DIR, "ClaudeObhod");
const EXE_NAME = "ClaudeObhod.exe";
const PROFILE_NAME = "Claude Обход";

// ---------- язык ----------
let EN = !/^(ru|uk|be|kk|ky|uz|tg)/i.test(safe(() => Intl.DateTimeFormat().resolvedOptions().locale) || "ru");
const tr = (ru, en) => (EN ? en : ru);

function safe(f, d) { try { return f(); } catch { return d; } }

// ---------- оформление ----------
const C = {
  accent: "\x1b[38;2;217;119;87m",
  deep: "\x1b[38;2;201;100;66m",
  text: "\x1b[38;2;250;249;245m",
  dim: "\x1b[38;2;166;163;154m",
  faint: "\x1b[38;2;117;115;108m",
  ok: "\x1b[38;2;143;191;127m",
  err: "\x1b[38;2;229;83;75m",
  warn: "\x1b[38;2;224;179;74m",
  bold: "\x1b[1m",
  reset: "\x1b[0m",
};
const out = (s = "") => process.stdout.write(s + "\n");
const strip = (s) => s.replace(/\x1b\[[0-9;]*m/g, "");
const pad = (s, n) => s + " ".repeat(Math.max(0, n - strip(s).length));

function banner() {
  const a = C.accent, t = C.text + C.bold, d = C.dim, f = C.faint, r = C.reset;
  const star = [
    "        │        ",
    "    ╲   │   ╱    ",
    "      ╲ │ ╱      ",
    "  ──────✻──────  ",
    "      ╱ │ ╲      ",
    "    ╱   │   ╲    ",
    "        │        ",
  ];
  const right = [
    "",
    "",
    `${t}Claude Обход${r}`,
    `${d}${tr("Claude Code через твой сервер", "Claude Code through your own server")}${r}`,
    `${f}v${VERSION} · ${REPO.replace("https://", "")}${r}`,
    "",
    "",
  ];
  out("");
  star.forEach((l, i) => out("  " + a + l + r + "  " + right[i]));
  out("");
}

function rule() { out("  " + C.faint + "─".repeat(60) + C.reset); }

// ---------- конфиг ----------
function loadConfig() {
  const c = safe(() => JSON.parse(fs.readFileSync(CONFIG, "utf8")), {}) || {};
  if (!c.link && BAKED_LINK) c.link = BAKED_LINK;
  if (c.lang === "en") EN = true;
  if (c.lang === "ru") EN = false;
  return c;
}
function saveConfig(c) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(CONFIG, JSON.stringify(c, null, 2));
}
function log(m) {
  safe(() => {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 512 * 1024) fs.renameSync(LOG, LOG + ".old");
    fs.appendFileSync(LOG, new Date().toISOString() + " " + m + "\n");
  });
}

// ---------- разбор ссылки ----------
// socks5://relay:ТОКЕН@адрес:порт   — свой сервер Claude Обход (TLS + SOCKS5, как в приложении)
// socks5://логин:пароль@хост:порт, логин:пароль@хост:порт, хост:порт:логин:пароль, хост:порт — обычный SOCKS5
// ssh://user@host[:port] или ssh:алиас — SSH-туннель (ssh -D), ключ должен быть настроен
function parseLink(raw) {
  let s = String(raw || "").trim().replace(/^["']|["']$/g, "");
  if (!s) throw new Error(tr("пустая ссылка", "empty link"));
  if (/^ssh:/i.test(s)) {
    const target = s.replace(/^ssh:(\/\/)?/i, "");
    if (!target) throw new Error(tr("после ssh: нужен адрес", "ssh: needs an address"));
    let host = target, sshPort = "";
    const m = /^(.*?):(\d+)$/.exec(target);
    if (m && !target.includes("]")) { host = m[1]; sshPort = m[2]; }
    return { mode: "ssh", sshTarget: host, sshPort, host: host.replace(/^.*@/, ""), port: Number(sshPort) || 22 };
  }
  if (/^https?:\/\//i.test(s)) throw new Error(tr("нужна ссылка socks5://, а не адрес сайта", "need a socks5:// link, not a website"));
  s = s.replace(/^socks5h?:\/\//i, "");
  let user = "", pass = "", hostport = s;
  const at = s.lastIndexOf("@");
  if (at >= 0) {
    const cred = s.slice(0, at);
    hostport = s.slice(at + 1);
    const i = cred.indexOf(":");
    user = i >= 0 ? cred.slice(0, i) : cred;
    pass = i >= 0 ? cred.slice(i + 1) : "";
  } else {
    const p = s.split(":");
    if (p.length === 4) { hostport = p[0] + ":" + p[1]; user = p[2]; pass = p[3]; }
  }
  hostport = hostport.replace(/\/.*$/, "");
  const m = /^\[?([^\]]+?)\]?:(\d+)$/.exec(hostport);
  if (!m) throw new Error(tr("не вижу адрес и порт (нужно адрес:порт)", "can't find host and port (need host:port)"));
  const host = m[1], port = Number(m[2]);
  if (!(port > 0 && port < 65536)) throw new Error(tr("порт вне диапазона", "port out of range"));
  user = decodeURIComponent(user); pass = decodeURIComponent(pass);
  if (user === "relay") {
    if (pass.length < 16) throw new Error(tr("токен в ссылке слишком короткий", "the token in the link is too short"));
    return { mode: "relay", host, port, token: pass };
  }
  return { mode: "socks", host, port, user, pass };
}

function describe(l) {
  if (l.mode === "relay") return `${l.host}:${l.port}`;
  if (l.mode === "ssh") return `ssh ${l.sshTarget}`;
  return `SOCKS5 ${l.host}:${l.port}`;
}

// ---------- чтение из сокета ----------
function reader(sock) {
  let buf = Buffer.alloc(0), err = null, ended = false;
  const waiters = [];
  const pump = () => {
    while (waiters.length) {
      const w = waiters[0];
      if (buf.length >= w.n) { waiters.shift(); const o = buf.subarray(0, w.n); buf = buf.subarray(w.n); w.res(o); }
      else if (err || ended) { waiters.shift(); w.rej(err || new Error(tr("сервер закрыл соединение", "the server closed the connection"))); }
      else break;
    }
  };
  const onData = (d) => { buf = Buffer.concat([buf, d]); pump(); };
  const onEnd = () => { ended = true; pump(); };
  const onErr = (e) => { err = e; pump(); };
  sock.on("data", onData); sock.on("end", onEnd); sock.on("close", onEnd); sock.on("error", onErr);
  return {
    read: (n) => new Promise((res, rej) => { waiters.push({ n, res, rej }); pump(); }),
    done() {
      sock.pause();
      sock.removeListener("data", onData); sock.removeListener("end", onEnd);
      sock.removeListener("close", onEnd); sock.removeListener("error", onErr);
      return buf;
    },
  };
}

function withTimeout(p, ms, msg) {
  let t;
  return Promise.race([p, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(msg)), ms); })]).finally(() => clearTimeout(t));
}

const SOCKS_ERR = {
  2: () => tr("сервер запретил адрес", "server blocked the address"),
  3: () => tr("у сервера нет сети", "server: network unreachable"),
  4: () => tr("сервер не нашёл хост", "server: host unreachable"),
  5: () => tr("сервер: соединение отклонено", "server: connection refused"),
  7: () => tr("сервер: команда не поддерживается", "server: command not supported"),
};

async function socks5(sock, host, port, user, pass, onlyAuth) {
  const rd = reader(sock);
  const methods = onlyAuth ? [2] : user || pass ? [2, 0] : [0];
  sock.write(Buffer.from([5, methods.length, ...methods]));
  const g = await rd.read(2);
  if (g[0] !== 5) throw new Error(onlyAuth ? tr("сервер ответил не по SOCKS5 — это не сервер Claude Обход или старая версия", "the server didn't answer as SOCKS5 — not a Claude Obhod server, or an old version") : tr("по этому адресу не SOCKS5-прокси", "this address is not a SOCKS5 proxy"));
  if (g[1] === 2) {
    const u = Buffer.from(user || "relay"), p = Buffer.from(pass || "");
    sock.write(Buffer.concat([Buffer.from([1, u.length]), u, Buffer.from([p.length]), p]));
    const a = await rd.read(2);
    if (a[1] !== 0) throw new Error(onlyAuth ? tr("сервер не принял токен — проверь ссылку", "the server rejected the token — check the link") : tr("прокси не принял логин/пароль", "the proxy rejected the login/password"));
  } else if (g[1] === 0xff) {
    throw new Error(tr("сервер не принял способ входа", "the server didn't accept the auth method"));
  }
  const h = Buffer.from(host.replace(/^\[|\]$/g, ""));
  sock.write(Buffer.concat([Buffer.from([5, 1, 0, 3, h.length]), h, Buffer.from([port >> 8, port & 255])]));
  const r = await rd.read(4);
  if (r[1] !== 0) throw new Error((SOCKS_ERR[r[1]] || (() => tr("ошибка SOCKS5 ", "SOCKS5 error ") + r[1]))());
  const len = r[3] === 1 ? 4 : r[3] === 4 ? 16 : (await rd.read(1))[0];
  await rd.read(len + 2);
  return rd.done();
}

// ---------- туннель до сервера ----------
let LINK = null;
let CFG = {};
let sshProc = null, sshPort = 0;

function tcp(host, port) {
  return new Promise((res, rej) => {
    const s = net.connect({ host, port });
    s.once("connect", () => { s.removeAllListeners("error"); res(s); });
    s.once("error", rej);
  });
}

function connectRelay() {
  return new Promise((res, rej) => {
    const byIp = net.isIP(LINK.host) !== 0;
    const s = tls.connect({
      host: LINK.host, port: LINK.port,
      servername: byIp ? undefined : LINK.host,
      // Сервер, поставленный по IP, живёт с самоподписанным сертификатом.
      // Тогда доверяем ему при первом подключении и запоминаем отпечаток (как SSH).
      rejectUnauthorized: !byIp,
    });
    s.once("secureConnect", () => {
      if (byIp) {
        const fp = safe(() => s.getPeerCertificate().fingerprint256, "");
        const key = `${LINK.host}:${LINK.port}`;
        CFG.pins = CFG.pins || {};
        if (!CFG.pins[key] && fp) { CFG.pins[key] = fp; saveConfig(CFG); }
        else if (fp && CFG.pins[key] !== fp) {
          s.destroy();
          return rej(new Error(tr("сертификат сервера изменился — если ты переставлял сервер, запусти --setup", "the server certificate changed — if you reinstalled the server, run --setup")));
        }
      }
      s.removeAllListeners("error");
      res(s);
    });
    s.once("error", rej);
  });
}

function startSsh() {
  if (sshProc && !sshProc.killed && sshPort) return Promise.resolve();
  return new Promise((res, rej) => {
    const srv = net.createServer();
    srv.listen(0, "127.0.0.1", () => {
      sshPort = srv.address().port;
      srv.close(() => {
        const args = ["-N", "-D", "127.0.0.1:" + sshPort, "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes",
          "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-o", "ConnectTimeout=15"];
        if (LINK.sshPort) args.push("-p", LINK.sshPort);
        args.push(LINK.sshTarget);
        const exe = IS_WIN && fs.existsSync(path.join(process.env.WINDIR || "C:\\Windows", "System32", "OpenSSH", "ssh.exe"))
          ? path.join(process.env.WINDIR || "C:\\Windows", "System32", "OpenSSH", "ssh.exe") : "ssh";
        sshProc = spawn(exe, args, { stdio: ["ignore", "ignore", "pipe"], windowsHide: true });
        let errText = "";
        sshProc.stderr.on("data", (d) => { errText += d; log("ssh: " + String(d).trim()); });
        sshProc.on("exit", (code) => { log("ssh exit " + code); sshProc = null; });
        sshProc.on("error", (e) => rej(new Error("ssh: " + e.message)));
        const t0 = Date.now();
        const poll = () => {
          if (!sshProc) return rej(new Error(errText.trim().split("\n").pop() || tr("ssh не подключился", "ssh failed to connect")));
          tcp("127.0.0.1", sshPort).then((s) => { s.destroy(); res(); }, () => {
            if (Date.now() - t0 > 20000) rej(new Error(tr("ssh не поднял туннель за 20 с", "ssh didn't open the tunnel in 20 s")));
            else setTimeout(poll, 300);
          });
        };
        setTimeout(poll, 400);
      });
    });
    srv.on("error", rej);
  });
}

// Соединение до host:port через сервер. Возвращает [сокет, остаток данных].
async function openTunnel(host, port) {
  const msg = tr("сервер не ответил за 20 с", "the server didn't answer in 20 s");
  if (LINK.mode === "relay") {
    const s = await withTimeout(connectRelay(), 20000, msg);
    try { return [s, await withTimeout(socks5(s, host, port, "relay", LINK.token, true), 20000, msg)]; }
    catch (e) { s.destroy(); throw e; }
  }
  if (LINK.mode === "ssh") {
    await startSsh();
    const s = await tcp("127.0.0.1", sshPort);
    try { return [s, await withTimeout(socks5(s, host, port), 20000, msg)]; }
    catch (e) { s.destroy(); throw e; }
  }
  const s = await withTimeout(tcp(LINK.host, LINK.port), 20000, msg);
  try { return [s, await withTimeout(socks5(s, host, port, LINK.user, LINK.pass), 20000, msg)]; }
  catch (e) { s.destroy(); throw e; }
}

// ---------- локальный HTTP-прокси ----------
// Claude Code, npm, git, curl понимают HTTPS_PROXY. Всё, что сюда пришло, уходит только
// через сервер; если сервер недоступен — ошибка, «напрямую» не бывает.
function startProxy() {
  return new Promise((res, rej) => {
    const server = http.createServer((req, resp) => {
      let u;
      try { u = new URL(req.url); } catch { resp.writeHead(400); return resp.end("bad proxy request"); }
      if (u.protocol !== "http:") { resp.writeHead(400); return resp.end("https goes via CONNECT"); }
      const host = u.hostname.replace(/^\[|\]$/g, "");
      const port = Number(u.port) || 80;
      openTunnel(host, port).then(([s, rest]) => {
        if (rest && rest.length) s.unshift(rest);
        s.resume();
        const headers = {};
        for (let i = 0; i < req.rawHeaders.length; i += 2) {
          if (!/^(proxy-|connection$|keep-alive$)/i.test(req.rawHeaders[i])) headers[req.rawHeaders[i]] = req.rawHeaders[i + 1];
        }
        headers.Connection = "close";
        const r = http.request({ method: req.method, path: u.pathname + u.search, headers, createConnection: () => s }, (ures) => {
          resp.writeHead(ures.statusCode, ures.rawHeaders);
          ures.pipe(resp);
        });
        r.on("error", (e) => { if (!resp.headersSent) resp.writeHead(502); resp.end("Claude Obhod: " + e.message); });
        req.pipe(r);
      }, (e) => {
        log("http " + host + ":" + port + " ERR " + e.message);
        if (!resp.headersSent) resp.writeHead(502);
        resp.end("Claude Obhod: " + e.message);
      });
    });
    server.on("connect", (req, client, head) => {
      const m = /^\[?([^\]]+?)\]?:(\d+)$/.exec(req.url);
      if (!m) return client.end("HTTP/1.1 400 Bad Request\r\n\r\n");
      client.on("error", () => {});
      openTunnel(m[1], Number(m[2])).then(([s, rest]) => {
        if (client.destroyed) return s.destroy();
        if (process.env.OBHOD_DEBUG) log("CONNECT " + req.url + " ok");
        s.setNoDelay?.(true); client.setNoDelay?.(true);
        client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
        if (rest && rest.length) client.write(rest);
        if (head && head.length) s.write(head);
        s.pipe(client); client.pipe(s);
        s.resume();
        s.on("error", () => client.destroy());
        client.on("error", () => s.destroy());
        s.on("close", () => client.destroy());
        client.on("close", () => s.destroy());
      }, (e) => {
        log("CONNECT " + req.url + " ERR " + e.message);
        client.end("HTTP/1.1 502 Bad Gateway\r\n\r\n");
      });
    });
    server.on("clientError", (_e, sock) => safe(() => sock.end("HTTP/1.1 400 Bad Request\r\n\r\n")));
    server.on("error", rej);
    server.listen(0, "127.0.0.1", () => res(server));
  });
}

// ---------- HTTPS-запрос через туннель (для проверок) ----------
async function httpsVia(host, pathName, method = "GET") {
  const [raw, rest] = await openTunnel(host, 443);
  if (rest && rest.length) raw.unshift(rest);
  return withTimeout(new Promise((res, rej) => {
    const s = tls.connect({ socket: raw, servername: host }, () => {
      s.write(`${method} ${pathName} HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: claude-obhod/${VERSION}\r\nAccept: */*\r\nConnection: close\r\n\r\n`);
    });
    raw.resume();
    let buf = Buffer.alloc(0);
    s.on("data", (d) => { buf = Buffer.concat([buf, d]); if (buf.length > 65536) s.destroy(); });
    const finish = () => {
      const txt = buf.toString("utf8");
      const m = /^HTTP\/[\d.]+ (\d{3})/.exec(txt);
      if (!m) return rej(new Error(tr("пустой ответ", "empty response")));
      const body = txt.slice(txt.indexOf("\r\n\r\n") + 4);
      res({ code: Number(m[1]), body });
    };
    s.on("end", finish); s.on("close", finish);
    s.on("error", rej);
  }), 20000, tr("нет ответа за 20 с", "no answer in 20 s"));
}

// ---------- проверки ----------
const BLOCKED = new Set(["RU", "BY", "IR", "KP", "CU", "SY", "VE", "CN", "HK", "MO", "AF"]);
const COUNTRY_RU = { LV: "Латвия", LT: "Литва", EE: "Эстония", DE: "Германия", NL: "Нидерланды", FI: "Финляндия", PL: "Польша", FR: "Франция", GB: "Великобритания", US: "США", SE: "Швеция", CZ: "Чехия", KZ: "Казахстан", TR: "Турция", AM: "Армения", GE: "Грузия", RU: "Россия", CN: "Китай", BY: "Беларусь", HK: "Гонконг" };
const countryName = (c) => (EN ? safe(() => new Intl.DisplayNames(["en"], { type: "region" }).of(c), c) : COUNTRY_RU[c] || c);

const SPIN = ["✻", "✳", "✢", "·", "✢", "✳"];

async function step(title, fn) {
  const t0 = Date.now();
  let i = 0;
  const draw = (mark, detail, ms) => {
    const line = "  " + mark + "  " + pad(C.text + title + C.reset, 22) + " " + pad(detail, 40) + (ms != null ? C.faint + String(ms).padStart(5) + " ms" + C.reset : "");
    process.stdout.write("\r\x1b[2K" + line);
  };
  const timer = setInterval(() => draw(C.accent + SPIN[i++ % SPIN.length] + C.reset, C.faint + tr("проверяю…", "checking…") + C.reset), 110);
  draw(C.accent + SPIN[0] + C.reset, C.faint + tr("проверяю…", "checking…") + C.reset);
  try {
    const detail = await fn();
    clearInterval(timer);
    draw(C.ok + "✓" + C.reset, C.dim + detail + C.reset, Date.now() - t0);
    process.stdout.write("\n");
    return true;
  } catch (e) {
    clearInterval(timer);
    draw(C.err + "✕" + C.reset, C.err + (e.message || String(e)) + C.reset, Date.now() - t0);
    process.stdout.write("\n");
    return false;
  }
}

function skip(title, why) {
  out("  " + C.faint + "·" + C.reset + "  " + pad(C.faint + title + C.reset, 22) + " " + C.faint + why + C.reset);
}

async function runChecks() {
  const titles = [
    tr("Интернет", "Internet"),
    tr("Сервер", "Server"),
    tr("Туннель", "Tunnel"),
    tr("Выход в интернет", "Internet exit"),
    tr("Claude доступен", "Claude available"),
  ];
  const ok1 = await step(titles[0], async () => {
    const tryOne = (h) => withTimeout(tcp(h, 443), 6000, "timeout").then((s) => { s.destroy(); return true; });
    await Promise.any([tryOne("1.1.1.1"), tryOne("8.8.8.8"), tryOne("77.88.8.8")]).catch(() => {
      throw new Error(tr("нет сети — проверь Wi-Fi или кабель", "no network — check Wi-Fi or cable"));
    });
    return tr("есть", "connected");
  });
  if (!ok1) { titles.slice(1).forEach((t) => skip(t, tr("ждёт интернет", "waiting for internet"))); return false; }

  const ok2 = await step(titles[1], async () => {
    if (LINK.mode === "ssh") { await startSsh(); return `${LINK.sshTarget} · ${tr("SSH на связи", "SSH connected")}`; }
    // Проверяем вход: просим сервер соединиться с ним же самим — важен только ответ на токен.
    const [s] = await openTunnel("api.country.is", 443).catch((e) => {
      if (/ECONNREFUSED/.test(e.message)) throw new Error(tr(`сервер не принимает соединения на порту ${LINK.port}`, `the server refuses connections on port ${LINK.port}`));
      if (/ETIMEDOUT|ENETUNREACH|EHOSTUNREACH|не ответил|didn't answer/.test(e.message)) throw new Error(tr("сервер не отвечает — не запущен или порт закрыт", "the server doesn't answer — not running or port closed"));
      if (/certificate|CERT|self.signed/i.test(e.message)) throw new Error(tr("ошибка сертификата: ", "certificate error: ") + e.message);
      throw e;
    });
    s.destroy();
    return `${describe(LINK)} · ${tr("на связи", "online")}`;
  });
  if (!ok2) { titles.slice(2).forEach((t) => skip(t, tr("ждёт сервер", "waiting for server"))); return false; }

  let exitInfo = null;
  const ok3 = await step(titles[2], async () => {
    const r = await httpsVia("api.country.is", "/");
    if (r.code !== 200) throw new Error("HTTP " + r.code);
    exitInfo = JSON.parse(r.body);
    return tr("трафик идёт через сервер", "traffic goes through the server");
  });
  if (!ok3) { titles.slice(3).forEach((t) => skip(t, tr("ждёт туннель", "waiting for tunnel"))); return false; }

  const ok4 = await step(titles[3], async () => {
    const c = String(exitInfo.country || "");
    if (!c) throw new Error(tr("страна не определилась", "country unknown"));
    if (BLOCKED.has(c)) throw new Error(`${countryName(c)} — ${tr("тут Claude закрыт, нужен сервер в другой стране", "Claude is blocked here, you need a server elsewhere")}`);
    return `${countryName(c)} · ${exitInfo.ip}`;
  });
  if (!ok4) { skip(titles[4], tr("нужен другой сервер", "need another server")); return false; }

  return step(titles[4], async () => {
    const r = await httpsVia("api.anthropic.com", "/v1/messages");
    if (r.code === 403) throw new Error(tr("доступ закрыт по региону (403)", "blocked by region (403)"));
    if (r.code >= 500) throw new Error("HTTP " + r.code);
    return tr("api.anthropic.com отвечает", "api.anthropic.com answers");
  });
}

// ---------- ввод ссылки ----------
function ask(q) {
  return new Promise((res) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    rl.question(q, (a) => { rl.close(); res(a); });
  });
}

async function setup(reason) {
  out("");
  if (reason) out("  " + C.warn + reason + C.reset);
  out("  " + C.text + C.bold + tr("Подключение", "Connection") + C.reset);
  out("  " + C.dim + tr("Вставь ссылку от своего сервера Claude Обход — её печатает install.sh:", "Paste the link from your Claude Obhod server — install.sh prints it:") + C.reset);
  out("  " + C.faint + "  socks5://relay:TOKEN@203.0.113.7:9443" + C.reset);
  out("  " + C.dim + tr("Или адрес обычного SOCKS5, или ssh://user@host для SSH-туннеля.", "Or a regular SOCKS5 address, or ssh://user@host for an SSH tunnel.") + C.reset);
  out("  " + C.faint + tr("Сервер ставится за 5 минут: ", "The server takes 5 minutes to set up: ") + REPO + "#" + tr("сервер-на-vds", "server-on-a-vds") + C.reset);
  out("");
  for (;;) {
    const a = (await ask("  " + C.accent + "› " + C.reset)).trim();
    if (!a) { out("  " + C.faint + tr("Пусто. Закрыть — Ctrl+C.", "Empty. Ctrl+C to quit.") + C.reset); continue; }
    try {
      const l = parseLink(a);
      CFG.link = a;
      if (CFG.pins) delete CFG.pins[`${l.host}:${l.port}`];
      saveConfig(CFG);
      out("  " + C.ok + "✓ " + C.reset + C.dim + tr("сохранено: ", "saved: ") + describe(l) + C.reset);
      return l;
    } catch (e) {
      out("  " + C.err + "✕ " + e.message + C.reset);
    }
  }
}

// ---------- поиск Claude Code ----------
function findClaude() {
  const cands = [];
  const where = IS_WIN ? spawnSync("where.exe", ["claude"], { encoding: "utf8" }) : spawnSync("which", ["-a", "claude"], { encoding: "utf8" });
  const lines = (where.stdout || "").split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  for (const p of lines) {
    if (/\.exe$/i.test(p)) cands.push({ exe: p });
    else if (/\.cmd$/i.test(p)) {
      // npm-обёртка: внутри лежит родной claude.exe — запускаем его напрямую
      const inner = path.join(path.dirname(p), "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe");
      if (fs.existsSync(inner)) cands.push({ exe: inner });
      else cands.push({ exe: p, shell: true });
    } else if (!IS_WIN) cands.push({ exe: p });
  }
  const local = path.join(HOME, ".local", "bin", IS_WIN ? "claude.exe" : "claude");
  if (fs.existsSync(local)) cands.unshift({ exe: local });
  return cands[0] || null;
}

// ---------- браузер через сервер ----------
function findBrowser() {
  const pf = process.env["ProgramFiles"] || "C:\\Program Files";
  const pf86 = process.env["ProgramFiles(x86)"] || "C:\\Program Files (x86)";
  const la = process.env.LOCALAPPDATA || "";
  const list = [
    path.join(pf86, "Microsoft", "Edge", "Application", "msedge.exe"),
    path.join(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
    path.join(pf, "Google", "Chrome", "Application", "chrome.exe"),
    path.join(pf86, "Google", "Chrome", "Application", "chrome.exe"),
    path.join(la, "Google", "Chrome", "Application", "chrome.exe"),
    path.join(pf, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
  ];
  return list.find((p) => p && fs.existsSync(p)) || null;
}

function openBrowser(port, url) {
  const b = findBrowser();
  if (!b) return false;
  const profile = path.join(DATA_DIR, "browser");
  fs.mkdirSync(profile, { recursive: true });
  const args = [
    `--proxy-server=http://127.0.0.1:${port}`,
    "--proxy-bypass-list=<-loopback>",
    `--user-data-dir=${profile}`,
    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
    "--webrtc-ip-handling-policy=disable_non_proxied_udp",
    "--no-first-run", "--no-default-browser-check",
    EN ? "--lang=en-US" : "--lang=ru",
    url || "https://claude.ai",
  ];
  const p = spawn(b, args, { detached: true, stdio: "ignore" });
  p.unref();
  return true;
}

// ---------- установка: ярлык и профиль Windows Terminal ----------
function psRun(script) {
  const enc = Buffer.from(script, "utf16le").toString("base64");
  return spawnSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc], { encoding: "utf8" });
}
const psq = (s) => "'" + String(s).replace(/'/g, "''") + "'";

function wtExe() {
  const p = path.join(process.env.LOCALAPPDATA || "", "Microsoft", "WindowsApps", "wt.exe");
  return fs.existsSync(p) ? p : null;
}

// ---------- Windows Terminal ----------
const WT_GUID = "{c1a0de0b-0b0d-4c1a-9d3e-0b40d0000001}";
const WT_SCHEME = {
  name: "Claude Obhod",
  background: "#262624", foreground: "#FAF9F5", cursorColor: "#D97757", selectionBackground: "#5A4034",
  black: "#1F1E1D", red: "#E5534B", green: "#8FBF7F", yellow: "#E0B34A", blue: "#7AA2D6", purple: "#C792EA", cyan: "#6CC5C0", white: "#D6D3CA",
  brightBlack: "#75736C", brightRed: "#FF7A70", brightGreen: "#A6D196", brightYellow: "#F0C860", brightBlue: "#93B7E6", brightPurple: "#D7A9F0", brightCyan: "#86DAD4", brightWhite: "#FAF9F5",
};
function wtProfile(exe, icon) {
  return {
    guid: WT_GUID,
    name: PROFILE_NAME,
    commandline: `"${exe}"`,
    icon,
    colorScheme: WT_SCHEME.name,
    tabColor: "#C96442",
    tabTitle: PROFILE_NAME,
    suppressApplicationTitle: true,
    startingDirectory: "%USERPROFILE%",
    cursorShape: "bar",
    font: { face: "Cascadia Mono", size: 11 },
    padding: "14, 10",
    backgroundImage: null, // не наследуем картинку из «defaults» — чистый фон Claude
    useAcrylic: false,
    opacity: 100,
    hidden: false,
  };
}
function wtSettingsFiles() {
  const la = process.env.LOCALAPPDATA || "";
  const list = [
    path.join(la, "Packages", "Microsoft.WindowsTerminal_8wekyb3d8bbwe", "LocalState", "settings.json"),
    path.join(la, "Packages", "Microsoft.WindowsTerminalPreview_8wekyb3d8bbwe", "LocalState", "settings.json"),
    path.join(la, "Microsoft", "Windows Terminal", "settings.json"),
  ];
  return list.filter((f) => fs.existsSync(f));
}
// settings.json бывает с комментариями — вырезаем их, не трогая строки
function parseJsonc(s) {
  try { return JSON.parse(s); } catch {}
  let o = "", i = 0, str = false;
  while (i < s.length) {
    const ch = s[i], nx = s[i + 1];
    if (str) { o += ch; if (ch === "\\") { o += nx; i += 2; continue; } if (ch === '"') str = false; i++; continue; }
    if (ch === '"') { str = true; o += ch; i++; continue; }
    if (ch === "/" && nx === "/") { while (i < s.length && s[i] !== "\n") i++; continue; }
    if (ch === "/" && nx === "*") { i += 2; while (i < s.length && !(s[i] === "*" && s[i + 1] === "/")) i++; i += 2; continue; }
    o += ch; i++;
  }
  return JSON.parse(o.replace(/,(\s*[}\]])/g, "$1"));
}
function wtUpsert(file, profile) {
  return safe(() => {
    const raw = fs.readFileSync(file, "utf8");
    const j = parseJsonc(raw.replace(/^﻿/, ""));
    const bak = file + ".bak-claude-obhod";
    if (!fs.existsSync(bak)) fs.writeFileSync(bak, raw);
    if (Array.isArray(j.profiles)) j.profiles = { list: j.profiles };
    j.profiles = j.profiles || {};
    j.profiles.list = (j.profiles.list || []).filter((p) => p.guid !== WT_GUID);
    if (profile) j.profiles.list.push(profile);
    j.schemes = (j.schemes || []).filter((s) => s.name !== WT_SCHEME.name);
    if (profile) j.schemes.push(WT_SCHEME);
    fs.writeFileSync(file, JSON.stringify(j, null, 4));
    return true;
  }, false);
}

const ICON_PNG_B64 = ASSETS.png || "";
const ICON_ICO_B64 = ASSETS.ico || "";

function install(quiet) {
  if (!IS_WIN) { out(tr("Установка ярлыков — только для Windows.", "Shortcut install is Windows-only.")); return; }
  fs.mkdirSync(INSTALL_DIR, { recursive: true });
  const target = path.join(INSTALL_DIR, EXE_NAME);
  const self = process.execPath;
  if (path.resolve(self).toLowerCase() !== path.resolve(target).toLowerCase() && /ClaudeObhod.*\.exe$/i.test(self)) {
    fs.copyFileSync(self, target);
  }
  const exe = fs.existsSync(target) ? target : self;
  const icoPath = path.join(INSTALL_DIR, "claude-obhod.ico");
  const pngPath = path.join(INSTALL_DIR, "claude-obhod.png");
  if (ICON_ICO_B64) fs.writeFileSync(icoPath, Buffer.from(ICON_ICO_B64, "base64"));
  if (ICON_PNG_B64) fs.writeFileSync(pngPath, Buffer.from(ICON_PNG_B64, "base64"));

  // профиль Windows Terminal: тёплая тёмная тема Claude, своя иконка
  const wt = wtExe();
  const profile = wtProfile(exe, fs.existsSync(pngPath) ? pngPath : exe);
  let wtDone = false;
  if (wt) {
    // Пишем прямо в settings.json: Терминал подхватывает его на лету, даже если уже открыт.
    for (const f of wtSettingsFiles()) wtDone = wtUpsert(f, profile) || wtDone;
    const fragDir = path.join(process.env.LOCALAPPDATA, "Microsoft", "Windows Terminal", "Fragments", "ClaudeObhod");
    if (wtDone) safe(() => fs.rmSync(fragDir, { recursive: true, force: true }));
    else {
      // запасной путь: фрагмент (подхватится при следующем запуске Терминала)
      fs.mkdirSync(fragDir, { recursive: true });
      fs.writeFileSync(path.join(fragDir, "claude-obhod.json"), JSON.stringify({ profiles: [profile], schemes: [WT_SCHEME] }, null, 2));
    }
  }

  const lnkTarget = wt || exe;
  const lnkArgs = wt ? `--size 128,38 -p "${wtDone ? WT_GUID : PROFILE_NAME}"` : "";
  const icon = fs.existsSync(icoPath) ? icoPath : exe;
  const r = psRun(`
$ErrorActionPreference = 'Stop'
$sh = New-Object -ComObject WScript.Shell
foreach ($dir in @([Environment]::GetFolderPath('Desktop'), (Join-Path ([Environment]::GetFolderPath('Programs')) ''))) {
  $l = $sh.CreateShortcut((Join-Path $dir ${psq(PROFILE_NAME + ".lnk")}))
  $l.TargetPath = ${psq(lnkTarget)}
  $l.Arguments = ${psq(lnkArgs)}
  $l.WorkingDirectory = $env:USERPROFILE
  $l.IconLocation = ${psq(icon + ",0")}
  $l.Description = ${psq(tr("Claude Code через твой сервер", "Claude Code through your own server"))}
  $l.Save()
}
'ok'`);
  if (!quiet) {
    if (/ok/.test(r.stdout || "")) {
      out("  " + C.ok + "✓ " + C.reset + C.text + tr("Ярлык «Claude Обход» — на рабочем столе и в меню «Пуск».", "Shortcut \"Claude Обход\" is on the desktop and in the Start menu.") + C.reset);
      if (wt) out("  " + C.ok + "✓ " + C.reset + C.text + tr("Профиль в Windows Terminal: тема и значок Claude.", "Windows Terminal profile with the Claude theme and icon.") + C.reset);
      out("  " + C.faint + tr("Программа лежит в ", "Installed to ") + INSTALL_DIR + C.reset);
    } else {
      out("  " + C.err + tr("Не удалось создать ярлык: ", "Couldn't create the shortcut: ") + (r.stderr || r.stdout || "").trim().split("\n").pop() + C.reset);
    }
  }
}

function uninstall() {
  if (!IS_WIN) return;
  psRun(`
$sh = New-Object -ComObject WScript.Shell
foreach ($dir in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
  Remove-Item -LiteralPath (Join-Path $dir ${psq(PROFILE_NAME + ".lnk")}) -ErrorAction SilentlyContinue
}`);
  safe(() => fs.rmSync(path.join(process.env.LOCALAPPDATA, "Microsoft", "Windows Terminal", "Fragments", "ClaudeObhod"), { recursive: true, force: true }));
  for (const f of wtSettingsFiles()) wtUpsert(f, null);
  CFG.installed = false;
  saveConfig(CFG);
  out("  " + C.ok + "✓ " + C.reset + tr("Ярлык и профиль убраны. Настройки: ", "Shortcut and profile removed. Settings: ") + DATA_DIR);
  out("  " + C.faint + tr("Сама программа: ", "The program itself: ") + INSTALL_DIR + C.reset);
}

// ---------- запуск ----------
function proxyEnv(port) {
  const p = `http://127.0.0.1:${port}`;
  const env = {
    ...process.env,
    HTTPS_PROXY: p, HTTP_PROXY: p, https_proxy: p, http_proxy: p,
    NO_PROXY: "localhost,127.0.0.1,::1", no_proxy: "localhost,127.0.0.1,::1",
    NODE_USE_ENV_PROXY: "1",
    CLAUDE_OBHOD_PORT: String(port),
    CLAUDE_OBHOD: "1",
  };
  // Ссылку входа Claude Code откроет не в обычном браузере (он пошёл бы напрямую),
  // а через нас: Claude Code запускает BROWSER как программу и отдаёт ей адрес.
  if (/ClaudeObhod.*\.exe$/i.test(process.execPath)) env.BROWSER = process.execPath;
  return env;
}

function waitKey(msg) {
  return new Promise((res) => {
    if (!process.stdin.isTTY) return res();
    out("");
    process.stdout.write("  " + C.faint + msg + C.reset);
    process.stdin.setRawMode?.(true);
    process.stdin.resume();
    process.stdin.once("data", () => { process.stdin.setRawMode?.(false); process.stdin.pause(); out(""); res(); });
  });
}

function shutdown(code, server) {
  safe(() => server && server.close());
  safe(() => sshProc && sshProc.kill());
  process.exit(code);
}

async function main() {
  const argv = process.argv.slice(2);
  if (IS_WIN) process.title = PROFILE_NAME;

  // вызов от Claude Code вместо браузера: открыть ссылку входа в защищённом браузере
  const asBrowser = process.env.CLAUDE_OBHOD_PORT && argv.length === 1 && /^https?:\/\//i.test(argv[0]);
  if (argv[0] === "--open-url" || asBrowser) {
    const port = Number(process.env.CLAUDE_OBHOD_PORT);
    const url = (asBrowser ? argv : argv.slice(1)).join(" ").replace(/^"|"$/g, "");
    if (port && openBrowser(port, url)) process.exit(0);
    process.stderr.write(tr("Открой эту ссылку в браузере через сервер: ", "Open this link in a browser that goes through the server: ") + url + "\n");
    process.exit(1);
  }
  if (argv[0] === "--version" || argv[0] === "-v") { out("Claude Obhod " + VERSION); return; }

  CFG = loadConfig();
  if (argv[0] === "--lang" && /^(ru|en)$/.test(argv[1] || "")) { CFG.lang = argv[1]; saveConfig(CFG); EN = argv[1] === "en"; out("ok"); return; }
  if (argv[0] === "--help" || argv[0] === "-h") {
    banner();
    out("  ClaudeObhod.exe                 " + tr("проверки и Claude Code через сервер", "checks, then Claude Code through the server"));
    out("  ClaudeObhod.exe <аргументы>     " + tr("аргументы уходят в claude (например -c)", "arguments go to claude (e.g. -c)"));
    out("  ClaudeObhod.exe --setup         " + tr("сменить ссылку", "change the link"));
    out("  ClaudeObhod.exe --browser       " + tr("браузер через сервер (claude.ai, вход)", "browser through the server (claude.ai, sign-in)"));
    out("  ClaudeObhod.exe --shell         " + tr("терминал, где всё идёт через сервер", "a terminal where everything goes through the server"));
    out("  ClaudeObhod.exe --check         " + tr("только проверить связь", "only check the connection"));
    out("  ClaudeObhod.exe --proxy         " + tr("держать прокси для других программ", "keep a proxy running for other programs"));
    out("  ClaudeObhod.exe --install       " + tr("ярлык на рабочем столе и профиль Terminal", "desktop shortcut and Terminal profile"));
    out("  ClaudeObhod.exe --uninstall     " + tr("убрать ярлык и профиль", "remove the shortcut and profile"));
    out("  ClaudeObhod.exe --lang ru|en    " + tr("язык", "language"));
    return;
  }
  if (argv[0] === "--uninstall") { banner(); uninstall(); return; }
  if (argv[0] === "--install") { banner(); install(false); await waitKey(tr("Готово. Нажми любую клавишу…", "Done. Press any key…")); return; }

  banner();

  // первый запуск .exe из «Загрузок» — ставим себя и делаем ярлык
  const service = /^--(check|proxy|browser|shell)$/.test(argv[0] || "");
  if (IS_WIN && !service && /ClaudeObhod.*\.exe$/i.test(process.execPath) && !CFG.installed) {
    install(false);
    CFG.installed = true;
    saveConfig(CFG);
    out("");
  }

  const forceSetup = argv[0] === "--setup";
  if (forceSetup) argv.shift();
  try { LINK = CFG.link && !forceSetup ? parseLink(CFG.link) : null; } catch { LINK = null; }
  if (!LINK) LINK = await setup(CFG.link && !forceSetup ? tr("Сохранённая ссылка не читается — вставь заново.", "The saved link can't be read — paste it again.") : "");
  for (;;) {
    out("");
    out("  " + C.text + C.bold + tr("Проверяю подключение", "Checking the connection") + C.reset + "  " + C.faint + describe(LINK) + C.reset);
    rule();
    const ok = await runChecks();
    rule();
    if (ok) break;
    out("  " + C.err + C.bold + tr("Нет подключения.", "No connection.") + C.reset + " " + C.dim + tr("Claude Code без сервера не запускаю — иначе он пошёл бы напрямую.", "Not starting Claude Code without the server — it would go directly.") + C.reset);
    out("  " + C.faint + tr("Лог: ", "Log: ") + LOG + C.reset);
    const a = (await ask("  " + C.dim + tr("Enter — повторить, s — сменить ссылку, q — выйти: ", "Enter — retry, s — change link, q — quit: ") + C.reset)).trim().toLowerCase();
    safe(() => sshProc && sshProc.kill());
    sshProc = null; sshPort = 0;
    if (a === "q" || a === "й") shutdown(1);
    if (a === "s" || a === "ы") LINK = await setup("");
  }

  if (argv[0] === "--check") {
    out("  " + C.ok + C.bold + tr("Всё готово.", "All set.") + C.reset + " " + C.text + tr("Связь через сервер работает.", "The connection through the server works.") + C.reset);
    await waitKey(tr("Нажми любую клавишу…", "Press any key…"));
    return shutdown(0);
  }

  const server = await startProxy();
  const port = server.address().port;
  const env = proxyEnv(port);

  if (argv[0] === "--proxy") {
    out("  " + C.ok + "✓ " + C.reset + C.text + tr("Прокси для других программ: ", "Proxy for other programs: ") + C.accent + `http://127.0.0.1:${port}` + C.reset);
    out("  " + C.faint + tr("Работает, пока открыто это окно. Ctrl+C — выключить.", "Works while this window is open. Ctrl+C to stop.") + C.reset);
    process.on("SIGINT", () => shutdown(0, server));
    return;
  }

  if (argv[0] === "--browser") {
    const url = argv[1] || "https://claude.ai";
    if (!openBrowser(port, url)) out("  " + C.err + tr("Не нашёл Edge или Chrome.", "Couldn't find Edge or Chrome.") + C.reset);
    else out("  " + C.ok + "✓ " + C.reset + C.text + tr("Браузер открыт — он ходит только через сервер. Не закрывай это окно, пока пользуешься им.", "Browser opened — it goes only through the server. Keep this window open while you use it.") + C.reset);
    await waitKey(tr("Закончил — нажми любую клавишу, чтобы выключить.", "Done — press any key to stop."));
    return shutdown(0, server);
  }

  if (argv[0] === "--shell") {
    out("  " + C.ok + "✓ " + C.reset + C.text + tr("Терминал через сервер: claude, npm, git, curl идут через VDS. Выход — exit.", "Terminal through the server: claude, npm, git, curl go via the VDS. Leave with exit.") + C.reset);
    out("");
    const sh = IS_WIN ? spawn("powershell.exe", ["-NoLogo"], { stdio: "inherit", env }) : spawn(process.env.SHELL || "bash", [], { stdio: "inherit", env });
    process.on("SIGINT", () => {});
    sh.on("exit", (c) => shutdown(c ?? 0, server));
    return;
  }

  const claude = findClaude();
  if (!claude) {
    out("  " + C.warn + tr("Claude Code не установлен.", "Claude Code isn't installed.") + C.reset);
    const npm = spawnSync(IS_WIN ? "where.exe" : "which", ["npm"], { encoding: "utf8" }).status === 0;
    const a = (await ask("  " + C.dim + tr("Установить через сервер сейчас? [Y/n] ", "Install it through the server now? [Y/n] ") + C.reset)).trim().toLowerCase();
    if (a === "n" || a === "н" || a === "т") return shutdown(1, server);
    let r;
    if (IS_WIN) {
      // официальный установщик Anthropic, скачивается через сервер
      r = spawnSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        `$p='http://127.0.0.1:${port}'; [Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy($p); irm -Proxy $p https://claude.ai/install.ps1 | iex`], { stdio: "inherit", env });
    } else {
      r = spawnSync("bash", ["-c", "curl -fsSL https://claude.ai/install.sh | bash"], { stdio: "inherit", env });
    }
    if ((!r || r.status !== 0) && npm) {
      r = spawnSync(IS_WIN ? "npm.cmd" : "npm", ["install", "-g", "@anthropic-ai/claude-code"], { stdio: "inherit", env, shell: IS_WIN });
    }
  }
  const cl = findClaude();
  if (!cl) {
    out("  " + C.err + tr("Не получилось найти claude. Поставь Claude Code и запусти снова: ", "Couldn't find claude. Install Claude Code and run again: ") + "https://docs.claude.com/claude-code" + C.reset);
    await waitKey(tr("Нажми любую клавишу…", "Press any key…"));
    return shutdown(1, server);
  }

  const loggedIn = fs.existsSync(path.join(HOME, ".claude", ".credentials.json")) || !!process.env.ANTHROPIC_API_KEY;
  out("  " + C.ok + C.bold + tr("Всё готово.", "All set.") + C.reset + " " + C.text + tr("Claude Code идёт только через сервер — настоящий IP не засветится.", "Claude Code goes only through the server — your real IP stays hidden.") + C.reset);
  if (!loggedIn) out("  " + C.dim + tr("Вход в аккаунт откроется в отдельном браузере, который тоже ходит через сервер.", "Sign-in opens in a separate browser that also goes through the server.") + C.reset);
  out("  " + C.faint + tr("Не пользуйся официальным приложением Claude и не заходи в аккаунт напрямую — за это банят.", "Don't use the official Claude app or sign in directly — that's what gets accounts banned.") + C.reset);
  out("");

  process.on("SIGINT", () => {}); // Ctrl+C — для Claude Code, а не для нас
  if (IS_WIN) process.on("SIGBREAK", () => {});
  const child = spawn(cl.exe, argv, { stdio: "inherit", env, shell: !!cl.shell, windowsHide: false });
  child.on("error", (e) => { out(C.err + e.message + C.reset); shutdown(1, server); });
  child.on("exit", (code) => shutdown(code ?? 0, server));
}

main().catch((e) => {
  out("\n  " + C.err + (e && e.message ? e.message : String(e)) + C.reset);
  log("fatal " + (e && e.stack));
  process.exitCode = 1;
});
