import { bodyObject, json, CMD_ID_RE, type Req, type Res } from '../../lib/http.js';
import { authDevice, RateLimited } from '../../lib/device.js';
import { keys, redis } from '../../lib/store.js';

/** Results above this are rejected (Vercel body limit is 4.5 MB). */
const MAX_RESULT_BYTES = 4 * 1024 * 1024;

/**
 * POST /api/device/result   (X-Device-Id + Bearer device secret)
 * { id, ok, content: [{type:"text",text} | {type:"image",data,mimeType}], error? }
 *
 * Only the device the command was sent to may answer it.
 */
export default async function handler(req: Req, res: Res): Promise<void> {
  if (req.method !== 'POST') return json(res, 405, { error: 'method_not_allowed' });
  let id: string | null;
  try {
    id = await authDevice(req);
  } catch (e) {
    if (e instanceof RateLimited) return json(res, 429, { error: 'too_many_requests' });
    throw e;
  }
  if (!id) return json(res, 401, { error: 'unauthorized' });

  const b = bodyObject(req);
  const cmdId = String(b.id ?? '');
  if (!CMD_ID_RE.test(cmdId)) return json(res, 400, { error: 'bad_request' });

  const r = await redis();
  const owner = await r.get(keys.pending(cmdId));
  if (owner !== id) return json(res, 404, { error: 'unknown_command' }); // expired or not yours

  const payload = JSON.stringify({ ok: b.ok === true, content: b.content ?? [], error: b.error ?? null });
  if (Buffer.byteLength(payload) > MAX_RESULT_BYTES) return json(res, 413, { error: 'too_large' });

  await r.multi().del(keys.pending(cmdId)).rPush(keys.result(cmdId), payload).expire(keys.result(cmdId), 60).exec();
  return json(res, 200, { ok: true });
}
