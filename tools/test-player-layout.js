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

const common = {viewportWidth:1920,viewportHeight:1080,topHeight:54,controlHeight:64,mediaWidth:1920,mediaHeight:1080,fullscreen:true,boxWidth:1800};
assertSize('original fullscreen ratio', calculate({...common,selection:'original'}), 1681, 945);
assertSize('21:9 fullscreen ratio', calculate({...common,selection:'21-9'}), 1920, 822);
assertSize('portrait fullscreen ratio', calculate({...common,selection:'9-16'}), 532, 945);
assertSize('fill fullscreen viewport', calculate({...common,selection:'fill'}), 1920, 946);
assertSize('stretch fullscreen viewport', calculate({...common,selection:'stretch'}), 1920, 946);

const windowed = calculate({...common,viewportWidth:1280,viewportHeight:800,boxWidth:1000,fullscreen:false,selection:'16-9'});
if (windowed.width > 1000 || Math.abs(windowed.width / windowed.height - 16 / 9) > 0.002) {
  throw new Error(`windowed 16:9 ratio does not fit: ${windowed.width}x${windowed.height}`);
}
process.stdout.write('OK: fullscreen and windowed aspect-ratio layouts fit their available viewports\n');
