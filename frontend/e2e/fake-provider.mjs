/* global Buffer, console, process */

import { appendFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { createServer } from 'node:http';

const port = Number(process.env.FAKE_PROVIDER_PORT || 8787);
const logPath = process.env.FAKE_PROVIDER_LOG || '';
const requests = [];

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

async function record(entry) {
  requests.push(entry);
  if (!logPath) return;
  await mkdir(dirname(logPath), { recursive: true });
  await appendFile(logPath, `${JSON.stringify(entry)}\n`, 'utf8');
}

const server = createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ status: 'UP' }));
    return;
  }

  if (request.method === 'POST' && request.url === '/responses') {
    const raw = await readBody(request);
    await record({ method: request.method, url: request.url, body: JSON.parse(raw) });
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'fake-response-e2e',
        model: 'fake-e2e',
        output_text: 'deterministic fake provider answer',
      }),
    );
    return;
  }

  response.writeHead(404, { 'content-type': 'application/json' });
  response.end(JSON.stringify({ error: 'not found' }));
});

server.listen(port, '127.0.0.1', () => {
  console.log(`fake provider listening on http://127.0.0.1:${port}`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
