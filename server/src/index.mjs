// claude-relay — HTTPS CONNECT-туннель + API проверок.
// Приложение: меню с галочками → зелёные → вход в Claude через VDS.
import tls from "node:tls";
import net from "node:net";
import http from "node:http";
import dns from "node:dns";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import {
  loadConfig, log, tlsOptions, detectSelf, isPrivate, splitHostPort, eq,
  nodeTooOld, publicHostOf,
} from "./util.mjs";
import { ctx } from "./relayCtx.mjs";
import { runChecks, printChecks, CHECK_LIST } from "./checks.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STATIC = path.join(__dirname, "static");
const BUILD = JSON.parse(fs.readFileSync(path.join(STATIC, "build.json"), "utf8"));
const MENU = fs.readFileSync(path.join(STATIC, "menu.html"), "utf8");
const ICON = fs.readFileSync(path.join(STATIC, "icon.svg"));

// ================= авторизация =================
function tokenFrom(req) {
  const a = req.headers.authorization || "";
  const m = /^Bearer\s+(.+)$/i.exec(a);
  if (m) return m[1].trim();

  const pa = req.headers["proxy-authorization"] || "";
  const pm = /^Bearer\s+(.+)$/i.exec(pa);
  if (pm) return pm[1].trim();
  const p = /^Basic\s+(.+)$/i.exec(pa);
  if (p) {
    try {
      const [, pass] = Buffer.from(p[1], "base64").toString().split(":");
      return pass || "";
    } catch {}
  }
  const b = /^Basic\s+(.+)$/i.exec(a);
  if (b) {
    try {
      const [, pass] = Buffer.from(b[1], "base64").toString().split(":");
      return pass || "";
    } catch {}
  }
  const c = /(?:^|;\s*)relay_token=([^;]+)/.exec(req.headers.cookie || "");
  if (c) return decodeURIComponent(c[1]);
  return "";
}

function authorized(req, cfg) {
  const t = tokenFrom(req);
  return !!t && cfg.auth.tokens.some((x) => eq(x, t));
}

function json(res, status, obj, headers = {}) {
  const text = JSON.stringify(obj);
  res.writeHead(status, {
    "content-type": "application/json",
    "cache-control": "no-store",
    "content-length": Buffer.byteLength(text),
    ...headers,
  });
  res.end(text);
}

function reply(sock, status, body, headers = {}) {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  const h = {
    "content-type": typeof body === "string" ? "text/plain; charset=utf-8" : "application/json",
    "content-length": Buffer.byteLength(text),
    connection: "close",
    ...headers,
  };
  sock.write(`HTTP/1.1 ${status} ${http.STATUS_CODES[status] || "Error"}\r\n`);
  for (const [k, v] of Object.entries(h)) sock.write(`${k}: ${v}\r\n`);
  sock.write("\r\n" + text);
  sock.end();
}

// ================= состояние =================
let health = null;
async function healthNow(cfg, fresh) {
  if (!fresh && health && Date.now() - health.at < 20000) return health.data;
  const data = await runChecks(cfg);
  health = { at: Date.now(), data };
  return data;
}

const ipAttempts = new Map();
function tooMany(ip) {
  const now = Date.now();
  const r = ipAttempts.get(ip) || { n: 0, reset: now + 60000 };
  if (now > r.reset) Object.assign(r, { n: 0, reset: now + 60000 });
  r.n++;
  ipAttempts.set(ip, r);
  // 120 CONNECT в минуту: с запасом на загрузку страницы Claude (много хостов),
  // но достаточно, чтобы VDS не превратили в открытый прокси.
  return r.n > 120;
}

// ================= меню =================
function serveMenu(req, res, cfg) {
  const url = new URL(req.url, "http://x");
  if (req.method === "POST") {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const token = new URLSearchParams(body).get("token") || "";
      if (!cfg.auth.tokens.some((t) => eq(t, token))) {
        res.writeHead(303, { location: "/menu?error=1" });
        return res.end();
      }
      const go = new URLSearchParams(body).has("go");
      res.writeHead(303, {
        "set-cookie": `relay_token=${encodeURIComponent(token)}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=2592000`,
        location: go ? "/new" : "/menu",
      });
      res.end();
    });
    return;
  }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
  res.end(MENU.replaceAll("{{DOMAIN}}", cfg.publicHost || "").replaceAll("{{BUILD}}", String(BUILD.build)));
}

