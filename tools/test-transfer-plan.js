// The Edge page's transfer engine: chunk planner, PBB1 batches, resumable parallel
// uploads with retry through the status route, and the legacy fallback.
// Run from the repository root: node --test tools/test-transfer-plan.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('app/src/main/assets/www/index.html', 'utf8');
const begin = html.indexOf('  /* Transfer engine:');
const end = html.indexOf('  /* End transfer engine */');
assert.ok(begin > 0 && end > begin, 'transfer engine section is in the page');
const code = html.slice(begin, end);
const MIB = 1048576;

function load(fetchImpl) {
  const context = vm.createContext({
    fetch: fetchImpl || (() => { throw new Error('no network'); }),
    endpoint: path => path + (path.includes('?') ? '&' : '?') + 'code=123456',
    Blob, TextEncoder, DataView, Uint8Array, Promise, setTimeout: (fn) => setTimeout(fn, 0), Date, Math, JSON, Number, Error, Array,
  });
  vm.runInContext(code + `
this.api = {pbUploadConnections, pbChunkSize, pbPlanChunks, pbFirstMissing, pbPlanSend, pbBatchHeader, pbBatchBlob,
  pbSpeedMeter, pbProgressText, pbUploadChunked, pbSendAll, pbCanSaveRanged, PbLegacyPeer,
  PB_MIN_CHUNK, PB_MAX_CHUNK, PB_SMALL_FILE, PB_BIG_DOWNLOAD};`, context);
  return context.api;
}

// A Blob that is cheap at any size: slice() only records the range.
function fakeFile(name, size, extra = {}) {
  return {name, size, lastModified: 1700000000000, ...extra,
    slice(a, b) { return {range: [a, b], size: b - a}; }};
}

test('connections and chunk sizes stay inside 2..4 PUTs and 4..16 MiB', () => {
  const p = load();
  assert.equal(p.pbUploadConnections(1 * MIB), 1);
  assert.equal(p.pbUploadConnections(100 * MIB), 2);
  assert.equal(p.pbUploadConnections(600 * MIB), 3);
  assert.equal(p.pbUploadConnections(4 * 1024 * MIB), 4);
  for (const size of [1, 5 * MIB, 100 * MIB, 4096 * MIB, 50 * 1024 * MIB]) {
    const chunk = p.pbChunkSize(size, p.pbUploadConnections(size));
    assert.ok(chunk >= 4 * MIB && chunk <= 16 * MIB, size + ' -> ' + chunk);
    assert.equal(chunk % MIB, 0);
  }
  assert.equal(p.pbChunkSize(4096 * MIB, 4), 16 * MIB);
  assert.equal(p.pbChunkSize(100 * MIB, 2), 6 * MIB);
});

test('the planner covers exactly the missing ranges, in order', () => {
  const p = load();
  const plan = p.pbPlanChunks([[0, 10], [20, 25], [30, 30]], 4);
  assert.deepEqual(JSON.parse(JSON.stringify(plan)), [[0, 4], [4, 8], [8, 10], [20, 24], [24, 25]]);
  assert.deepEqual(JSON.parse(JSON.stringify(p.pbPlanChunks([], 4))), []);
  assert.throws(() => p.pbPlanChunks([[0, 1]], 0));
  const big = p.pbPlanChunks([[0, 4096 * MIB + 7]], 16 * MIB);
  assert.equal(big.length, 257);
  assert.equal(big.reduce((s, [a, b]) => s + b - a, 0), 4096 * MIB + 7);
  for (let i = 1; i < big.length; i++) assert.equal(big[i][0], big[i - 1][1]);
});

test('firstMissing finds where a broken part has to continue', () => {
  const p = load();
  assert.equal(p.pbFirstMissing([[5, 10]], 0, 8), 5);
  assert.equal(p.pbFirstMissing([[0, 3], [12, 20]], 4, 10), 10);
  assert.equal(p.pbFirstMissing([[0, 20]], 4, 10), 4);
  assert.equal(p.pbFirstMissing([], 4, 10), 10);
});

test('small files are batched (<= 2 MiB), big ones go alone, folders kept', () => {
  const p = load();
  const items = [
    {file: fakeFile('a.jpg', 1000), path: 'Trip/a.jpg'},
    {file: fakeFile('b.mkv', 50 * MIB), path: 'Trip/b.mkv'},
    {file: fakeFile('c.txt', 2 * MIB), path: 'Trip/sub/c.txt'},
    {file: fakeFile('d.txt', 2 * MIB + 1), path: 'd.txt'},
  ];
  const plan = p.pbPlanSend(items);
  assert.deepEqual(JSON.parse(JSON.stringify(plan.batches.map(b => b.map(i => i.path)))), [['Trip/a.jpg', 'Trip/sub/c.txt']]);
  assert.deepEqual(JSON.parse(JSON.stringify(plan.large.map(i => i.path))), ['Trip/b.mkv', 'd.txt']);
  const many = Array.from({length: 2500}, (_, i) => ({file: fakeFile('f' + i, 1024), path: 'set/f' + i}));
  const split = p.pbPlanSend(many);
  assert.equal(split.batches.length, 3);
  assert.equal(split.batches.flat().length, 2500);
});

