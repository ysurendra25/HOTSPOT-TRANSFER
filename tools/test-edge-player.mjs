import assert from 'node:assert/strict';

// Run through test-edge-presentation.mjs to exercise the actual locked hotspot page.
export async function checkPlayer(page, waitFor) {
  const run = expression => page.evaluate(expression);
  const click = id => run(`document.getElementById(${JSON.stringify(id)}).click()`);
  const inspect = () => run(`(() => {
    const stage = document.querySelector('#stage'), video = stage.querySelector('video');
    const frame = video.getBoundingClientRect(), bounds = stage.getBoundingClientRect();
    return {vw:innerWidth, vh:innerHeight, left:bounds.left, top:bounds.top, width:bounds.width, height:bounds.height,
      background:getComputedStyle(stage).backgroundColor, x:frame.x, y:frame.y, w:frame.width, h:frame.height,
      fit:getComputedStyle(video).objectFit, selected:document.querySelector('#aspectRatio').value,
      original:video.currentSrc.includes('/api/media/'), modalOnTop:!!document.elementFromPoint(2,innerHeight / 2)?.closest('#modal')};
  })()`);
  async function select(mode) {
    await run(`(() => { const s = document.querySelector('#aspectRatio'); s.value=${JSON.stringify(mode)}; s.dispatchEvent(new Event('change')); })()`);
    return inspect();
  }
  function fullStage(s) {
    assert.deepEqual([s.left,s.top,s.width,s.height],[0,0,s.vw,s.vh]);
    assert.equal(s.background,'rgb(0, 0, 0)');
    assert.ok(s.modalOnTop && s.original);
    assert.ok(Math.abs(s.x + s.w / 2 - s.vw / 2) < 1 && Math.abs(s.y + s.h / 2 - s.vh / 2) < 1);
  }
  await run(`document.querySelector('#tabbar [data-tab="files"]').click()`);
  await waitFor(() => run('!!document.querySelector(\'[data-preview="landscape"]\')'));
  await run(`document.querySelector('[data-preview="landscape"]').click()`);
  const playing = async () => waitFor(() => run("document.querySelector('#stage video')?.currentTime > 0 && !document.querySelector('#stage video').paused"));
  await playing();
  let s = await inspect(); fullStage(s);
  assert.equal(s.selected,'fill'); assert.deepEqual([s.w,s.h],[s.vw,s.vh]);
  s = await select('original'); fullStage(s); assert.ok(Math.abs(s.w/s.h-4/3) < 0.005);
  await click('fullscreen');
  await waitFor(() => run('document.fullscreenElement?.id === "modal"'));
  for (const [mode, ratio] of [['143-100',1.43],['190-100',1.9],['239-100',2.39],['21-9',21/9],['9-16',9/16]]) {
    s = await select(mode); fullStage(s);
    assert.ok(Math.abs(s.w / s.h - ratio) < 0.01); assert.equal(s.fit,'fill');
  }
  s = await select('zoom-125'); fullStage(s); assert.ok(s.w > s.vw && s.h > s.vh);
  s = await select('stretch'); fullStage(s); assert.equal(s.fit,'fill');
  s = await select('fill'); fullStage(s); assert.equal(s.fit,'cover');
  assert.deepEqual([s.w,s.h],[s.vw,s.vh]);
  await run("document.querySelector('#playbackSpeed').value='1.5'; document.querySelector('#playbackSpeed').dispatchEvent(new Event('change'))");
  assert.equal(await run("document.querySelector('#stage video').playbackRate"),1.5);
  await click('fwd10');
  assert.ok(await run("document.querySelector('#stage video').currentTime >= 10"));
  await click('back10');
  assert.ok(await run("document.querySelector('#stage video').currentTime < 5"));
  await waitFor(() => run("getComputedStyle(document.querySelector('#controls')).opacity === '0'"), 6500);
  fullStage(await inspect());
  await page.command('Input.dispatchMouseEvent',{type:'mouseMoved',x:12,y:20});
  await waitFor(() => run("getComputedStyle(document.querySelector('#controls')).opacity === '1'"));
  await click('nextItem');
  await waitFor(() => run("document.querySelector('#stage video')?.videoHeight === 64"));
  await playing();
  assert.equal((await inspect()).selected,'fill');
  s = await select('original'); fullStage(s);
  assert.ok(Math.abs(s.w/s.h-36/64) < 0.005);
  await click('prevItem'); await playing();
  assert.equal(await run("document.querySelector('#stage video').videoWidth"),64);
  await click('closeModal');
  await waitFor(() => run("!document.fullscreenElement && !document.querySelector('#stage video')"));
  await run(`document.querySelector('[data-preview="long-film"]').click()`);
  await playing();
  const landing = await run(`(() => {
    const video = document.querySelector('#stage video'), seek = document.querySelector('#seek');
    video.pause();
    const before = video.currentTime;
    for (const v of [100, 2500, 4500]) { seek.value = v; seek.dispatchEvent(new Event('input')); }
    const previewOnly = video.currentTime === before && document.querySelector('#time').textContent === '1:15:00 / 3:00:00';
    seek.dispatchEvent(new Event('change'));
    return {previewOnly, duration:video.duration, position:video.currentTime};
  })()`);
  assert.ok(landing.previewOnly);
  assert.equal(landing.duration,10800);
  assert.ok(landing.position === 4500);
  await waitFor(() => run("!document.querySelector('#stage video').seeking && document.querySelector('#stage video').readyState >= 2"));
  await click('closeModal');
  console.log('Edge decoded the selected position around 1:15:00 in a three-hour indexed movie.');
  await run(`document.querySelector('[data-preview="unsupported"]').click()`);
  await playing();
  assert.ok(await run("document.querySelector('#stage video').currentSrc.includes('/api/video/compatible/unsupported')"));
  await click('closeModal');
  await run(`document.querySelector('#tabbar [data-tab="chat"]').click()`);
  await waitFor(() => run("!!document.querySelector('.chat-file-actions button')"));
  await run("document.querySelector('.chat-file-actions button').click()");
  await playing();
  await click('closeModal');
  // Inject a one-shot browser policy rejection, then allow the user's manual retry.
  await run(`const originalPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function(){ HTMLMediaElement.prototype.play = originalPlay;
      return Promise.reject(new DOMException('Blocked', 'NotAllowedError')); };
    document.querySelector('.chat-file-actions button').click()`);
  await waitFor(() => run("document.querySelector('#playerStatus').textContent === 'Press Play to start playback.' && !document.querySelector('#playerStatus').hidden"));
  await click('playPause'); await playing(); await click('closeModal');
  console.log('Edge: original landscape and portrait videos decoded, fullscreen covered the viewport, fit/crop/zoom, IMAX, playback, auto-hide and pointer reveal passed.');
}
