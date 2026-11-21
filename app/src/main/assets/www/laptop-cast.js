(() => {
  const launch = document.getElementById('shareLaptopScreen');
  const status = document.getElementById('laptopCastStatus');
  const helperOrigin = 'http://127.0.0.1:__PB_HELPER_PORT__';
  let sender, port, publisher = '', nonce, startupTimer, windowTimer;
  async function exchange(action, data = {}, unloading = false) {
    const response = await fetch(endpoint('/api/laptop-cast'), {
      method:'POST', keepalive:unloading, headers:{'Content-Type':'application/json'},
      body:JSON.stringify({action, ...data}), signal:AbortSignal.timeout(6500)
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'The phone did not accept this presentation.');
    return result;
  }
  function disconnect() {
    clearTimeout(startupTimer);
    clearInterval(windowTimer);
    if (publisher) exchange('stop', {publisher}, true).catch(() => {});
    publisher = '';
    if (port) { port.close(); port = null; }
    sender = null;
  }
  function openSender() {
    if (document.getElementById('offlineWorkspace')) { openLocalSetup('screen', new URL(helperOrigin).port); return; }
    if (sender && !sender.closed && port) { sender.focus(); return; }
    disconnect();
    nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
    const url = new URL('/present.html', helperOrigin);
    url.hash = new URLSearchParams({phone:location.origin, nonce}).toString();
    sender = window.open(url.href, '_blank');
    if (!sender) {
      status.textContent = 'Allow the PocketBridge presenter window to open, then try again.';
      return;
    }
    status.textContent = 'Starting the Edge presenter…';
    startupTimer = setTimeout(() => {
      status.textContent = 'Start the matching PocketBridge Windows helper on this laptop, then click Share laptop screen again.';
    }, 4500);
    windowTimer = setInterval(() => {
      if (sender && sender.closed) { disconnect(); status.textContent = 'The presenter window closed.'; }
    }, 800);
  }
  async function handleRequest(channel, data) {
    if (!data || !Number.isInteger(data.id) || channel !== port) return;
    try {
      let result;
      switch (data.action) {
        case 'start':
          if (publisher) throw new Error('Stop the current presentation first.');
          result = await exchange('start');
          if (port !== channel) { await exchange('stop', {publisher:result.publisher}); return; }
          publisher = result.publisher;
          result = {};
          status.textContent = 'Sharing your selection. On the phone, open Screen and tap Watch Edge screen.';
          break;
        case 'poll':
          result = await exchange('poll', {publisher, previous:data.previous});
          break;
        case 'answer':
          result = await exchange('answer', {publisher, viewer:data.viewer, sdp:data.sdp});
          break;
        case 'stop':
          if (publisher) await exchange('stop', {publisher});
          publisher = '';
          result = {};
          status.textContent = 'Laptop screen sharing stopped.';
          break;
        default: throw new Error('Unknown presentation request.');
      }
      channel.postMessage({id:data.id,result});
    } catch (error) { channel.postMessage({id:data.id,error:error.message}); }
  }
  window.addEventListener('message', event => {
    if (!sender || event.source !== sender || event.origin !== helperOrigin ||
        event.data?.type !== 'pb-present-ready' || event.data.nonce !== nonce || port) return;
    clearTimeout(startupTimer);
    const pair = new MessageChannel();
    port = pair.port1;
    const channel = port;
    channel.onmessage = e => handleRequest(channel, e.data);
    sender.postMessage({type:'pb-present-port', nonce}, helperOrigin, [pair.port2]);
    status.textContent = 'In the presenter window, choose the screen or window to share.';
  });
  window.addEventListener('pagehide', disconnect);
  launch.addEventListener('click', openSender);
})();
