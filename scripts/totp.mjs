// Demo helper: prints the current 6-digit TOTP code for a base32 secret (RFC 6238, SHA1, 30 s).
// Use it when you do not have an authenticator app at hand:  node scripts/totp.mjs <SECRET>
import { createHmac } from "node:crypto";

const secret = (process.argv[2] || "").replace(/\s|=/g, "").toUpperCase();
if (!secret) {
  console.error("usage: node scripts/totp.mjs <BASE32_SECRET>");
  process.exit(1);
}
const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
let bits = "";
for (const c of secret) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
const counter = Buffer.alloc(8);
counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
const h = createHmac("sha1", key).update(counter).digest();
const o = h[h.length - 1] & 0xf;
const code = ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
console.log(code);
