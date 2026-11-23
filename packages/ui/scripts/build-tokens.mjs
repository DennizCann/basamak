// Generates src/tokens.css from tokens.json (single source of truth for web, CMS, mobile).
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const t = JSON.parse(readFileSync(join(root, "tokens.json"), "utf8"));

const vars = (prefix, obj) =>
  Object.entries(obj).map(([k, v]) => `  --${prefix}-${k}: ${v};`).join("\n");

const base = ["font", "space", "radius", "motion", "size"].map((g) => vars(g, t[g])).join("\n");
const light = vars("color", t.color.light);
const dark = vars("color", t.color.dark);

const css = `/* GENERATED from tokens.json - do not edit by hand. */
:root {
${base}
${light}
  color-scheme: light;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
${dark}
    color-scheme: dark;
  }
}
:root[data-theme="dark"] {
${dark}
  color-scheme: dark;
}
@media (prefers-reduced-motion: reduce) {
  :root { --motion-fast: 0ms; --motion-base: 0ms; }
}
`;
writeFileSync(join(root, "src", "tokens.css"), css);
console.log("tokens.css generated");
