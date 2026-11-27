// The Edge page's Library sort: per-category choice in localStorage and the same orders as LibrarySort.java.
// Run from the repository root: node --test tools/test-library-sort.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('app/src/main/assets/www/index.html', 'utf8');
const code = html.slice(html.indexOf('  /* Library sort: one choice per category'), html.indexOf('  const formatSize = (bytes) => {'));

function load(store = new Map()) {
  const localStorage = {getItem: k => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v))};
  const context = vm.createContext({localStorage, Intl});
  vm.runInContext(code + '\nthis.api = {sortFor, rememberSort, sortRows, SORTS};', context);
  return context.api;
}

test('each category remembers its own sort, newest first by default', () => {
  const store = new Map();
  const page = load(store);
  assert.equal(page.sortFor('videos'), 'date_desc');
  page.rememberSort('videos', 'name_asc');
  page.rememberSort('audio', 'size_desc');
  page.rememberSort('images', 'bogus');
  assert.equal(page.sortFor('videos'), 'name_asc');
  assert.equal(page.sortFor('audio'), 'size_desc');
  assert.equal(page.sortFor('images'), 'date_desc');
  assert.equal(load(store).sortFor('videos'), 'name_asc', 'kept across reloads');
  assert.deepEqual([...page.SORTS], ['name_asc', 'name_desc', 'date_desc', 'date_asc', 'size_desc', 'size_asc']);
});

test('names sort naturally and case-insensitively; ties stay stable', () => {
  const page = load();
  const rows = [
    {id: 'v:3', name: 'Episode 10.mkv', size: 300, modified: 1},
    {id: 'v:1', name: 'episode 2.mkv', size: 100, modified: 3},
    {id: 'v:2', name: 'Trailer.mp4', size: 200, modified: 2},
    {id: 'v:4', name: 'trailer.mp4', size: 200, modified: 2},
  ];
  const names = value => page.sortRows(rows, value).map(r => r.id).join(',');
  assert.equal(names('name_asc'), 'v:1,v:3,v:2,v:4');
  assert.equal(names('name_desc'), 'v:2,v:4,v:3,v:1');
  assert.equal(names('date_desc'), 'v:1,v:4,v:2,v:3');
  assert.equal(names('date_asc'), 'v:3,v:2,v:4,v:1');
  assert.equal(names('size_desc'), 'v:3,v:2,v:4,v:1');
  assert.equal(names('size_asc'), 'v:1,v:2,v:4,v:3');
  assert.equal(names('nonsense'), names('date_desc'));
});

test('every category tab and sort control is on the page', () => {
  for (const kind of ['all', 'videos', 'audio', 'images', 'documents', 'apps', 'other']) assert.ok(html.includes(`data-kind="${kind}"`), kind);
  for (const sort of ['name_asc', 'name_desc', 'date_desc', 'date_asc', 'size_desc', 'size_asc']) {
    assert.ok(html.includes(`<option value="${sort}"`), 'select ' + sort);
    assert.ok(html.includes(`data-sort="${sort}"`), 'button ' + sort);
  }
  assert.ok(html.includes("'&sort=' + encodeURIComponent(sortFor(currentKind))"));
});
