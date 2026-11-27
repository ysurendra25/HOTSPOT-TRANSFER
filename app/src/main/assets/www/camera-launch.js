(() => {
  const button = document.getElementById('launchCamera');
  const status = document.getElementById('laptopCameraStatus');
  if (!button || !status) return;
  const section = document.getElementById('cameraSection');
  const promptBox = document.getElementById('cameraPrompt');
  const promptText = document.getElementById('cameraPromptText');
  const acceptButton = document.getElementById('cameraAccept');
  const declineButton = document.getElementById('cameraDecline');
  const stopButton = document.getElementById('cameraStop');
  const liveBadge = document.getElementById('cameraLive');
  const helperOrigin = 'http://127.0.0.1:__PB_HELPER_PORT__';
  const methods = {status: 'GET', request: 'POST', respond: 'POST', offer: 'POST', stop: 'POST'};
  const ACTIVE = ['requested', 'accepted', 'live'];
  let popup, port, timeout, pendingCommand = null, state = {}, pollTimer = 0;

  async function call(action, data) {
    const options = {method: methods[action], cache: 'no-store', signal: AbortSignal.timeout(30000)};
    if (methods[action] === 'POST') {
      options.headers = {'Content-Type': 'application/json'};
      options.body = JSON.stringify(data || {});
    }
    const response = await fetch(endpoint('/api/camera/' + action), options);
    const body = await response.json().catch(() => ({}));
    return {ok: response.ok, body};
  }

  /** The phone's call state drives the prompt, the live badge and the buttons. */
  function render(next) {
    if (!next || typeof next.state !== 'string') return;
    state = next;
    const incoming = next.state === 'requested' && next.requester === 'phone';
    const active = ACTIVE.includes(next.state);
    if (promptBox) promptBox.hidden = !incoming;
    if (incoming && promptText) promptText.textContent = 'The phone wants a two-way camera call. Nothing is shared until you accept. ' +
      Math.ceil((next.expiresInMs || 0) / 1000) + ' s left.';
    if (liveBadge) liveBadge.hidden = !(next.state === 'live' || next.live);
    if (stopButton) stopButton.hidden = !active || incoming;
    button.hidden = active;
    if (incoming) status.textContent = 'The phone is asking for a camera call. Accept opens the laptop camera window.';
    else if (next.state === 'requested') status.textContent = 'Waiting for the phone to accept (' + Math.ceil((next.expiresInMs || 0) / 1000) + ' s).';
    else if (next.state === 'accepted') status.textContent = 'Both sides accepted. Connecting the cameras…';
    else if (next.state === 'live') status.textContent = 'Live. Either side can end the call.';
    else if (next.reason) status.textContent = next.reason;
  }

  async function poll() {
    clearTimeout(pollTimer);
    const visible = section && !section.hidden && document.visibilityState === 'visible';
    if (visible || ACTIVE.includes(state.state) || (popup && !popup.closed)) {
      try { const {ok, body} = await call('status'); if (ok) render(body); } catch { /* keep the last state */ }
    }
    pollTimer = setTimeout(poll, ACTIVE.includes(state.state) ? 1000 : 2000);
  }

  /** Opens (or focuses) the helper's camera window; it does the camera work in a secure localhost page. */
  function openWindow(command) {
    if (document.getElementById('offlineWorkspace')) { openLocalSetup('camera', new URL(helperOrigin).port); return; }
    pendingCommand = command;
    if (popup && !popup.closed && port) { popup.focus(); flush(); return; }
    clearTimeout(timeout);
    if (port) port.close();
    port = null;
    popup = window.open(helperOrigin + '/capture-camera#' + encodeURIComponent(location.origin), 'PocketBridgeCamera');
    if (!popup) { status.textContent = 'Allow the camera window to open, then try again.'; return; }
    status.textContent = 'Opening the camera window on this laptop…';
    timeout = setTimeout(() => {
      status.textContent = 'Start the matching PocketBridge Windows helper on this laptop, then try again. On a phone, use the PocketBridge app: Camera → Call another phone.';
      if (pendingCommand && pendingCommand.command === 'accept') call('respond', {id: pendingCommand.callId, accept: false}).then(r => render(r.body)).catch(() => {});
      pendingCommand = null;
    }, 6500);
  }

  function flush() {
    if (port && pendingCommand) { port.postMessage(pendingCommand); pendingCommand = null; }
  }

  button.addEventListener('click', () => openWindow({command: 'request'}));
  if (acceptButton) acceptButton.addEventListener('click', () => {
    if (promptBox) promptBox.hidden = true;
    openWindow({command: 'accept', callId: state.id});
  });
  if (declineButton) declineButton.addEventListener('click', async () => {
    if (promptBox) promptBox.hidden = true;
    try { render((await call('respond', {id: state.id, accept: false})).body); } catch { status.textContent = 'The phone did not answer.'; }
  });
  if (stopButton) stopButton.addEventListener('click', async () => {
    if (port) port.postMessage({command: 'stop'});
    try { render((await call('stop', {id: state.id})).body); } catch { status.textContent = 'The phone did not answer.'; }
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
      const payload = data.data && typeof data.data === 'object' ? data.data : {};
      if (action === 'offer' && (typeof payload.sdp !== 'string' || payload.sdp.length > 200000)) return;
      try {
        const {ok, body} = await call(action, payload);
        if (ok || body.state) render(body);
        channel.port1.postMessage({id, ok, body});
      } catch {
        channel.port1.postMessage({id, ok: false, body: {error: 'The phone did not answer. Check the hotspot and keep PocketBridge sharing.'}});
      }
    };
    popup.postMessage('pb-camera-paired', helperOrigin, [channel.port2]);
    status.textContent = 'Camera window opened.';
    flush();
  });
  window.addEventListener('pagehide', () => {
    clearTimeout(timeout);
    if (port) { port.postMessage({closed: true}); port.close(); }
  });
  document.addEventListener('visibilitychange', poll);
  poll();
})();