// ================= HTTP-прокси для ПК (curl -x https://...) =================
function proxyHttp(req, sock, cfg) {
  // абсолютный URL: http://example.com/path
  let u;
  try { u = new URL(req.url); } catch { return reply(sock, 400, "bad request"); }
  const headers = { ...req.headers };
  delete headers["proxy-authorization"];
  delete headers["proxy-connection"];
  headers.host = u.host;
  const r = http.request(
    { host: u.hostname, port: u.port || 80, method: req.method, path: u.pathname + u.search, headers },
    (res) => {
      let h = `HTTP/1.1 ${res.statusCode} ${res.statusMessage}\r\n`;
      for (let i = 0; i < res.rawHeaders.length; i += 2) h += `${res.rawHeaders[i]}: ${res.rawHeaders[i + 1]}\r\n`;
      sock.write(h + "\r\n");
      res.pipe(sock);
    },
  );
  r.on("error", (e) => reply(sock, 502, "upstream: " + e.code));
  req.pipe(r);
}

// ================= выход в интернет (общий для CONNECT и SOCKS5) =================
// DNS резолвим сами: так не пускаем имена, указывающие на локальную сеть VDS,
// и подключаемся ровно к тому IP, который проверили.
// cb(err, sock): err = { code: "dns" | "private" | "refused" | "timeout" | "error", msg }
function dialTarget(host, port, cb) {
  let finished = false;
  let sock = null;
  const finish = (err, s) => {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    if (err) { try { s?.destroy(); } catch {} }
    cb(err, err ? null : s);
  };
  const timer = setTimeout(() => finish({ code: "timeout", msg: "upstream timeout" }, sock), 15000);

  if (!host || !(port > 0 && port < 65536)) return finish({ code: "private", msg: "запрещённый адрес" });
  if (net.isIP(host) && isPrivate(host)) return finish({ code: "private", msg: "запрещённый адрес" });

  dns.lookup(host, { all: true }, (e, addrs) => {
    if (finished) return;
    if (e) return finish({ code: "dns", msg: "dns: " + (e.code || e.message) });
    if (!addrs?.length) return finish({ code: "dns", msg: "dns: пусто" });
    if (addrs.some((a) => isPrivate(a.address))) return finish({ code: "private", msg: "запрещённый адрес" });
    const pick = addrs.find((a) => a.family === 4) || addrs[0];
    sock = net.connect({ host: pick.address, port }, () => {
      if (finished) return sock.destroy();
      sock.setNoDelay(true);
      finish(null, sock);
    });
    sock.on("error", (err) =>
      finish({ code: err.code === "ECONNREFUSED" ? "refused" : "error", msg: "upstream недоступен" }, sock));
  });
}

// ================= HTTP CONNECT =================
function doConnect(req, clientSock, head, cfg) {
  const { host, port } = splitHostPort(req.url, 443);
  let target = null;
  let closed = false;
  const done = () => {
    if (closed) return;
    closed = true;
    try { target?.destroy(); } catch {}
    try { clientSock.destroy(); } catch {}
  };
  clientSock.on("error", done);
  clientSock.on("close", done);
  clientSock.setTimeout(0);

  dialTarget(host, port, (err, t) => {
    if (closed) return t?.destroy();
    if (err) {
      const status = err.code === "private" ? 403 : err.code === "timeout" ? 504 : 502;
      closed = true;
      return reply(clientSock, status, err.msg);
    }
    target = t;
    clientSock.setNoDelay(true);
    clientSock.write("HTTP/1.1 200 Connection Established\r\n\r\n");
    if (head?.length) t.write(head);
    t.on("close", done);
    t.pipe(clientSock);
    clientSock.pipe(t);
  });
}

