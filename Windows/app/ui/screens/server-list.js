"use strict";
/* ======================================================== server list */
async function loadServers(force) {
  $('#list').innerHTML = `<div class="empty">${esc(t('loading'))}</div>`;
  try {
    servers = (await api(force ? 'refresh_servers' : 'servers', force ? {} : undefined)).servers;
  } catch (e) {
    $('#list').innerHTML = `<div class="empty">${esc(e.message)}<br><button class="link" id="retry" type="button">${esc(t('retry'))}</button></div>`;
    lastListHtml = '';
    $('#retry').onclick = () => loadServers(true);
    return;
  }
  if (!selected || !byCode(selected)) {
    selected = (st.country && st.country.code) || (byCode(st.last_country) && st.last_country) || (byCode('REC') ? 'REC' : servers[0]?.code);
  }
  animList = true; lastListHtml = '';
  render();
}
function openServerListPage() {
  closeSettings();
  document.body.classList.add('server-list-page');
  $('#aside').classList.remove('open');
  $('#server-page-head').hidden = false;
  const search = $('#q');
  if (search) search.value = '';
  animList = true;
  lastListHtml = '';
  renderList();
  if (search) search.focus({preventScroll:true});
}
function closeServerListPage() {
  document.body.classList.remove('server-list-page');
  $('#server-page-head').hidden = true;
  $('#aside').classList.remove('open');
  render();
}
$('#server-page-back').onclick = closeServerListPage;

/* ======================================================== render */
function renderList() {
  const q = $('#q').value.trim().toLowerCase();
  let items = servers.filter(s => !q || dispName(s).toLowerCase().includes(q) || s.name.toLowerCase().includes(q) || s.code.toLowerCase() === q);
  const collator = new Intl.Collator(lang, {sensitivity:'base'});
  if (sortPing) {
    items.sort((a, b) => {
      const recommendedOrder = (a.code === 'REC' ? 0 : 1) - (b.code === 'REC' ? 0 : 1);
      if (recommendedOrder) return recommendedOrder;
      const latencyOrder = (pings[a.code] ?? Infinity) - (pings[b.code] ?? Infinity);
      return latencyOrder || collator.compare(dispName(a), dispName(b));
    });
  } else {
    items.sort((a, b) => {
      const recommendedOrder = (a.code === 'REC' ? 0 : 1) - (b.code === 'REC' ? 0 : 1);
      return recommendedOrder || collator.compare(dispName(a), dispName(b));
    });
  }
  $('#sort').classList.toggle('on', sortPing);
  $('#count').textContent = servers.length ? `(${servers.length})` : '';
  $('#server-page-count').textContent = servers.length ? `(${servers.length})` : '';
  let html;
  if (!items.length) html = servers.length ? `<div class="empty">${esc(t('nomatch'))}</div>` : '';
  else {
    const live = st.country && st.country.code;
    html = items.map((s, idx) => {
      const ms = pings[s.code], has = s.code in pings, [cls, , n] = quality(ms);
      const isPinging = pinging && pingingCode === s.code;
      const rowPing = s.code === selected && s.code !== 'REC' && !has && !isPinging
        ? `<button class="chip row-ping" type="button" data-ping-code="${esc(s.code)}" aria-label="${esc(t('ping_btn'))}"${pinging ? ' disabled' : ''}>${esc(t('ping_btn'))}</button>`
        : '';
      const badge = has
        ? ms == null
          ? '<span class="ms r">✕</span>'
          : `<span class="ms ${cls}"><span class="num">${ms}</span><small>ms</small></span><span class="${cls}">${bars(n)}</span>`
        : isPinging
          ? `<span class="server-ping-loading" role="status" aria-label="${esc(t('pinging'))}"></span>`
          : '';
      const tag = s.code === live && st.state === 'connected' ? `<span class="live-tag"><i></i>${esc(t('live'))}</span>` : '';
      const sub = (s.code === 'REC' ? t('auto') : s.code.toUpperCase()) + ' · ' + t(s.count > 1 ? 'srv_n' : 'srv_1', {n:s.count});
      return `<div class="row spot${s.code === selected ? ' sel' : ''}${rowPing ? ' has-ping-action' : ''}" style="--i:${Math.min(idx, 16)}">
        <button class="row-select" data-code="${esc(s.code)}" type="button" aria-pressed="${s.code === selected}">
          ${flag(s.code)}
          <span class="rt"><span class="nm">${esc(dispName(s))}</span><span class="sub">${esc(sub)}</span></span>
          <span class="rm">${tag}${badge}</span>
        </button>${rowPing}</div>`;
    }).join('');
  }
  if (html === lastListHtml) return;
  lastListHtml = html;
  const list = $('#list');
  list.classList.toggle('enter', animList);
  list.innerHTML = html;
  if (animList) { animList = false; setTimeout(() => list.classList.remove('enter'), 900); }
}
function renderPing() {
  const has = selected in pings, ms = pings[selected];
  const selectedIsPinging = pinging && pingingCode === selected;
  const [cls, label] = quality(ms);
  $('#pv').textContent = has && ms != null ? ms : '—';
  $('#pq').textContent = selectedIsPinging ? t('pinging') : has ? label : t('ping_none');
  $('#sonar').className = 'sonar ' + (selectedIsPinging ? 'go' : (has ? cls : ''));
  $('#ping').disabled = pinging || !selected;
  $('#ping-all').disabled = pinging;
}

