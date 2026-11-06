import { bodyObject, json, type Req, type Res } from '../../lib/http.js';
import { authDevice, touchDevice } from '../../lib/device.js';
import { blpop, keys, redis, PRESENCE_TTL_SEC } from '../../lib/store.js';

/** How long one poll waits for a command. The phone re-polls immediately after. */
const WAIT_SEC = 25;

/**
 * POST /api/device/poll   (X-Device-Id + Bearer device secret)
 * body: { status?: {...} }  — small status snapshot shown to the AI by device_status
 *
 * Long-poll: returns 200 { command } as soon as one is queued, or 204 after WAIT_SEC.
 * Expired commands (the AI already gave up) are skipped.
 */
export default async function handler(req: Req, res: Res): Promise<void> {
  if (req.method !== 'POST') return json(res, 405, { error: 'method_not_allowed' });
  const id = await authDevice(req);
  if (!id) return json(res, 401, { error: 'unauthorized' });

  const r = await redis();
  const status = bodyObject(req).status;
  await r.set(keys.seen(id), JSON.stringify({ at: Date.now(), status: status ?? null }), { EX: PRESENCE_TTL_SEC });
  await touchDevice(id);

  const deadline = Date.now() + WAIT_SEC * 1000;
  while (true) {
    const left = Math.floor((deadline - Date.now()) / 1000);
    if (left < 1) break;
    const raw = await blpop(keys.queue(id), left);
    if (!raw) break;
    try {
      const cmd = JSON.parse(raw);
      if (typeof cmd?.exp === 'number' && cmd.exp < Date.now()) continue; // stale
      // Still connected: refresh presence so a long wait does not look offline.
      await r.set(keys.seen(id), JSON.stringify({ at: Date.now(), status: status ?? null }), { EX: PRESENCE_TTL_SEC });
      return json(res, 200, { command: cmd });
    } catch {
      continue;
    }
  }
  res.statusCode = 204;
  res.end();
}
