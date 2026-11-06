/**
 * End-to-end: real Redis + relay handlers + a simulated phone + the official MCP client.
 * Run: npx tsc -p . && node --test dist/test/
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, type ChildProcess } from 'node:child_process';
import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import type { AddressInfo } from 'node:net';

const REDIS_PORT = 6391;
process.env.REDIS_URL = `redis://127.0.0.1:${REDIS_PORT}`;

let redisProc: ChildProcess;
let server: http.Server;
let base = '';

const sha = (s: string) => createHash('sha256').update(s).digest('hex');
const rnd = (n = 32) => randomBytes(n).toString('base64url');

async function startRelay(): Promise<void> {
  const routes: Record<string, (req: any, res: any) => Promise<void> | void> = {
    '/api/mcp': (await import('../api/mcp.js')).default,
    '/api/device/register': (await import('../api/device/register.js')).default,
    '/api/device/poll': (await import('../api/device/poll.js')).default,
    '/api/device/result': (await import('../api/device/result.js')).default,
  };
  server = http.createServer(async (req: any, res) => {
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const raw = Buffer.concat(chunks).toString('utf8');
    try { req.body = raw ? JSON.parse(raw) : undefined; } catch { req.body = raw; }
    const u = new URL(req.url, 'http://x');
    // Same rewrite as vercel.json: /mcp/:token -> /api/mcp?token=
    let path = u.pathname;
    const m = /^\/mcp\/([^/]+)$/.exec(path);
    if (m) { path = '/api/mcp'; u.searchParams.set('token', m[1]); }
    req.query = Object.fromEntries(u.searchParams);
    const h = routes[path];
    if (!h) { res.statusCode = 404; res.end(); return; }
    try { await h(req, res); } catch (e) { res.statusCode = 500; res.end(String(e)); }
  });
  await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function post(path: string, body: unknown, headers: Record<string, string> = {}) {
  const r = await fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null };
}

/** Simulated phone: long-polls and answers commands with a handler. */
function phone(deviceId: string, secret: string, answer: (cmd: any) => any) {
  let stop = false;
  const auth = { 'x-device-id': deviceId, authorization: `Bearer ${secret}` };
  const loop = (async () => {
    while (!stop) {
      const r = await fetch(base + '/api/device/poll', {
        method: 'POST', headers: { 'content-type': 'application/json', ...auth },
        body: JSON.stringify({ status: { model: 'TestPhone' } }),
      });
      if (r.status === 200) {
        const { command } = await r.json();
        const result = await answer(command);
        await post('/api/device/result', { id: command.id, ...result }, auth);
      } else {
        await r.arrayBuffer();
        if (r.status !== 204) await new Promise((ok) => setTimeout(ok, 200));
      }
    }
  })();
  return { stop: async () => { stop = true; await loop.catch(() => {}); } };
}

async function mcpClient(url: string, headers?: Record<string, string>) {
  const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
  const { StreamableHTTPClientTransport } = await import('@modelcontextprotocol/sdk/client/streamableHttp.js');
  const client = new Client({ name: 'test', version: '1.0.0' });
  await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers } }));
  return client;
}

before(async () => {
  redisProc = spawn('redis-server', ['--port', String(REDIS_PORT), '--save', '', '--appendonly', 'no'], { stdio: 'ignore' });
  for (let i = 0; i < 50; i++) {
    try {
      const { createClient } = await import('redis');
      const c = createClient({ url: process.env.REDIS_URL });
      await c.connect(); await c.flushAll(); c.destroy();
      break;
    } catch { await new Promise((ok) => setTimeout(ok, 100)); }
  }
  await startRelay();
});

after(async () => {
  server?.close();
  (await import('../lib/store.js')).redis().then((c) => c.destroy()).catch(() => {});
  redisProc?.kill();
});

