import type { IncomingMessage, ServerResponse } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';

/** Vercel's Node runtime adds parsed `body` and `query` to the request. */
export type Req = IncomingMessage & { body?: unknown; query?: Record<string, string | string[]> };
export type Res = ServerResponse;

export function json(res: Res, status: number, body: unknown): void {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json');
  res.setHeader('cache-control', 'no-store');
  res.end(JSON.stringify(body));
}

export function sha256(s: string): string {
  return createHash('sha256').update(s, 'utf8').digest('hex');
}

export function safeEqualHex(a: string, b: string): boolean {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

export function bearer(req: Req): string | null {
  const h = req.headers['authorization'];
  if (typeof h !== 'string') return null;
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : null;
}

export function header(req: Req, name: string): string | null {
  const v = req.headers[name.toLowerCase()];
  return typeof v === 'string' ? v : null;
}

export function queryParam(req: Req, name: string): string | null {
  const q = req.query?.[name];
  if (typeof q === 'string') return q;
  if (Array.isArray(q) && typeof q[0] === 'string') return q[0];
  // Fallback when query was not pre-parsed.
  try {
    const u = new URL(req.url || '/', 'http://x');
    return u.searchParams.get(name);
  } catch {
    return null;
  }
}

/** Body as an object (Vercel pre-parses JSON; tests may pass a string). */
export function bodyObject(req: Req): Record<string, unknown> {
  const b = req.body;
  if (b && typeof b === 'object' && !Buffer.isBuffer(b)) return b as Record<string, unknown>;
  if (typeof b === 'string') {
    try {
      const o = JSON.parse(b);
      return o && typeof o === 'object' ? o : {};
    } catch {
      return {};
    }
  }
  return {};
}

export const DEVICE_ID_RE = /^[A-Za-z0-9_-]{16,64}$/;
export const HEX64_RE = /^[0-9a-f]{64}$/;
export const CMD_ID_RE = /^[0-9a-f-]{36}$/;
