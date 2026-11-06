import { bearer, header, safeEqualHex, sha256, DEVICE_ID_RE, type Req } from './http.js';
import { keys, redis, DEVICE_TTL_SEC } from './store.js';

/** Authenticates a phone by X-Device-Id + Bearer device secret. Returns the id or null. */
export async function authDevice(req: Req): Promise<string | null> {
  const id = header(req, 'x-device-id');
  const secret = bearer(req);
  if (!id || !secret || !DEVICE_ID_RE.test(id) || secret.length < 32) return null;
  const r = await redis();
  const stored = await r.hGet(keys.device(id), 'secret');
  if (!stored || !safeEqualHex(stored, sha256(secret))) return null;
  return id;
}

/** Keep an active device (and its token mapping) from expiring. */
export async function touchDevice(id: string): Promise<void> {
  const r = await redis();
  const client = await r.hGet(keys.device(id), 'client');
  const multi = r.multi().expire(keys.device(id), DEVICE_TTL_SEC);
  if (client) multi.expire(keys.token(client), DEVICE_TTL_SEC);
  await multi.exec();
}
