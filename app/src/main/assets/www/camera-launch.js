(() => {
  const button = document.getElementById('launchCamera');
  const status = document.getElementById('laptopCameraStatus');
  if (!button || !status) return;
  const helperOrigin = 'http://127.0.0.1:__PB_HELPER_PORT__';
  const methods = {status: 'GET', offer: 'POST', stop: 'POST'};
  let popup;
  let port;
  let timeout;

  button.addEventListener('click', () => {
    if (document.getElementById('offlineWorkspace')) { openLocalSetup('camera', new URL(helperOrigin).port); return; }
    if (popup && !popup.closed && port) { popup.focus(); return; }
    clearTimeout(timeout);
    if (port) port.close();
    port = null;
    popup = window.open(helperOrigin + '/capture-camera#' + encodeURIComponent(location.origin), 'PocketBridgeCamera');
    if (!popup) { status.textContent = 'Allow the camera window to open, then try again.'; return; }
    status.textContent = 'Opening the camera window on this laptop…';
    timeout = setTimeout(() => {
      status.textContent = 'Start the matching PocketBridge Windows helper on this laptop, then click Open camera again.';
    }, 6500);
  });

  window.addEventListener('message', event => {
    if (!popup || event.source !== popup || event.origin !== helperOrigin || event.data !== 'pb-camera-ready') return;
    clearTimeout(timeout);
    if (port) port.close();
    const channel = new MessageChannel();
    port = channel.port1;
    port.onmessage = async ({data}) => {
      if (!data || !Number.isInteger(data.id) || !Object.hasOwn(methods, data.action)) return;
      const {id, action} = data;
      const options = {method: methods[action], cache: 'no-store', signal: AbortSignal.timeout(30000)};
      if (action === 'offer') {
        if (typeof data.sdp !== 'string' || data.sdp.length > 200000) return;
        options.headers = {'Content-Type': 'application/json'};
        options.body = JSON.stringify({sdp: data.sdp});
      }
      try {
        const response = await fetch(endpoint('/api/camera/' + action), options);
        const body = await response.json();
        channel.port1.postMessage({id, ok: response.ok, body});
      } catch {
        channel.port1.postMessage({id, ok: false, body: {error: 'The phone did not answer. Check the hotspot and keep PocketBridge sharing.'}});
      }
    };
    popup.postMessage('pb-camera-paired', helperOrigin, [channel.port2]);
    status.textContent = 'Camera window opened. Start the session on the phone, then approve the laptop camera in Edge.';
  });
  window.addEventListener('pagehide', () => {
    clearTimeout(timeout);
    if (port) { port.postMessage({closed: true}); port.close(); }
  });
})();
