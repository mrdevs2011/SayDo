import { bearer, header, safeEqualHex, sha256, DEVICE_ID_RE, type Req } from './http.js';
import { keys, redis, DEVICE_TTL_SEC } from './store.js';
import { AUTH_FAIL_LIMIT, blocked, clientIp, recordFailure } from './ratelimit.js';

export class RateLimited extends Error {}

/**
 * Authenticates a phone by X-Device-Id + Bearer device secret. Returns the id or null.
 * Wrong credentials count against the caller's address; too many and it throws RateLimited (HTTP 429).
 */
export async function authDevice(req: Req): Promise<string | null> {
  const ip = clientIp(req);
  if (await blocked('authfail', ip, AUTH_FAIL_LIMIT)) throw new RateLimited();
  const id = header(req, 'x-device-id');
  const secret = bearer(req);
  let ok = false;
  if (id && secret && DEVICE_ID_RE.test(id) && secret.length >= 32 && secret.length <= 200) {
    const r = await redis();
    const stored = await r.hGet(keys.device(id), 'secret');
    ok = !!stored && safeEqualHex(stored, sha256(secret));
  }
  if (!ok) {
    await recordFailure(ip);
    return null;
  }
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
