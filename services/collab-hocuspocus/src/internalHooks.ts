import { createHash, createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { transform } from "./documentTransform.js";

function required(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}
export const origin = new URL(required("ORBIT_PUBLIC_ORIGIN")).origin;
const secret = required("ORBIT_INTERNAL_HMAC_SECRET");
const backend = new URL(required("ORBIT_SERVER_INTERNAL_URL"));

function signature(path: string, timestamp: string, nonce: string, body: Buffer) {
  const digest = createHash("sha256").update(body).digest("hex");
  return createHmac("sha256", secret).update(["POST", path, timestamp, nonce, digest].join("\n")).digest();
}
class HookError extends Error {
  constructor(readonly status: number) { super("Collaboration request rejected"); }
}
export async function hook<T>(operation: string, data: object, retry = false): Promise<T> {
  const path = "/internal/v1/collab/" + operation;
  const body = Buffer.from(JSON.stringify(data));
  for (let attempt = 0; ; attempt++) {
    const timestamp = String(Math.floor(Date.now() / 1000));
    const nonce = randomUUID();
    try {
      const response = await fetch(new URL(path, backend), {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(15000),
        headers: { "Content-Type": "application/json", "X-Orbit-Timestamp": timestamp,
          "X-Orbit-Nonce": nonce, "X-Orbit-Signature": signature(path, timestamp, nonce, body).toString("hex") },
        body,
      });
      if (!response.ok) throw new HookError(response.status);
      return await response.json() as T;
    } catch (error) {
      if (!retry || attempt >= 2 || (error instanceof HookError && error.status < 500)) throw error;
    }
  }
}

const transformPath = "/internal/v1/document-transform";
const nonces = new Map<string, number>();
const maxBytes = 96 * 1024 * 1024;
export async function transformRequest(req: IncomingMessage, res: ServerResponse) {
  if (req.method !== "POST" || req.url !== transformPath) { res.writeHead(404).end(); return; }
  const ts = String(req.headers["x-orbit-timestamp"] ?? "");
  const nonce = String(req.headers["x-orbit-nonce"] ?? "");
  const sig = String(req.headers["x-orbit-signature"] ?? "");
  const now = Math.floor(Date.now() / 1000);
  if (!/^\d{1,12}$/.test(ts) || Math.abs(now - Number(ts)) > 300 || !/^[\w.:-]{8,128}$/.test(nonce)
      || !/^[a-f0-9]{64}$/.test(sig)) { res.writeHead(403).end(); return; }
  try {
    const parts: Buffer[] = [];
    let size = 0;
    for await (const part of req) {
      size += part.length;
      if (size > maxBytes) { res.writeHead(413).end(); return; }
      parts.push(part);
    }
    const body = Buffer.concat(parts);
    if (!timingSafeEqual(signature(transformPath, ts, nonce, body), Buffer.from(sig, "hex"))) {
      res.writeHead(403).end(); return;
    }
    for (const [id, expiry] of nonces) if (expiry < now) nonces.delete(id);
    if (nonces.has(nonce)) { res.writeHead(403).end(); return; }
    nonces.set(nonce, Number(ts) + 300);
    const result = transform(JSON.parse(body.toString("utf8")));
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" }).end(JSON.stringify(result));
  } catch {
    res.writeHead(422).end();
  }
}