async function pingSelected() {
  if (pinging || !selected) return;
  await pingServer(selected, false);
}
async function pingServer(code, quiet) {
  if (pinging || !code) return;
  pinging = true; pingingCode = code; renderPing();
  renderList();
  try { pings[code] = (await api('ping', {code})).ms; }
  catch (e) {
    pings[code] = null;
    if (!quiet) toast(e.message, 'err');
  }
  finally { pinging = false; pingingCode = null; render(); }
}
async function pingAll() {
  if (pinging) return;
  pinging = true; renderPing(); renderList(); $('#ping-all').textContent = t('pinging_all');
  try { Object.assign(pings, await api('ping_all', {})); toast(t('t_pingall')); }
  catch (e) { toast(e.message, 'err'); }
  finally { pinging = false; $('#ping-all').textContent = t('ping_all'); render(); }
}


$('#ping').onclick = pingSelected;
$('#ping-all').onclick = pingAll;
$('#sort').onclick = () => { sortPing = !sortPing; $('#sort').classList.toggle('on', sortPing); animList = true; lastListHtml = ''; renderList(); };
$('#q').oninput = () => { animList = true; renderList(); };
$('#list').onclick = e => {
  const pingButton = e.target.closest('.row-ping');
  if (pingButton) { pingServer(pingButton.dataset.pingCode, false); return; }
  const row = e.target.closest('.row-select'); if (!row) return;
  const onServerPage = document.body.classList.contains('server-list-page');
  selected = row.dataset.code;
  if (onServerPage) closeServerListPage();
  else { $('#aside').classList.remove('open'); render(); }
  if (st.state === 'connected' && !busy && !(st.country && st.country.code === selected)) doConnect(selected);
};
$('#pick').onclick = () => {
  if (isNarrow()) { openServerListPage(); return; }
  $('#aside').classList.toggle('open');
  $('#q').focus({preventScroll:true});
};
window.addEventListener('resize', () => {
  if (isNarrow() && $('#aside').classList.contains('open')) openServerListPage();
});
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  // close the sign-in overlays first (unless a request is in flight), then the server drawer
  if (!$('#signin-overlay').hidden || !$('#browser-overlay').hidden) { closeSignin(false); return; }
  if (document.body.classList.contains('server-list-page')) { closeServerListPage(); return; }
  $('#aside').classList.remove('open');
});
