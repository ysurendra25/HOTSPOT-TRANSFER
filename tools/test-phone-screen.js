// Exercise the shipped viewer as an impatient user who hits Watch before Android approves.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('app/src/main/assets/www/index.html', 'utf8');
const viewerCode = html.slice(html.indexOf('let screenReader = null;'), html.indexOf("document.getElementById('watchButton').addEventListener"));

function harness(status) {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {classList:{add(){},remove(){}}, style:{}, pause(){}, textContent:''});
    return nodes.get(id);
  }
  const requests = [];
  let timer;
  const context = vm.createContext({
    window:{}, document:{getElementById:node}, crypto:require('node:crypto').webcrypto,
    AbortController, AbortSignal, Date,
    fetch: async path => {
      requests.push(path);
      return {ok:true, json:async () => status()};
    },
    setTimeout: callback => {timer = callback;}, clearTimeout:()=>{}, endpoint:path=>path,
  });
  vm.runInContext('let screenWanted = false, screenFrameTimer = 0;\n' + viewerCode, context);
  const calls = [];
  context.readScreenStream = () => calls.push('jpeg');
  context.watchScreenWebRtc = () => calls.push('rtc');
  return {context, node, calls, requests, tick:()=>timer()};
}

test('Watch waits through consent and startup, then starts JPEG and WebRTC', async () => {
  const states = [{ready:false}, {starting:true}, {ready:true}];
  const h = harness(() => states.shift());
  const started = h.context.watchScreen();
  await new Promise(setImmediate);
  assert.match(h.node('screenState').textContent, /approve/i);
  assert.deepEqual(h.calls, []);
  h.tick();
  await new Promise(setImmediate);
  assert.match(h.node('screenState').textContent, /preparing/i);
  h.tick();
  await started;
  assert.deepEqual(h.calls, ['jpeg', 'rtc']);
  assert.equal(h.requests.length, 3);
});

test('Closing while waiting never starts capture streams', async () => {
  const h = harness(() => ({ready:false}));
  const started = h.context.watchScreen();
  await new Promise(setImmediate);
  h.context.stopWatching();
  h.tick();
  await started;
  assert.deepEqual(h.calls, []);
  assert.equal(h.node('watchButton').disabled, false);
});

test('Phone reports capture error and lets Edge retry immediately', async () => {
  const h = harness(() => ({ready:false,error:'Capture stopped by Android'}));
  await h.context.watchScreen();
  assert.equal(h.node('screenState').textContent, 'Capture stopped by Android');
  assert.equal(h.node('watchButton').disabled, false);
  assert.deepEqual(h.calls, []);
});
