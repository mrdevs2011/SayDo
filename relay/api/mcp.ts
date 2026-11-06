import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { bearer, json, queryParam, sha256, type Req, type Res } from '../lib/http.js';
import { keys, redis } from '../lib/store.js';
import { TOOLS } from '../lib/tools.js';
import { callDevice } from '../lib/call.js';
import { allow, AUTH_FAIL_LIMIT, blocked, clientIp, recordFailure } from '../lib/ratelimit.js';

/** Requests per minute for one client token. */
const MCP_PER_TOKEN_MIN = 120;
/** Browser origins allowed to call the endpoint (comma separated). Requests without Origin (all MCP clients that are not web pages) are always fine. */
const ALLOWED_ORIGINS = (process.env.MCP_ALLOWED_ORIGINS || '').split(',').map((o) => o.trim()).filter(Boolean);

/**
 * MCP endpoint (Streamable HTTP, stateless).
 *
 * Auth: the per-device client token, either
 *   - header  Authorization: Bearer <token>        (Claude Code, Cursor, …)
 *   - URL     /mcp/<token>                         (clients that only take a URL)
 * The token selects which phone this MCP server controls.
 */
export default async function handler(req: Req, res: Res): Promise<void> {
  // Web pages must not drive the phone: refuse browser requests from origins that are not listed.
  const origin = req.headers['origin'];
  if (typeof origin === 'string' && !ALLOWED_ORIGINS.includes(origin)) {
    return json(res, 403, { error: 'forbidden_origin' });
  }

  // Token guessing: after too many wrong tokens from one address, stop answering it for a while.
  const ip = clientIp(req);
  if (await blocked('authfail', ip, AUTH_FAIL_LIMIT)) {
    res.setHeader('retry-after', '600');
    return json(res, 429, { error: 'too_many_requests' });
  }

  const token = bearer(req) ?? queryParam(req, 'token');
  if (!token || token.length < 32 || token.length > 200) {
    await recordFailure(ip);
    res.setHeader('www-authenticate', 'Bearer');
    return json(res, 401, { error: 'unauthorized' });
  }
  const tokenHash = sha256(token);
  const r = await redis();
  const deviceId = await r.get(keys.token(tokenHash));
  if (!deviceId) {
    await recordFailure(ip);
    res.setHeader('www-authenticate', 'Bearer');
    return json(res, 401, { error: 'unauthorized' });
  }
  if (!(await allow('mcp', tokenHash, MCP_PER_TOKEN_MIN, 60))) {
    res.setHeader('retry-after', '60');
    return json(res, 429, { error: 'too_many_requests' });
  }

  if (req.method !== 'POST') {
    // Stateless server: no standalone SSE stream and no sessions to delete.
    res.setHeader('allow', 'POST');
    return json(res, 405, { jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed' }, id: null });
  }

  const name = (await r.hGet(keys.device(deviceId), 'name')) || 'Phone';
  const server = new McpServer(
    { name: 'saydo', version: '1.0.0' },
    {
      instructions:
        `Phone "${name}". Macro first:\n` +
        '1) For any goal call find_macro with it (score under 0.6: try one phrasing in another language).\n' +
        '2) Good match (0.6+): run_macro, report briefly. SENSITIVE macros need confirm=true, only after the user confirmed that exact action.\n' +
        '3) Otherwise drive the phone: act (several steps per call), read_screen with a query. read_notifications answers "what came to me". screenshot only if the tree is empty.\n' +
        '4) Never do anything with money, passwords or sending messages unless the user asked for exactly that and confirmed it.\n' +
        '5) After a manual sequence worked, offer to save it as a macro.',
    },
  );

  for (const t of TOOLS) {
    server.registerTool(
      t.name,
      {
        title: t.title,
        description: t.description,
        inputSchema: t.input,
        annotations: { readOnlyHint: t.readOnly === true, openWorldHint: false },
      },
      async (args: Record<string, unknown>) => callDevice(deviceId, t.name, args ?? {}, t.timeoutSec),
    );
  }

  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
  res.on('close', () => {
    void transport.close();
    void server.close();
  });
  await server.connect(transport);
  await transport.handleRequest(req, res, req.body);
}
