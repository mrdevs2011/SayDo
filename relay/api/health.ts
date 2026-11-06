import { json, type Req, type Res } from '../lib/http.js';

export default function handler(_req: Req, res: Res): void {
  json(res, 200, { service: 'saydo-relay', ok: true, mcp: '/mcp/<token> or /api/mcp with Authorization: Bearer <token>' });
}