test('a PBB1 batch matches BatchStream.java byte for byte', async () => {
  const p = load();
  const blob = p.pbBatchBlob([
    {path: 'Trip/a.txt', file: new Blob(['alpha'])},
    {path: 'é.txt', file: new Blob([''])},
  ]);
  const bytes = Buffer.from(await blob.arrayBuffer());
  const expected = Buffer.concat([
    Buffer.from('PBB1'),
    Buffer.from([0, 10]), Buffer.from('Trip/a.txt'), Buffer.from([0, 0, 0, 0, 0, 0, 0, 5]), Buffer.from('alpha'),
    Buffer.from([0, 6]), Buffer.from('é.txt'), Buffer.alloc(8),
    Buffer.from([0, 0]),
  ]);
  assert.deepEqual(bytes, expected);
  const head = p.pbBatchHeader('x', 5 * 1024 * MIB);
  assert.deepEqual([...head.slice(3)], [0, 0, 0, 1, 64, 0, 0, 0]);
  assert.throws(() => p.pbBatchHeader('', 1));
});

test('speed meter reports MB/s', () => {
  let now = 0;
  const p = load();
  const meter = p.pbSpeedMeter(() => now);
  meter.add(0); now = 1000; meter.add(25e6); now = 2000; meter.add(50e6);
  assert.equal(meter.mbps(), 25);
  assert.equal(p.pbProgressText('a.bin', 50, 100, 25), 'a.bin · 50% · 25.0 MB/s');
  assert.equal(p.pbCanSaveRanged(31 * MIB), false);
});

function json(status, body) {
  return {status, ok: status < 400, headers: new Map(), json: async () => body};
}

test('a chunked upload sends every missing range in parallel, retries a broken part via the status route, then finishes', async () => {
  const size = 40 * MIB;
  const received = [[0, 4 * MIB]]; // resumed: the first 4 MiB already there
  const calls = [];
  let failedOnce = false, inFlight = 0, maxInFlight = 0;
  const fetchImpl = async (url, init = {}) => {
    const method = init.method || 'GET';
    calls.push(method + ' ' + url);
    if (url.startsWith('/api/upload/start')) {
      const body = JSON.parse(init.body);
      assert.equal(body.relPath, 'Trip/big.bin');
      return json(200, {id: 'f'.repeat(32), missing: [[4 * MIB, size]]});
    }
    if (method === 'PUT') {
      const offset = Number(/offset=(\d+)/.exec(url)[1]);
      inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise(r => setTimeout(r, 1));
      inFlight--;
      const [a, b] = init.body.range;
      assert.equal(a, offset);
      if (!failedOnce && a === 16 * MIB) {
        failedOnce = true;
        received.push([a, a + MIB]); // 1 MiB arrived before the connection dropped
        throw new Error('connection reset');
      }
      received.push([a, b]);
      return json(200, {});
    }
    if (method === 'GET') {
      // status: report the gaps the "server" still has
      const sorted = received.slice().sort((x, y) => x[0] - y[0]);
      const missing = []; let at = 0;
      for (const [a, b] of sorted) { if (a > at) missing.push([at, a]); at = Math.max(at, b); }
      if (at < size) missing.push([at, size]);
      return json(200, {missing});
    }
    if (url.includes('/finish')) return json(201, {id: 'Trip/big.bin', name: 'Trip/big.bin', bytes: size});
    throw new Error('unexpected ' + url);
  };
  const p = load(fetchImpl);
  const progress = [];
  const done = await p.pbUploadChunked(fakeFile('big.bin', size), 'Trip/big.bin', d => progress.push(d));
  assert.equal(done.bytes, size);
  assert.equal(maxInFlight, 2, 'two parallel PUTs for a 40 MiB file');
  const resent = calls.filter(c => c.startsWith('PUT') && c.includes('offset=' + 17 * MIB));
  assert.equal(resent.length, 1, 'only the missing rest of the broken part is sent again');
  assert.ok(calls.some(c => c.startsWith('GET /api/upload/' + 'f'.repeat(32))), 'asked the status route');
  assert.equal(progress[progress.length - 1], size);
  assert.ok(calls[calls.length - 1].startsWith('POST /api/upload/' + 'f'.repeat(32) + '/finish'));
  // Every byte of the file is covered.
  const merged = received.sort((x, y) => x[0] - y[0]).reduce((at, [a, b]) => { assert.ok(a <= at); return Math.max(at, b); }, 0);
  assert.equal(merged, size);
});

test('an older phone (404 on start and batch) gets one legacy POST per file', async () => {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    calls.push((init.method || 'GET') + ' ' + url.split('?')[0]);
    if (url.startsWith('/api/upload/')) return json(404, {});
    if (url.startsWith('/api/upload?name=')) return json(201, {ok: true});
    throw new Error('unexpected ' + url);
  };
  const p = load(fetchImpl);
  const items = [
    {file: new Blob(['a']), path: 'x/a.txt'}, {file: new Blob(['b']), path: 'x/b.txt'},
  ];
  items[0].file.name = 'a.txt'; items[1].file.name = 'b.txt';
  const reports = [];
  const result = await p.pbSendAll(items, text => reports.push(text));
  assert.equal(result.legacy, true);
  assert.equal(result.files, 2);
  assert.deepEqual(calls, ['POST /api/upload/batch', 'POST /api/upload', 'POST /api/upload']);
});
