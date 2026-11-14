// One-time, loopback-only transfer of the user's own ChatGPT session to Voxora.
// Requires an installed debuggable APK and Android platform-tools (adb).
// Session values are never written to files or command-line arguments on the PC.
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const PACKAGE = 'local.watchvoice';
const TTL_MS = 15 * 60 * 1000;
const MAX_POST_BYTES = 60000;
const COOKIE_NAME = /^__Secure-next-auth\.session-token(?:\.\d+)?$/;

function usage() {
  console.log('Usage: node provision-once.mjs [--serial DEVICE] [--adb PATH]');
  console.log('Connect the watch with adb first. --serial is needed if more than one device is connected.');
}

function options(argv) {
  const result = { adb: 'adb', serial: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') return null;
    if (arg !== '--serial' && arg !== '--adb') throw Error('Unknown option: ' + arg);
    if (!argv[i + 1] || argv[i + 1].startsWith('--')) throw Error('Missing value for ' + arg);
    result[arg.slice(2)] = argv[++i];
  }
  return result;
}

function adbOutput(adb, args) {
  const result = spawnSync(adb, args, {
    encoding: 'utf8', windowsHide: true, timeout: 10000, maxBuffer: 100000,
  });
  if (result.error || result.status !== 0) {
    throw Error('adb failed. Check that Android platform-tools are installed and the watch is connected.');
  }
  return result.stdout;
}

function selectDevice(adb, requested) {
  const lines = adbOutput(adb, ['devices']).split(/\r?\n/).slice(1);
  const devices = lines.map(line => line.match(/^(\S+)\s+device(?:\s|$)/)?.[1]).filter(Boolean);
  if (!devices.length) throw Error('No authorized ADB device. Pair and connect the watch first.');
  if (requested && !devices.includes(requested)) throw Error('The requested device is not connected.');
  if (!requested && devices.length !== 1) {
    throw Error('Multiple ADB devices are connected. Run again with --serial DEVICE.');
  }
  const serial = requested ?? devices[0];
  adbOutput(adb, ['-s', serial, 'shell', 'run-as', PACKAGE, 'pwd']);
  return serial;
}

function provisionPayload(form) {
  const deviceId = (form.get('deviceId') ?? '').trim();
  if (!/^[A-Za-z0-9-]{1,128}$/.test(deviceId)) throw Error('Invalid device ID.');

  const lines = (form.get('cookies') ?? '').split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (!lines.length || lines.length > 8) throw Error('Enter one to eight session cookie entries.');
  const sessionCookies = {};
  for (const line of lines) {
    const separator = line.indexOf('=');
    const name = line.slice(0, separator).trim();
    const value = line.slice(separator + 1).trim();
    if (separator <= 0 || !COOKIE_NAME.test(name) || !value || /[;\r\n]/.test(value) ||
        Object.hasOwn(sessionCookies, name)) {
      throw Error('Invalid session cookie name or value.');
    }
    sessionCookies[name] = value;
  }
  const payload = JSON.stringify({ deviceId, sessionCookies });
  if (Buffer.byteLength(payload, 'utf8') > 59000) throw Error('Session data is too large.');
  return payload;
}

function transfer(adb, serial, payload) {
  return new Promise((resolve, reject) => {
    // Only fixed text appears in argv. The session is transferred over stdin.
    const command = "run-as local.watchvoice sh -c 'umask 077; mkdir -p files; cat > files/provision.json'";
    const child = spawn(adb, ['-s', serial, 'shell', command], {
      windowsHide: true, stdio: ['pipe', 'ignore', 'pipe'],
    });
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      if (error) reject(error);
      else resolve();
    };
    child.stderr.on('data', () => {}); // Never echo external output or session data.
    child.stdin.on('error', () => {});
    child.once('error', () => finish(Error('Could not start adb.')));
    child.once('close', code => finish(code === 0 ? null : Error('ADB transfer failed.')));
    child.stdin.end(payload);
  });
}

function html(nonce) {
  return `<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Voxora account setup</title>
<body><h1>Voxora — one-time account setup</h1>
<p>This page runs only on your computer. Enter values from your own signed-in browser session.
Treat them like a password; never share them or paste them into a public issue.</p>
<form action="/${nonce}" method="post" autocomplete="off">
<p><label>Device ID (value of the <code>oai-did</code> cookie)<br>
<input name="deviceId" required size="42" spellcheck="false" autocomplete="off"></label></p>
<p><label>ChatGPT session cookie entries, one <code>name=value</code> per line<br>
<textarea name="cookies" rows="5" cols="65" required spellcheck="false"
placeholder="__Secure-next-auth.session-token=VALUE"></textarea></label></p>
<button type="submit">Transfer to Watch</button></form>
<p>Close this tab after transfer. No session file is created on the computer.</p></body></html>`;
}

function reply(res, code, message, type = 'text/plain; charset=utf-8') {
  res.writeHead(code, {
    'Content-Type': type,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; form-action 'self'; frame-ancestors 'none'",
  });
  res.end(message);
}

async function main() {
  const opts = options(process.argv.slice(2));
  if (!opts) { usage(); return; }
  const serial = selectDevice(opts.adb, opts.serial);
  const nonce = randomBytes(24).toString('hex');
  let used = false;
  let server;
  server = http.createServer(async (req, res) => {
    const port = server.address().port;
    const origin = `http://127.0.0.1:${port}`;
    if (req.headers.host !== `127.0.0.1:${port}`) {
      reply(res, 403, 'Forbidden'); return;
    }
    if (req.method === 'GET' && req.url === '/') {
      reply(res, 200, html(nonce), 'text/html; charset=utf-8'); return;
    }
    if (req.method !== 'POST' || req.url !== `/${nonce}` ||
        req.headers.origin !== origin || used ||
        !req.headers['content-type']?.startsWith('application/x-www-form-urlencoded')) {
      reply(res, 403, 'Forbidden'); return;
    }
    used = true;
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > MAX_POST_BYTES) throw Error('Form too large.');
        chunks.push(chunk);
      }
      const form = new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
      const payload = provisionPayload(form);
      await transfer(opts.adb, serial, payload);
      reply(res, 200, '<h1>Transfer complete</h1><p>Open Voxora on the Watch and use an online feature to finish importing the session.</p>', 'text/html; charset=utf-8');
      console.log('One-time transfer complete. No session data was logged or saved on the PC.');
    } catch {
      reply(res, 500, 'Setup failed. Restart the helper and check the input and ADB connection.');
      console.error('Setup failed; no session values were printed.');
    } finally {
      setTimeout(() => server.close(), 500).unref();
    }
  });
  server.listen(0, '127.0.0.1', () => {
    console.log('Watch: ' + serial);
    console.log('Open http://127.0.0.1:' + server.address().port + '/ in a browser on this computer.');
    console.log('The helper closes automatically after 15 minutes.');
  });
  setTimeout(() => server.close(), TTL_MS).unref();
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