// ================= SOCKS5 внутри TLS =================
// Тот же порт, что и HTTPS: после TLS первый байт 0x05 = SOCKS5, иначе HTTP.
// Ссылка для приложения: socks5://relay:ТОКЕН@домен — логин любой, пароль = токен.
// Только команда CONNECT; адреса проверяются так же, как у HTTP CONNECT.
const socksRep = (rep) => Buffer.from([0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
const SOCKS_ERR = { private: 0x02, dns: 0x04, refused: 0x05, timeout: 0x04, error: 0x01 };

function socksSession(sock, cfg) {
  const ip = sock.remoteAddress || "?";
  let buf = Buffer.alloc(0);
  let stage = "greet"; // greet → auth → request → connecting → piping | closed
  let target = null;

  const close = (answer) => {
    if (stage === "closed") return;
    stage = "closed";
    sock.removeListener("data", onData);
    if (answer) sock.end(answer); else sock.destroy();
    try { target?.destroy(); } catch {}
  };

  function advance() {
    if (stage === "greet") {
      if (buf.length < 2) return;
      if (buf[0] !== 0x05) return close(null);
      const n = buf[1];
      if (buf.length < 2 + n) return;
      const methods = buf.subarray(2, 2 + n);
      buf = buf.subarray(2 + n);
      // без логина/пароля не пускаем: иначе VDS станет открытым прокси
      if (!methods.includes(0x02)) return close(Buffer.from([0x05, 0xff]));
      sock.write(Buffer.from([0x05, 0x02]));
      stage = "auth";
    }
    if (stage === "auth") {
      if (buf.length < 2) return;
      if (buf[0] !== 0x01) return close(Buffer.from([0x01, 0x01]));
      const ul = buf[1];
      if (buf.length < 3 + ul) return;
      const pl = buf[2 + ul];
      if (buf.length < 3 + ul + pl) return;
      const pass = buf.subarray(3 + ul, 3 + ul + pl).toString("utf8");
      buf = buf.subarray(3 + ul + pl);
      if (!cfg.auth.tokens.some((t) => eq(t, pass))) return close(Buffer.from([0x01, 0x01]));
      sock.write(Buffer.from([0x01, 0x00]));
      stage = "request";
    }
    if (stage === "request") {
      if (buf.length < 5) return;
      if (buf[0] !== 0x05) return close(null);
      const cmd = buf[1], atyp = buf[3];
      let off = 4, host;
      if (atyp === 0x01) {
        if (buf.length < off + 4 + 2) return;
        host = Array.from(buf.subarray(off, off + 4)).join(".");
        off += 4;
      } else if (atyp === 0x03) {
        const l = buf[off];
        if (buf.length < off + 1 + l + 2) return;
        host = buf.subarray(off + 1, off + 1 + l).toString("utf8");
        off += 1 + l;
      } else if (atyp === 0x04) {
        if (buf.length < off + 16 + 2) return;
        const g = [];
        for (let i = 0; i < 16; i += 2) g.push(buf.readUInt16BE(off + i).toString(16));
        host = g.join(":");
        off += 16;
      } else {
        return close(socksRep(0x08)); // тип адреса не поддерживается
      }
      const port = buf.readUInt16BE(off);
      buf = buf.subarray(off + 2);
      if (cmd !== 0x01) return close(socksRep(0x07)); // только CONNECT
      if (tooMany(ip)) return close(socksRep(0x01));

      stage = "connecting";
      dialTarget(host, port, (err, t) => {
        if (stage === "closed") return t?.destroy();
        if (err) return close(socksRep(SOCKS_ERR[err.code] ?? 0x01));
        target = t;
        stage = "piping";
        sock.removeListener("data", onData);
        sock.write(socksRep(0x00));
        if (buf.length) t.write(buf); // то, что клиент успел прислать сразу за запросом
        buf = Buffer.alloc(0);
        t.on("error", () => close(null));
        t.on("close", () => close(null));
        t.pipe(sock);
        sock.pipe(t);
      });
    }
  }

  function onData(chunk) {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    if (stage === "connecting" || stage === "closed") return; // копим до подключения
    if (buf.length > 4096) return close(null); // рукопожатие столько весить не может
    try { advance(); } catch { close(null); }
  }

  sock.on("error", () => close(null));
  sock.on("close", () => close(null));
  sock.on("data", onData);
  sock.resume();
}

// ================= старт =================
function start() {
  const cfg = loadConfig();
  if (nodeTooOld()) {
    console.error(`Нужен Node.js ${process.versions.node} >= 20.3`);
    process.exit(1);
  }

  const [host, portStr] = cfg.listen.split(":");
  const port = Number(portStr) || 443;

  const handler = async (req, res) => {
    const url = new URL(req.url || "/", "https://" + (cfg.publicHost || "local"));

    if (url.pathname === "/healthz") {
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      return res.end(JSON.stringify({ ok: true, build: BUILD.build, version: BUILD.version, ts: Date.now() }));
    }
    if (url.pathname === "/favicon.ico") {
      res.writeHead(200, { "content-type": "image/svg+xml" });
      return res.end(ICON);
    }
    if (url.pathname === "/menu") return serveMenu(req, res, cfg);
    if (url.pathname === "/new" || url.pathname.startsWith("/chat") || url.pathname.startsWith("/auth")) {
      // сюда браузер попадает после входа — просто объясняем, что делать
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      return res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <body style="background:#0f0e0c;color:#f2efe9;font:16px system-ui;padding:24px">
        <h2 style="color:#d97757">Готово</h2>
        <p>Токен принят. Откройте приложение <b>Claude Обход</b> на телефоне — там галочки и вход в Claude.</p>
        <p style="color:#9a948a">Для ПК: <code>curl -x https://${cfg.publicHost}:${port} --proxy-header "Proxy-Authorization: Bearer ТОКЕН" https://claude.ai</code></p>
        </body>`);
    }

    if (url.pathname === "/relay/api/status") {
      if (!authorized(req, cfg)) return json(res, 401, { error: "unauthorized" });
      const data = await healthNow(cfg, url.searchParams.get("fresh") === "1");
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      return res.end(JSON.stringify({ ...data, build: BUILD.build, version: BUILD.version }));
    }
    if (url.pathname === "/relay/api/whoami") {
      if (!authorized(req, cfg)) return json(res, 401, { error: "unauthorized" });
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      return res.end(JSON.stringify({ ip: (req.headers["x-forwarded-for"] || "").split(",")[0] || req.socket.remoteAddress, relay: cfg.publicHost, build: BUILD.build }));
    }

    // абсолютный URL → это прокси-запрос от curl/браузера
    if (/^http:\/\//i.test(req.url || "")) {
      if (!authorized(req, cfg)) return json(res, 407, { error: "proxy authentication required" }, { "proxy-authenticate": 'Basic realm="claude-relay"' });
      return proxyHttp(req, res.socket, cfg);
    }

    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    res.end(`<!doctype html><meta charset="utf-8"><body style="background:#0f0e0c;color:#f2efe9;font:16px system-ui;padding:24px">
      <h2>claude-relay</h2><p>Работает. <a style="color:#d97757" href="/menu">Меню и проверки →</a></p></body>`);
  };

  // Снаружи — один TLS-порт. Внутри TLS смотрим первый байт:
  //   0x05 → SOCKS5 (приложение по socks5-ссылке), иначе → HTTP (меню, API, CONNECT).
  const httpServer = http.createServer(handler);
  const server = tls.createServer(tlsOptions(cfg));

  // TLS-сокеты любят присылать ECONNRESET при обрыве — не даём процессу упасть.
  server.on("connection", (s) => s.on("error", () => {}));
  server.on("tlsClientError", (_e, s) => { try { s?.destroy(); } catch {} });
  server.on("secureConnection", (sock) => {
    sock.on("error", () => {});
    sock.setTimeout(15000, () => sock.destroy()); // молчит после TLS — закрываем
    sock.once("data", (first) => {
      sock.setTimeout(0);
      sock.pause();
      sock.unshift(first);
      if (first[0] === 0x05) return socksSession(sock, cfg);
      httpServer.emit("connection", sock);
      sock.resume();
    });
  });

  httpServer.on("connect", (req, clientSock, head) => {
    clientSock.on("error", () => {});
    req.on("error", () => {});
    if (!authorized(req, cfg)) return reply(clientSock, 407, { error: "proxy authentication required" }, { "proxy-authenticate": 'Basic realm="claude-relay"' });
    const ip = clientSock.remoteAddress || "?";
    if (tooMany(ip)) return reply(clientSock, 429, { error: "too many connects" });
    doConnect(req, clientSock, head, cfg);
  });

  httpServer.on("clientError", (_e, sock) => {
    try { sock.end("HTTP/1.1 400 Bad Request\r\n\r\n"); } catch {}
  });

  server.listen(port, host, async () => {
    log(`claude-relay ${BUILD.version} слушает https://${host}:${port} (домен ${cfg.publicHost}) · HTTPS + SOCKS5 на одном порту`);
    const self = await detectSelf();
    if (self) {
      ctx.ip = self.ip;
      ctx.country = self.country || "XX";
      log(`выходной IP ${ctx.ip} (${ctx.country})`);
    }
    const s = await runChecks(cfg);
    log(`проверки: ${s.checks.filter((c) => c.ok).length}/${s.checks.length} ok${s.ok ? " — всё зелёное" : " — есть проблемы"}`);
  });

  const stop = () => {
    log("останавливаюсь…");
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
}

async function doctor() {
  const cfg = loadConfig();
  console.log(`\n  claude-relay doctor · ${cfg.publicUrl || cfg.publicHost}\n`);
  const self = await detectSelf();
  if (self) { ctx.ip = self.ip; ctx.country = self.country || "?"; }
  console.log(`  node ${process.versions.node} · IP ${ctx.ip} (${ctx.country})\n`);
  printChecks(await runChecks(cfg));
}

// Ссылка, которую человек вставляет в приложение при первом запуске.
export function socksLink(cfg, token = cfg.auth.tokens[0]) {
  const port = Number(String(cfg.listen).split(":").pop()) || 443;
  const host = cfg.publicHost.includes(":") ? `[${cfg.publicHost}]` : cfg.publicHost;
  return `socks5://relay:${encodeURIComponent(token)}@${host}${port === 443 ? "" : ":" + port}`;
}

function link() {
  const cfg = loadConfig();
  for (const t of cfg.auth.tokens) console.log(socksLink(cfg, t));
}

const cmd = process.argv[2] || "start";
if (cmd === "doctor") doctor().catch((e) => { console.error(e); process.exit(1); });
else if (cmd === "start") start();
else if (cmd === "link") link();
else { console.log("usage: node src/index.mjs [start|doctor|link]"); process.exit(1); }
