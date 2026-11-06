import { createClient } from 'redis';

/**
 * Redis access. Vercel Marketplace Redis (Upstash) exposes REDIS_URL / KV_URL.
 * Blocking commands (BLPOP) get their own short-lived connection, because one
 * Fluid Compute instance serves many concurrent requests and a blocked shared
 * connection would stall all of them.
 */
const REDIS_URL = process.env.REDIS_URL || process.env.KV_URL || 'redis://127.0.0.1:6379';

type Client = ReturnType<typeof createClient>;
let shared: Promise<Client> | null = null;

export function redis(): Promise<Client> {
  if (!shared) {
    const c = createClient({ url: REDIS_URL });
    c.on('error', (e: Error) => console.error('redis error:', e.message));
    shared = c.connect().catch((e) => {
      shared = null;
      throw e;
    }) as Promise<Client>;
  }
  return shared;
}

/** BLPOP on a dedicated connection. Returns the element or null on timeout. */
export async function blpop(key: string, timeoutSec: number): Promise<string | null> {
  const c = createClient({ url: REDIS_URL });
  c.on('error', () => {});
  await c.connect();
  try {
    const r = await c.blPop(key, timeoutSec);
    return r ? r.element : null;
  } finally {
    try {
      c.destroy();
    } catch {
      /* ignore */
    }
  }
}

export const keys = {
  device: (id: string) => `saydo:dev:${id}`,          // hash: secret, client, name, created
  token: (clientHash: string) => `saydo:tok:${clientHash}`, // -> device id
  queue: (id: string) => `saydo:q:${id}`,             // list of pending commands
  pending: (cmdId: string) => `saydo:p:${cmdId}`,     // -> device id that may answer
  result: (cmdId: string) => `saydo:r:${cmdId}`,      // list with one result
  seen: (id: string) => `saydo:seen:${id}`,           // presence (short TTL)
};

/** Devices not seen for this long are forgotten. */
export const DEVICE_TTL_SEC = 30 * 24 * 3600;
/** A device counts as online if it polled within this window. */
export const PRESENCE_TTL_SEC = 45;
