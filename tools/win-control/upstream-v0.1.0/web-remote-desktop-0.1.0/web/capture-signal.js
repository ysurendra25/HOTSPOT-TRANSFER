async function localSignal(message) {
  const response = await fetch('/phone-signal', {method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify(message), cache:'no-store', signal:AbortSignal.timeout(35000)});
  const body = await response.text();
  let data;
  try { data = JSON.parse(body); } catch { throw new Error(response.ok ? 'Unexpected reply from phone.' : body); }
  if (!response.ok) throw new Error(data.error || 'The phone refused this request.');
  return data;
}
