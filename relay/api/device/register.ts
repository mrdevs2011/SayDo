import { bodyObject, json, safeEqualHex, sha256, DEVICE_ID_RE, HEX64_RE, type Req, type Res } from '../../lib/http.js';
import { keys, redis, DEVICE_TTL_SEC } from '../../lib/store.js';

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
  const name = String(b.name ?? 'Phone').slice(0, 60);

  if (!DEVICE_ID_RE.test(deviceId) || deviceSecret.length < 32 || deviceSecret.length > 200 || !HEX64_RE.test(clientHash)) {
    return json(res, 400, { error: 'bad_request' });
  }

  const r = await redis();
  const devKey = keys.device(deviceId);
  const existing = await r.hGetAll(devKey);
  const secretHash = sha256(deviceSecret);

  if (existing && existing.secret) {
    if (!safeEqualHex(existing.secret, secretHash)) return json(res, 403, { error: 'forbidden' });
    if (existing.client && existing.client !== clientHash) await r.del(keys.token(existing.client));
  }

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
