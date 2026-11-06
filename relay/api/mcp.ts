import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { bearer, json, queryParam, sha256, type Req, type Res } from '../lib/http.js';
import { keys, redis } from '../lib/store.js';
import { TOOLS } from '../lib/tools.js';
import { callDevice } from '../lib/call.js';

/**
 * MCP endpoint (Streamable HTTP, stateless).
 *
 * Auth: the per-device client token, either
 *   - header  Authorization: Bearer <token>        (Claude Code, Cursor, …)
 *   - URL     /mcp/<token>                         (clients that only take a URL)
 * The token selects which phone this MCP server controls.
 */
export default async function handler(req: Req, res: Res): Promise<void> {
  const token = bearer(req) ?? queryParam(req, 'token');
  if (!token || token.length < 32 || token.length > 200) {
    res.setHeader('www-authenticate', 'Bearer');
    return json(res, 401, { error: 'unauthorized' });
  }
  const r = await redis();
  const deviceId = await r.get(keys.token(sha256(token)));
  if (!deviceId) {
    res.setHeader('www-authenticate', 'Bearer');
    return json(res, 401, { error: 'unauthorized' });
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
        `Controls the Android phone "${name}" through SayDo. Start with device_status. ` +
        'To act on the screen: read_screen (or screenshot), then click_element / type_text / swipe, ' +
        'then read_screen again to confirm the result. Prefer run_macro when a recorded macro fits.',
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
