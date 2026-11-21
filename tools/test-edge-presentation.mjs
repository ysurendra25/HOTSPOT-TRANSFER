// Run manually with a local Edge/Chromium binary. The laptop source is a changing
// canvas. This exercises the production peers, opener bridge, and decoded frames
// without asking for a person's real screen. Android tests cover the HTTP server.
import assert from 'node:assert/strict';
import http from 'node:http';
import {readFile, mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {checkPlayer} from './test-edge-player.mjs';

const sleep = ms => new Promise(done => setTimeout(done, ms));
const root = new URL('../', import.meta.url);
const source = name => readFile(new URL(name, root), 'utf8');
const app = 'app/src/main/assets/www/';
const hotspotPage = await source(app + 'index.html');
const presentPage = await source('tools/win-control/upstream-v0.1.0/web-remote-desktop-0.1.0/web/present.html');
const browserJS = await source(app + 'laptop-cast.js');
const cameraJS = await source(app + 'camera-launch.js');
const viewerPage = await source(app + 'watch-laptop.html');
const clips = new Map();
for (const name of ['landscape','portrait']) clips.set(name, await readFile(new URL('tools/fixtures/' + name + '.webm', root)));

async function serve(handler) {
  const server = http.createServer(async (req, res) => {
    try { await handler(req, res); } catch (error) { res.writeHead(500); res.end(error.message); }
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server;
}
let viewer = '', offer = '', answer = '', active = false;
function exchange(data) {
  switch (data.action) {
    case 'start': active = true; return {publisher:'sender'};
    case 'poll': return {viewer,offer:viewer !== data.previous ? offer : ''};
    case 'answer': answer = data.sdp; return {};
    case 'stop': active = false; viewer = offer = answer = ''; return {};
    case 'view': return {session:active ? 'session' : '', viewer, answer};
    case 'offer': offer = data.sdp; viewer = 'viewer'; return {session:'session', viewer};
    case 'leave': viewer = offer = answer = ''; return {};
    default: throw new Error('unexpected action');
  }
}
function html(res, body) { res.setHeader('Content-Type','text/html; charset=utf-8'); res.end(body); }
const helper = await serve((req, res) => html(res, presentPage));
const helperOrigin = 'http://127.0.0.1:' + helper.address().port;
// A loopback-only HTTP proxy represents the phone's hotspot IP. This exercises
// a genuinely insecure browser origin without binding a test server to Wi-Fi.
const hotspotOrigin = 'http://192.168.49.1:43117';
const phone = await serve(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.hostname !== '127.0.0.1' && url.origin !== hotspotOrigin) {
    res.writeHead(404); res.end(); return;
  }
  const path = url.pathname;
  if (path === '/laptop-cast.js' || path === '/camera-launch.js') {
    res.setHeader('Content-Type','text/javascript');
    res.end((path === '/laptop-cast.js' ? browserJS : cameraJS).replace('__PB_HELPER_PORT__', String(helper.address().port)));
  } else if ((path.startsWith('/api/') || path === '/watch-laptop.html') && url.searchParams.get('code') !== '123456') {
    res.writeHead(403); res.end();
  } else if (path === '/api/laptop-cast') {
    let body = '';
    for await (const chunk of req) body += chunk;
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify(exchange(JSON.parse(body))));
  } else if (path === '/watch-laptop.html') {
    html(res, viewerPage);
  } else if (path === '/api/files') {
    res.setHeader('Content-Type','application/json');
    const files = [...clips].map(([id, bytes]) => ({id, name:id + '.webm', kind:'video', size:bytes.length, url:'/api/media/' + id}));
    files.push({id:'unsupported', name:'Unsupported codec', kind:'video', url:'/api/media/unsupported'});
    res.end(JSON.stringify({files}));
  } else if (path === '/api/messages') {
    res.setHeader('Content-Type','application/json');
    res.end(JSON.stringify({messages:[{time:1, from:'phone', attachment:{id:'landscape', name:'Clip.webm', mime:'video/webm'}}]}));
  } else if (path === '/api/media/unsupported') {
    res.setHeader('Content-Type','video/mp4'); res.end('Unsupported container fixture');
  } else if (path === '/api/video/compatible/unsupported' || path === '/api/inbox/landscape' ||
             (path.startsWith('/api/media/') && clips.has(path.slice('/api/media/'.length)))) {
    const bytes = clips.get(path.split('/').pop()) || clips.get('landscape');
    const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
    const start = range ? Number(range[1]) : 0;
    const end = range && range[2] ? Math.min(Number(range[2]),bytes.length-1) : bytes.length-1;
    res.setHeader('Accept-Ranges','bytes'); res.setHeader('Content-Type','video/webm');
    if (range) { res.statusCode=206; res.setHeader('Content-Range',`bytes ${start}-${end}/${bytes.length}`); }
    res.setHeader('Content-Length',end-start+1); res.end(bytes.subarray(start,end+1));
  } else if (path.startsWith('/api/')) {
    res.setHeader('Content-Type','application/json'); res.end('{}');
  } else {
    html(res, hotspotPage);
  }
});
const origin = 'http://127.0.0.1:' + phone.address().port;
const profile = await mkdtemp(join(tmpdir(), 'pocketbridge-presentation-'));
const executable = process.env.PB_EDGE_BIN || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const edge = spawn(executable, ['--headless=new','--no-first-run','--disable-extensions',
  '--proxy-server=' + origin, '--proxy-bypass-list=localhost;127.0.0.1',
  '--disable-background-timer-throttling','--disable-renderer-backgrounding','--disable-backgrounding-occluded-windows',
  '--remote-debugging-port=0','--user-data-dir=' + profile,'about:blank'],
  {windowsHide:true, stdio:'ignore'});
