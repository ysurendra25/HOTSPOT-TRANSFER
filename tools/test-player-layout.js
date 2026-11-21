const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/assets/www/index.html', 'utf8');
const start = source.indexOf('function videoStageDimensions(options) {');
const end = source.indexOf('\n  function fitVideoStage()', start);
if (start < 0 || end < 0) throw new Error('videoStageDimensions was not found in the shipped player page');
const calculate = new Function(`${source.slice(start, end)}\nreturn videoStageDimensions`)();

function assertSize(label, actual, width, height) {
  if (actual.width !== width || actual.height !== height) {
    throw new Error(`${label}: expected ${width}x${height}, got ${actual.width}x${actual.height}`);
  }
}

const common = {viewportWidth:1920,viewportHeight:1080,mediaWidth:1920,mediaHeight:1080};
assertSize('original fullscreen ratio', calculate({...common,selection:'original'}), 1920, 1080);
assertSize('original portrait source', calculate({...common,mediaWidth:360,mediaHeight:640,selection:'original'}), 608, 1080);
assertSize('21:9 fullscreen ratio', calculate({...common,selection:'21-9'}), 1920, 823);
assertSize('portrait fullscreen ratio', calculate({...common,selection:'9-16'}), 608, 1080);
assertSize('IMAX 1.43 fullscreen ratio', calculate({...common,selection:'143-100'}), 1544, 1080);
assertSize('IMAX 1.90 fullscreen ratio', calculate({...common,selection:'190-100'}), 1920, 1011);
for (const selection of ['fill','stretch','zoom-125','zoom-150','zoom-200']) {
  assertSize(selection, calculate({...common,selection}), 1920, 1080);
}
assertSize('smaller Edge window', calculate({...common,viewportWidth:900,viewportHeight:600,selection:'16-9'}), 900, 506);
process.stdout.write('OK: fullscreen and windowed aspect-ratio layouts fit their available viewports\n');
