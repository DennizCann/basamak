import { createHmac } from "node:crypto";

export const WEB = "http://localhost:5173";
export const CMS = "http://localhost:5174";
export const MAILPIT = process.env.MAILPIT_URL || "http://localhost:8025";
export const DEMO_PASSWORD = "Basamak!2026";

/** RFC 6238 TOTP from a base32 secret (same algorithm as authenticator apps). */
export function totp(secret: string): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/\s|=/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

/** Reads the newest e-mail sent to `to` from Mailpit's API and returns its text body. */
export async function lastMailTo(to: string): Promise<string> {
  for (let i = 0; i < 20; i++) {
    const r = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent("to:" + to)}`);
    const data = await r.json();
    if (data.messages?.length) {
      const msg = await (await fetch(`${MAILPIT}/api/v1/message/${data.messages[0].ID}`)).json();
      return msg.Text as string;
    }
    await new Promise((res) => setTimeout(res, 500));
  }
  throw new Error("no mail for " + to);
}
