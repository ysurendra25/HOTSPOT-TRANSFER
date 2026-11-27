// The Edge page's chat buttons and Download links, checked against ChatActions.java's rules.
// Run from the repository root: node --test tools/test-chat-actions.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('app/src/main/assets/www/index.html', 'utf8');
const code = html.slice(html.indexOf('  function endpoint(path) {'), html.indexOf('  const formatSize = (bytes) => {'));

function load() {
  const context = vm.createContext({hasToken:false, TOKEN:'', code:'123456', document:{}, location:{href:'http://192.168.43.1:43117/'}, URL});
  vm.runInContext(code, context);
  return context;
}

test('video, music and other files get the same buttons as the phone app', () => {
  const page = load();
  assert.deepEqual([...page.chatActionsFor('video/mp4', 'a.mp4')], ['play', 'vlc', 'download']);
  assert.deepEqual([...page.chatActionsFor('application/octet-stream', 'film.MKV')], ['play', 'vlc', 'download']);
  assert.deepEqual([...page.chatActionsFor('audio/mpeg', 'song.mp3')], ['play', 'download']);
  assert.deepEqual([...page.chatActionsFor('', 'track.flac')], ['play', 'download']);
  assert.deepEqual([...page.chatActionsFor('image/jpeg', 'photo.jpg')], ['open', 'download']);
  assert.deepEqual([...page.chatActionsFor('application/pdf', 'doc.pdf')], ['open', 'download']);
  assert.deepEqual([...page.chatActionsFor('', 'notes')], ['open', 'download']);
});

test('download links ask the phone for an attachment and keep the code', () => {
  const page = load();
  assert.equal(page.downloadEndpoint('/api/inbox/abc'), '/api/inbox/abc?download=1&code=123456');
  assert.equal(page.downloadEndpoint('/api/media/video%3A1'), '/api/media/video%3A1?download=1&code=123456');
  assert.equal(page.endpoint('/api/inbox/abc'), '/api/inbox/abc?code=123456');
});