const errors = [];
async function waitFor(fn, ms=16000) {
  const stop = Date.now() + ms;
  let last;
  while (Date.now() < stop) {
    try { const result = await fn(); if (result) return result; } catch (error) { last = error; }
    await sleep(120);
  }
  throw new Error('Timed out waiting for browser state. ' + (last?.message || '') + errors.join('\n'));
}
const sockets = [];
async function client(target, enableRuntime = true) {
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  sockets.push(ws);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  const pending = new Map();
  let counter = 0;
  ws.onmessage = event => {
    const msg = JSON.parse(event.data);
    if (msg.method === 'Runtime.exceptionThrown') errors.push(JSON.stringify(msg.params.exceptionDetails));
    if (msg.id) { const cb = pending.get(msg.id); if (cb) { pending.delete(msg.id); cb(msg); } }
  };
  function command(method, params = {}) {
    const id = ++counter;
    return new Promise((resolve, reject) => {
      pending.set(id, msg => msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result));
      ws.send(JSON.stringify({id, method, params}));
    });
  }
  async function evaluate(expression) {
    const reply = await command('Runtime.evaluate', {expression, userGesture:true, awaitPromise:true, returnByValue:true});
    if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.exception?.description || reply.exceptionDetails.text);
    return reply.result.value;
  }
  if (enableRuntime) await command('Runtime.enable');
  return {command, evaluate};
}
let browser;
try {
  const port = await waitFor(async () => (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split(/\r?\n/)[0]);
  const debug = 'http://127.0.0.1:' + port;
  const list = async () => (await fetch(debug + '/json/list')).json();
  const create = async url => client(await (await fetch(debug + '/json/new?' + encodeURIComponent(url), {method:'PUT'})).json());
  const page = await create(hotspotOrigin);
  await waitFor(() => page.evaluate('document.readyState === "complete" && !!document.querySelector("#unlockBtn")'));
  assert.equal(await page.evaluate('window.isSecureContext'), false);
  await page.evaluate(`document.querySelector('#codeInput').value = '123456'; document.querySelector('#unlockBtn').click()`);
  await waitFor(() => page.evaluate("document.querySelector('#app').style.display === 'block'"));
  await checkPlayer(page, waitFor);
  await page.evaluate(`document.querySelector('#tabbar [data-tab="screen"]').click()`);
  assert.equal(await page.evaluate("document.querySelector('#shareLaptopScreen').checkVisibility()"), true);
  await page.evaluate("document.querySelector('#shareLaptopScreen').click()");
  const popup = await client(await waitFor(async () => (await list()).find(x => x.url.startsWith(helperOrigin))));
  await waitFor(() => popup.evaluate('document.querySelector("#start")?.disabled === false'));
  await popup.evaluate(`navigator.mediaDevices.getDisplayMedia = async () => { throw new DOMException('Cancelled', 'NotAllowedError'); };
    document.querySelector('#start').click();`);
  await waitFor(() => popup.evaluate("!document.querySelector('#start').disabled && document.querySelector('#status').textContent.includes('No screen was shared')"));
  assert.equal(active, false);
  // Only this isolated browser tab supplies a synthetic test source to the sender.
  await popup.evaluate(`navigator.mediaDevices.getDisplayMedia = async () => {
    const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 180;
    const ctx = canvas.getContext('2d'); let frame = 0;
    const draw = () => { ctx.fillStyle = frame++ % 2 ? 'rgb(255,0,0)' : 'rgb(0,0,255)'; ctx.fillRect(0,0,320,180); };
    draw(); window.testTimer = setInterval(draw, 120); return canvas.captureStream(12); };
    document.querySelector('#start').click();`);
  const view = await create(origin + '/watch-laptop.html?code=123456');
  await waitFor(() => view.evaluate('document.querySelector("video")?.videoWidth === 320'), 36000);
  const decoded = await view.evaluate(`(async () => {
    const v=document.querySelector('video'), c=document.createElement('canvas'), ctx=c.getContext('2d');
    const colours=new Set();
    for(let i=0;i<16;i++){ctx.drawImage(v,0,0,1,1);const pix=ctx.getImageData(0,0,1,1).data;
      colours.add(pix[0]>pix[2]?'red':'blue'); await new Promise(r=>setTimeout(r,90));}
    return [...colours].sort();})()`);
  assert.deepEqual(decoded, ['blue','red']);
  await popup.evaluate('document.querySelector("#stop").click()');
  await waitFor(() => view.evaluate('document.querySelector("video").srcObject === null'));
  assert.equal(active, false);
  assert.deepEqual(errors, []);
  console.log('Edge: approved synthetic presenter, decoded changing 320×180 frames on receiver and cleared on stop.');
  browser = await client(await (await fetch(debug + '/json/version')).json(), false);
} finally {
  if (browser) browser.command('Browser.close').catch(() => {});
  await sleep(400);
  sockets.forEach(ws => ws.close());
  edge.kill();
  phone.closeAllConnections(); phone.close(); helper.closeAllConnections(); helper.close();
  await rm(profile, {force:true,recursive:true,maxRetries:8,retryDelay:250}).catch(() => {});
}