test('full flow: register, MCP tools, phone executes, results come back', async () => {
  const deviceId = rnd(16), secret = rnd(), token = rnd();
  const reg = await post('/api/device/register', { deviceId, deviceSecret: secret, clientTokenHash: sha(token), name: 'Test' });
  assert.equal(reg.status, 200);

  const seen: any[] = [];
  const ph = phone(deviceId, secret, (cmd) => {
    seen.push(cmd);
    if (cmd.tool === 'tap') return { ok: true, content: [{ type: 'text', text: `tapped ${cmd.args.x},${cmd.args.y}` }] };
    if (cmd.tool === 'screenshot') return { ok: true, content: [{ type: 'image', data: 'AAAA', mimeType: 'image/jpeg' }] };
    return { ok: false, error: 'not implemented in test' };
  });
  await new Promise((ok) => setTimeout(ok, 300)); // first poll marks the phone online

  // Header auth
  const c = await mcpClient(base + '/api/mcp', { authorization: `Bearer ${token}` });
  const tools = await c.listTools();
  assert.ok(tools.tools.some((t) => t.name === 'read_screen'));
  assert.ok(tools.tools.some((t) => t.name === 'run_macro'));

  const tap = await c.callTool({ name: 'tap', arguments: { x: 100, y: 200 } });
  assert.equal(tap.isError, undefined);
  assert.deepEqual(tap.content, [{ type: 'text', text: 'tapped 100,200' }]);

  const shot = await c.callTool({ name: 'screenshot', arguments: {} });
  assert.equal((shot.content as any[])[0].type, 'image');

  const bad = await c.callTool({ name: 'list_apps', arguments: {} });
  assert.equal(bad.isError, true);
  await c.close();

  // URL-token auth
  const c2 = await mcpClient(`${base}/mcp/${token}`);
  const tap2 = await c2.callTool({ name: 'tap', arguments: { x: 1, y: 2 } });
  assert.deepEqual(tap2.content, [{ type: 'text', text: 'tapped 1,2' }]);
  await c2.close();

  // Invalid arguments never reach the phone
  const before = seen.length;
  const c3 = await mcpClient(`${base}/mcp/${token}`);
  const inv = await c3.callTool({ name: 'tap', arguments: { x: -5, y: 'a' } }).catch((e) => ({ isError: true, e }));
  assert.equal((inv as any).isError, true);
  await c3.close();
  assert.equal(seen.length, before);

  await ph.stop();
});

test('wrong token is rejected', async () => {
  const r = await post('/api/mcp', { jsonrpc: '2.0', id: 1, method: 'tools/list' }, { authorization: `Bearer ${rnd()}` });
  assert.equal(r.status, 401);
  const none = await post('/api/mcp', { jsonrpc: '2.0', id: 1, method: 'tools/list' });
  assert.equal(none.status, 401);
});

test('offline phone gives a clear error instead of hanging', async () => {
  const deviceId = rnd(16), secret = rnd(), token = rnd();
  await post('/api/device/register', { deviceId, deviceSecret: secret, clientTokenHash: sha(token), name: 'Off' });
  const c = await mcpClient(`${base}/mcp/${token}`);
  const t0 = Date.now();
  const r = await c.callTool({ name: 'device_status', arguments: {} });
  assert.equal(r.isError, true);
  assert.match((r.content as any[])[0].text, /offline/);
  assert.ok(Date.now() - t0 < 2000);
  await c.close();
});

test('device secret protects re-registration and token rotation', async () => {
  const deviceId = rnd(16), secret = rnd(), t1 = rnd(), t2 = rnd();
  assert.equal((await post('/api/device/register', { deviceId, deviceSecret: secret, clientTokenHash: sha(t1) })).status, 200);
  // Someone else cannot take over the id
  assert.equal((await post('/api/device/register', { deviceId, deviceSecret: rnd(), clientTokenHash: sha(rnd()) })).status, 403);
  // Owner rotates the token: old one stops working
  assert.equal((await post('/api/device/register', { deviceId, deviceSecret: secret, clientTokenHash: sha(t2) })).status, 200);
  assert.equal((await post('/api/mcp', { jsonrpc: '2.0', id: 1, method: 'tools/list' }, { authorization: `Bearer ${t1}` })).status, 401);
});

test('poll and result require the device secret; a device cannot answer another device', async () => {
  const a = { id: rnd(16), secret: rnd() }, b = { id: rnd(16), secret: rnd() };
  await post('/api/device/register', { deviceId: a.id, deviceSecret: a.secret, clientTokenHash: sha(rnd()) });
  await post('/api/device/register', { deviceId: b.id, deviceSecret: b.secret, clientTokenHash: sha(rnd()) });
  const wrong = await post('/api/device/poll', {}, { 'x-device-id': a.id, authorization: `Bearer ${b.secret}` });
  assert.equal(wrong.status, 401);

  // Queue a command for A by hand, then let B try to answer it.
  const { redis, keys } = await import('../lib/store.js');
  const r = await redis();
  const cmdId = '11111111-2222-3333-4444-555555555555';
  await r.set(keys.pending(cmdId), a.id, { EX: 30 });
  const spoof = await post('/api/device/result', { id: cmdId, ok: true, content: [] }, { 'x-device-id': b.id, authorization: `Bearer ${b.secret}` });
  assert.equal(spoof.status, 404);
  const real = await post('/api/device/result', { id: cmdId, ok: true, content: [] }, { 'x-device-id': a.id, authorization: `Bearer ${a.secret}` });
  assert.equal(real.status, 200);
});

test('phone that dies mid-command: MCP call times out instead of hanging forever', async () => {
  const { callDevice } = await import('../lib/call.js');
  const { redis, keys } = await import('../lib/store.js');
  const deviceId = rnd(16);
  const r = await redis();
  await r.set(keys.seen(deviceId), '{}', { EX: 30 }); // looks online, never answers
  const t0 = Date.now();
  const res = await callDevice(deviceId, 'tap', { x: 1, y: 1 }, 2);
  assert.equal(res.isError, true);
  assert.match((res.content[0] as any).text, /did not answer/);
  assert.ok(Date.now() - t0 < 4000);
});
