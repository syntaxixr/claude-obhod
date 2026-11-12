// Сборка ClaudeObhod.exe (нужен Bun: https://bun.sh)
//
//   bun build.mjs                                   общий exe, ссылку человек вставит сам
//   bun build.mjs --link "socks5://relay:ТОКЕН@1.2.3.4:9443"   личный exe: ссылка вшита
//
// Результат: dist/ClaudeObhod.exe
import { readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const li = args.indexOf("--link");
const link = li >= 0 ? args[li + 1] || "" : "";
const out = join(here, "dist", link ? "ClaudeObhod-personal.exe" : "ClaudeObhod.exe");
const version = JSON.parse(readFileSync(join(here, "package.json"), "utf8")).version;

const b64 = (f) => readFileSync(join(here, f)).toString("base64");
writeFileSync(join(here, "assets.js"),
  "// создаётся build.mjs, в git не хранится\n" +
  `module.exports = ${JSON.stringify({ link, ico: b64("icon.ico"), png: b64("icon.png") })};\n`);

mkdirSync(join(here, "dist"), { recursive: true });
const r = spawnSync("bun", [
  "build", join(here, "claude-obhod.js"),
  "--compile", "--minify",
  "--target=bun-windows-x64",
  `--outfile=${out}`,
  `--windows-icon=${join(here, "icon.ico")}`,
  "--windows-title=Claude Obhod",
  "--windows-publisher=syntaxixr",
  `--windows-version=${version}.0`,
  "--windows-description=Claude Code through your own server",
  "--windows-copyright=MIT · github.com/syntaxixr/claude-obhod",
  "--no-compile-autoload-dotenv",
  "--no-compile-autoload-bunfig",
], { stdio: "inherit" });

// вшитая ссылка не должна остаться лежать в папке
if (link) rmSync(join(here, "assets.js"), { force: true });
if (r.status !== 0) process.exit(r.status || 1);
console.log("\n  ✓ " + out);
