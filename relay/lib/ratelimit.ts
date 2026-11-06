import { redis } from './store.js';
import type { Req } from './http.js';

/**
 * Small fixed-window counters in Redis.
 *
 * Client address: Vercel sets x-vercel-forwarded-for / x-real-ip itself and overwrites any value the
 * client sends, so those are trusted first. x-forwarded-for and the socket address are fallbacks for
 * other hosts and for the tests. Behind a proxy that does not overwrite them an attacker can pick the
 * address, so the per-address limits are only as good as the platform.
 */
export function clientIp(req: Req): string {
  for (const h of ['x-vercel-forwarded-for', 'x-real-ip']) {
    const v = req.headers[h];
    if (typeof v === 'string' && v.trim()) return v.split(',')[0].trim().slice(0, 64);
  }
  const xff = req.headers['x-forwarded-for'];
  if (typeof xff === 'string' && xff.trim()) return xff.split(',')[0].trim().slice(0, 64);
  return req.socket?.remoteAddress ?? 'unknown';
}

const k = (bucket: string, id: string) => `saydo:rl:${bucket}:${id}`;

/** Counts one event. Returns true while the count is within `limit` for the current window. */
export async function allow(bucket: string, id: string, limit: number, windowSec: number): Promise<boolean> {
  const r = await redis();
  const key = k(bucket, id);
  const n = await r.incr(key);
  if (n === 1) await r.expire(key, windowSec);
  return n <= limit;
}

/** True when `id` already used up `limit` events in this window (does not count). */
export async function blocked(bucket: string, id: string, limit: number): Promise<boolean> {
  const r = await redis();
  const n = Number((await r.get(k(bucket, id))) ?? 0);
  return n >= limit;
}

/** Records a failed authentication from `id`. */
export async function recordFailure(id: string, windowSec = AUTH_FAIL_WINDOW_SEC): Promise<void> {
  await allow('authfail', id, Number.MAX_SAFE_INTEGER, windowSec);
}

export const AUTH_FAIL_LIMIT = 30;
export const AUTH_FAIL_WINDOW_SEC = 600;
