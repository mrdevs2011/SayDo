import { randomUUID } from 'node:crypto';
import { blpop, keys, redis } from './store.js';

export type Content =
  | { type: 'text'; text: string }
  | { type: 'image'; data: string; mimeType: string };

export type CallResult = { content: Content[]; isError?: boolean };

function text(t: string, isError = false): CallResult {
  return { content: [{ type: 'text', text: t }], isError };
}

/** Keep only well-formed content items coming back from the phone. */
export function sanitizeContent(raw: unknown): Content[] {
  if (!Array.isArray(raw)) return [];
  const out: Content[] = [];
  for (const c of raw) {
    if (!c || typeof c !== 'object') continue;
    const o = c as Record<string, unknown>;
    if (o.type === 'text' && typeof o.text === 'string') {
      out.push({ type: 'text', text: o.text });
    } else if (o.type === 'image' && typeof o.data === 'string' && typeof o.mimeType === 'string'
        && /^image\/(jpeg|png|webp)$/.test(o.mimeType)) {
      out.push({ type: 'image', data: o.data, mimeType: o.mimeType });
    }
  }
  return out;
}

/**
 * Sends one command to the phone and waits for its answer.
 * The phone picks it up through its long-poll and posts the result back.
 */
export async function callDevice(
  deviceId: string,
  tool: string,
  args: Record<string, unknown>,
  timeoutSec: number,
): Promise<CallResult> {
  const r = await redis();
  if (!(await r.exists(keys.seen(deviceId)))) {
    return text(
      'The phone is offline: SayDo MCP is switched off on the phone, the phone has no internet, ' +
        'or Android stopped the app. Ask the user to open SayDo and check Settings → MCP.',
      true,
    );
  }

  const cmdId = randomUUID();
  const cmd = { id: cmdId, tool, args, exp: Date.now() + timeoutSec * 1000 };
  await r
    .multi()
    .set(keys.pending(cmdId), deviceId, { EX: timeoutSec + 15 })
    .rPush(keys.queue(deviceId), JSON.stringify(cmd))
    .expire(keys.queue(deviceId), 600)
    .exec();

  const raw = await blpop(keys.result(cmdId), timeoutSec);
  if (!raw) {
    await r.del(keys.pending(cmdId));
    return text(`The phone did not answer within ${timeoutSec}s (tool: ${tool}).`, true);
  }
  try {
    const res = JSON.parse(raw) as { ok?: boolean; content?: unknown; error?: unknown };
    const content = sanitizeContent(res.content);
    if (res.ok !== true) {
      const msg = typeof res.error === 'string' && res.error ? res.error : 'Command failed on the phone.';
      return { content: content.length ? content : [{ type: 'text', text: msg }], isError: true };
    }
    return { content: content.length ? content : [{ type: 'text', text: 'OK' }] };
  } catch {
    return text('The phone sent an unreadable result.', true);
  }
}
