import { bodyObject, json, safeEqualHex, sha256, DEVICE_ID_RE, HEX64_RE, type Req, type Res } from '../../lib/http.js';
import { keys, redis, DEVICE_TTL_SEC } from '../../lib/store.js';
import { allow, clientIp } from '../../lib/ratelimit.js';

/** Registrations per address and hour (a phone registers once, plus a few retries and token rotations). */
const REGISTER_PER_IP_HOUR = 20;
/** New devices per day across everyone; keeps open registration from filling Redis. Override with RELAY_MAX_NEW_DEVICES_PER_DAY. */
const MAX_NEW_DEVICES_PER_DAY = Number(process.env.RELAY_MAX_NEW_DEVICES_PER_DAY) || 200;

/**
 * POST /api/device/register
 * { deviceId, deviceSecret, clientTokenHash, name }
 *
 * The phone generates all secrets itself. The relay only stores hashes.
 * - New device id: created.
 * - Existing id: only the holder of the device secret may update it (used to rotate
 *   the AI client token or rename the device).
 */
export default async function handler(req: Req, res: Res): Promise<void> {
  if (req.method !== 'POST') return json(res, 405, { error: 'method_not_allowed' });
  const b = bodyObject(req);
  const deviceId = String(b.deviceId ?? '');
  const deviceSecret = String(b.deviceSecret ?? '');
  const clientHash = String(b.clientTokenHash ?? '').toLowerCase();
  // The name is shown to the AI in the MCP instructions: no control characters or line breaks.
  const name = String(b.name ?? 'Phone').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 60) || 'Phone';

  if (!DEVICE_ID_RE.test(deviceId) || deviceSecret.length < 32 || deviceSecret.length > 200 || !HEX64_RE.test(clientHash)) {
    return json(res, 400, { error: 'bad_request' });
  }

  if (!(await allow('register', clientIp(req), REGISTER_PER_IP_HOUR, 3600))) {
    return json(res, 429, { error: 'too_many_requests' });
  }

  const r = await redis();
  const devKey = keys.device(deviceId);
  const existing = await r.hGetAll(devKey);
  const secretHash = sha256(deviceSecret);

  if (existing && existing.secret) {
    if (!safeEqualHex(existing.secret, secretHash)) return json(res, 403, { error: 'forbidden' });
    if (existing.client && existing.client !== clientHash) await r.del(keys.token(existing.client));
  }

  if (!existing?.secret && !(await allow('newdevice', 'all', MAX_NEW_DEVICES_PER_DAY, 86400))) {
    return json(res, 429, { error: 'registration_closed_for_today' });
  }
  // A token hash that already belongs to another device is never taken over.
  const tokenOwner = await r.get(keys.token(clientHash));
  if (tokenOwner && tokenOwner !== deviceId) return json(res, 409, { error: 'conflict' });

  await r
    .multi()
    .hSet(devKey, {
      secret: secretHash,
      client: clientHash,
      name,
      created: existing?.created || String(Date.now()),
    })
    .expire(devKey, DEVICE_TTL_SEC)
    .set(keys.token(clientHash), deviceId, { EX: DEVICE_TTL_SEC })
    .exec();

  return json(res, 200, { ok: true });
}
