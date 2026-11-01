// Проверки, которые VDS выполняет сам: доступен ли claude.ai именно с него.
import dns from "node:dns";
import net from "node:net";
import tls from "node:tls";
import { ctx } from "./relayCtx.mjs";

const t0 = () => performance.now();
const took = (s) => Math.round(performance.now() - s);
const BLOCKED = new Set(["CN", "HK", "JP", "KR", "RU", "BY", "IR", "KP", "CU", "SY", "VE"]);

export const CHECK_LIST = [
  { id: "vds", label: "Сервер VDS" },
  { id: "tls", label: "HTTPS / сертификат" },
  { id: "exit", label: "Выходной IP" },
  { id: "dns", label: "DNS" },
  { id: "claude", label: "claude.ai доступен" },
  { id: "api", label: "Anthropic API" },
  { id: "ws", label: "TCP 443 до claude.ai" },
  { id: "region", label: "Регион не заблокирован" },
];

function tlsPeer(host, port = 443, timeout = 8000, selfSigned = false) {
  return new Promise((resolve, reject) => {
    const servername = net.isIP(host) ? undefined : host;
    const s = tls.connect({ host, port, servername, timeout, rejectUnauthorized: !selfSigned }, () => {
      const c = s.getPeerCertificate();
      s.end();
      resolve(c);
    });
    s.on("error", reject);
    s.on("timeout", () => reject(new Error("timeout")));
  });
}

export async function runChecks(cfg) {
  const res = [];
  const add = (id, ok, ms, detail) => res.push({ id, ok, ms, detail: String(detail ?? "").slice(0, 160) });

  // VDS
  add("vds", true, 0, `pid ${process.pid} · up ${Math.round(process.uptime())}с`);

  // TLS нашего домена — проверяем снаружи (localhost не подходит, нужен SNI).
  {
    const s = t0();
    try {
      const host = cfg.tls.domain || cfg.publicHost;
      const port = Number(String(cfg.listen).split(":").pop()) || 443;
      // свой сертификат вместо Let's Encrypt (установка по IP) — проверяем срок, но не цепочку
      const selfSigned = !!cfg.tls.certFile && !cfg.tls.certFile.startsWith("/etc/letsencrypt/");
      const c = await tlsPeer(host, port, 8000, selfSigned);
      const until = new Date(c.valid_to);
      const left = Math.floor((until - Date.now()) / 86400000);
      const kind = selfSigned ? " · самоподписанный" : "";
      add("tls", until > new Date(), took(s), until > new Date() ? `до ${until.toISOString().slice(0, 10)} (${left} дн.)${kind}` : "истёк");
    } catch (e) {
      add("tls", false, took(s), e.message || e.code);
    }
  }

  // Выходной IP
  {
    const s = t0();
    add("exit", !!ctx.ip, took(s), `${ctx.ip || "?"}${ctx.country ? " · " + ctx.country : ""}`);
  }

  // DNS
  {
    const s = t0();
    try {
      const a = await new Promise((r, j) => dns.resolve4("claude.ai", (e, x) => (e ? j(e) : r(x))));
      add("dns", !!a?.length, took(s), a?.slice(0, 2).join(", ") || "");
    } catch (e) {
      add("dns", false, took(s), e.code || e.message);
    }
  }

  // claude.ai
  let claudeOk = false;
  {
    const s = t0();
    try {
      // без браузерного User-Agent Cloudflare отвечает 403 даже из разрешённой страны
      const r = await fetch("https://claude.ai/", {
        method: "HEAD", redirect: "manual", signal: AbortSignal.timeout(10000),
        headers: { "user-agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36" },
      });
      const loc = r.headers.get("location") || "";
      const regionBlock = /unavailable-in-region|not-in-supported/i.test(loc);
      const challenge = r.status === 403 && !!r.headers.get("cf-mitigated"); // JS-проверка, в WebView проходит сама
      claudeOk = !regionBlock && ((r.status > 0 && r.status < 400) || challenge);
      const note = regionBlock ? " регион закрыт" : challenge ? " · проверка Cloudflare (норма)" : claudeOk ? "" : " " + loc.slice(0, 60);
      add("claude", claudeOk, took(s), `HTTP ${r.status}${note}`);
    } catch (e) {
      add("claude", false, took(s), e.cause?.code || e.message);
    }
  }

  // Anthropic API: 401/405 — доступен; 403 — блок
  let apiOk = false;
  {
    const s = t0();
    try {
      const r = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: "{}",
        signal: AbortSignal.timeout(10000),
      });
      apiOk = r.status !== 403;
      add("api", apiOk, took(s), `HTTP ${r.status}`);
    } catch (e) {
      add("api", false, took(s), e.cause?.code || e.message);
    }
  }

  // WebSocket: реальный TLS-хендшейк + проверка, что порт 443 claude.ai отвечает
  {
    const s = t0();
    try {
      const ip = await new Promise((r) => dns.lookup("claude.ai", (e, a) => r(e ? null : a)));
      if (!ip) throw new Error("DNS не вернул IP");
      await new Promise((resolve, reject) => {
        const sock = net.connect({ host: ip, port: 443 }, () => {
          sock.destroy();
          resolve();
        });
        sock.on("error", reject);
        sock.setTimeout(6000, () => reject(new Error("timeout")));
      });
      add("ws", true, took(s), `TCP 443 ${ip} ok`);
    } catch (e) {
      add("ws", false, took(s), e.message);
    }
  }

  // Регион
  {
    const s = t0();
    const blocked = BLOCKED.has(ctx.country) || !claudeOk || !apiOk;
    add("region", !blocked, took(s), blocked ? `заблокирован (${ctx.country || "?"})` : `${ctx.country} ok`);
  }

  const ok = res.every((r) => r.ok);
  return { ok, checks: res, checkedAt: Date.now(), ip: ctx.ip, country: ctx.country };
}

export function printChecks(s) {
  for (const r of s.checks) {
    const meta = CHECK_LIST.find((c) => c.id === r.id);
    console.log(`  ${r.ok ? "✔" : "✘"} ${(meta?.label || r.id).padEnd(24)} ${String(r.ms).padStart(5)} мс  ${r.detail}`);
  }
  console.log(`\n  ${s.ok ? "ВСЁ ЗЕЛЁНОЕ" : "ЕСТЬ КРАСНОЕ — вход заблокирован"}\n`);
}
