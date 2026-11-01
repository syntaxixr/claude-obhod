// claude-relay — HTTPS CONNECT-туннель + API проверок для приложения.
// Node >= 20. Лицензия MIT.
import fs from "node:fs";
import path from "node:path";
import dns from "node:dns";
import net from "node:net";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(__dirname, "..");
export const CFG_PATH = process.env.RELAY_CONFIG || path.join(ROOT, "config.json");
export const LOG_DIR = process.env.RELAY_LOG_DIR || path.join(ROOT, "logs");
export const MIN_NODE = "20.3.0";

export const sleep = (n) => new Promise((r) => setTimeout(r, n));
export const stamp = () => new Date().toISOString().replace("T", " ").slice(0, 19);

export function log(...a) {
  const line = `${stamp()} ${a.join(" ")}`;
  console.log(line);
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    fs.appendFileSync(path.join(LOG_DIR, `relay-${new Date().toISOString().slice(0, 10)}.log`), line + "\n");
  } catch {}
}

export function nodeTooOld() {
  const v = process.versions.node.split(".").map(Number);
  const m = MIN_NODE.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (v[i] !== m[i]) return v[i] < m[i];
  return false;
}

export function publicHostOf(c) {
  try { return new URL(c.publicUrl).hostname; } catch { return c.tls?.domain || ""; }
}

export function loadConfig() {
  if (!fs.existsSync(CFG_PATH)) {
    console.error(`Нет ${CFG_PATH}. Скопируйте config.example.json → config.json и заполните.`);
    process.exit(1);
  }
  let c;
  try { c = JSON.parse(fs.readFileSync(CFG_PATH, "utf8")); }
  catch (e) { console.error(`config.json сломан: ${e.message}`); process.exit(1); }

  c.listen ||= "0.0.0.0:443";
  c.tls ||= {};
  c.auth ||= {};
  c.tls.mode ||= "manual";
  c.dns = c.dns?.length ? c.dns : ["9.9.9.11", "1.1.1.1"];
  c.publicHost = publicHostOf(c);

  if (!Array.isArray(c.auth.tokens)) c.auth.tokens = [];
  c.auth.tokens = c.auth.tokens.filter((t) => typeof t === "string" && t.length >= 16);
  if (!c.auth.tokens.length) {
    console.error("В config.json нужен хотя бы один auth.tokens (>= 16 символов).");
    process.exit(1);
  }
  return c;
}

// ---------- TLS ----------
export function tlsOptions(cfg) {
  const d = cfg.tls.domain;
  const certFile = cfg.tls.certFile || `/etc/letsencrypt/live/${d}/fullchain.pem`;
  const keyFile = cfg.tls.keyFile || `/etc/letsencrypt/live/${d}/privkey.pem`;
  try {
    return { cert: fs.readFileSync(certFile), key: fs.readFileSync(keyFile) };
  } catch (e) {
    console.error(`\n  Не читаются сертификаты:\n    ${certFile}\n    ${keyFile}\n  ${e.message}`);
    console.error(`  Получите Let's Encrypt: sudo certbot certonly --standalone -d ${d}\n`);
    process.exit(1);
  }
}

// ---------- внешний IP / страна ----------
export async function detectSelf() {
  const sources = [
    { url: "https://api.country.is/", pick: (j) => ({ ip: j.ip, country: j.country }) },
    { url: "https://ipinfo.io/json", pick: (j) => ({ ip: j.ip, country: j.country, org: j.org }) },
  ];
  for (const s of sources) {
    try {
      const r = await fetch(s.url, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) continue;
      const d = s.pick(await r.json());
      if (d?.ip) return d;
    } catch {}
  }
  return null;
}

// ---------- CONNECT-туннель ----------
export function isPrivate(ip) {
  if (net.isIPv4(ip)) {
    const p = ip.split(".").map(Number);
    return (
      p[0] === 10 || p[0] === 127 || p[0] === 0 ||
      (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
      (p[0] === 192 && p[1] === 168) ||
      (p[0] === 169 && p[1] === 254) ||
      (p[0] === 100 && p[1] >= 64 && p[1] <= 127)
    );
  }
  if (net.isIPv6(ip)) {
    // приводим к каноничному виду: 0:0:0:0:0:0:0:1 → ::1, ::ffff:127.0.0.1 → ::ffff:7f00:1
    const s = canonV6(ip);
    if (s === "::1" || s === "::") return true;
    if (s.startsWith("fc") || s.startsWith("fd")) return true;      // fc00::/7
    if (/^fe[89ab]/.test(s)) return true;                            // fe80::/10
    // IPv4 внутри IPv6 (::ffff:a.b.c.d и устаревший ::a.b.c.d)
    const m = /^::(?:ffff:)?([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(s);
    if (m) {
      const hi = parseInt(m[1], 16), lo = parseInt(m[2], 16);
      return isPrivate(`${hi >> 8}.${hi & 255}.${lo >> 8}.${lo & 255}`);
    }
  }
  return false;
}

export function canonV6(ip) {
  try { return new URL(`http://[${ip}]/`).hostname.slice(1, -1).toLowerCase(); }
  catch { return String(ip).toLowerCase(); }
}

/** Разбирает "host:port" (включая IPv6 [::1]:443). */
export function splitHostPort(s, defPort = 443) {
  const m = /^\[([^\]]+)\]:(\d+)$/.exec(s || "");
  if (m) return { host: m[1], port: Number(m[2]) };
  const i = (s || "").lastIndexOf(":");
  if (i === -1) return { host: s, port: defPort };
  return { host: s.slice(0, i), port: Number(s.slice(i + 1)) || defPort };
}

export const eq = (a, b) => {
  const A = Buffer.from(String(a)), B = Buffer.from(String(b));
  return A.length === B.length && crypto.timingSafeEqual(A, B);
};
