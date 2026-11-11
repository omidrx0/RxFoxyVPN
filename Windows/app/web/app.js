"use strict";
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const ic = (id, cls) => `<svg class="i ${cls || ''}"><use href="#${id}"/></svg>`;
const GLOBE = ic('i-globe');

/* ======================================================== custom title bar (only inside the desktop window: ?tb=1) */
(() => {
  if (new URLSearchParams(location.search).get('tb') !== '1') return;
  document.body.classList.add('has-tb');
  $('#titlebar').hidden = false;
  $$('.rz').forEach(el => { el.hidden = false; });
  // the native window (Go) exposes rxwin_minimize, rxwin_close, rxwin_toggle_maximize, ...
  const api = (m, ...a) => {
    const f = window['rxwin_' + m];
    return typeof f === 'function' ? Promise.resolve(f(...a)).catch(() => null) : Promise.resolve(null);
  };
  const setMax = v => document.body.classList.toggle('tb-maxed', !!v);
  const toggleMax = () => api('toggle_maximize').then(v => { if (v !== null) setMax(v); });
  $('#tb-min').addEventListener('click', () => api('minimize'));
  $('#tb-max').addEventListener('click', toggleMax);
  $('#tb-close').addEventListener('click', () => api('close'));
  // moving: the native side follows the mouse (smooth). A double click maximizes / restores.
  let lastDown = 0;
  $('#tb-drag').addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const now = Date.now();
    if (now - lastDown < 400) { lastDown = 0; toggleMax(); return; }
    lastDown = now;
    api('start_drag').then(ok => {
      // no native drag available: fall back to a CSS drag region
      if (ok === false) $('#tb-drag').classList.add('pywebview-drag-region');
    });
  });
  // edge resizing: the native side follows the mouse itself (see internal/desktop/window_windows.go)
  $$('.rz').forEach(el => {
    el.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      e.preventDefault();
      api('begin_resize', el.dataset.e);
    });
  });
})();


/* ======================================================== state */
let st = {state:'idle'}, stAt = Date.now();
let connError = false, connErrTimer = 0;
let servers = [], selected = null, busy = false, disconnecting = false, pinging = false, pingingCode = null, sortPing = false, connectionSaving = false;
let connectionDraft = null, lastServerConn = '', lastLiveCode = null;
const pings = {};
let trafficSample = null;
let trafficRates = {uploaded:0, downloaded:0};
let pollTimer = null, lastListHtml = '', animList = false, netMode = 'idle';
let loginMethod = 'direct', loginStep = 'main', pendingVerifMethod = 'email-2fa';
let captchaTimer = null, lastCaptchaPromptId = 0;
let browserPollTimer = null;
let relayState = null, relayHealthStatus = null;
let annQueue = [], annTotal = 0, annShown = 0, annTimer = null;

/* ======================================================== helpers */
async function api(path, body) {
  const opt = body === undefined ? {} :
    {method:'POST', headers:{'Content-Type':'application/json','X-RXVPN':'1'}, body:JSON.stringify(body)};
  let r;
  try { r = await fetch('/api/' + path, opt); }
  catch { throw new Error(t('e_link')); }
  let d = {}; try { d = await r.json(); } catch {}
  if (!r.ok) { const e = new Error(d.error || t('e_http', {c:r.status})); e.data = d; e.status = r.status; throw e; }
  return d;
}
function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast' + (kind === 'err' ? ' err' : '');
  el.innerHTML = ic(kind === 'err' ? 'i-alert' : 'i-check') + '<span></span>';
  el.lastChild.textContent = msg;
  $('#toasts').appendChild(el);
  const ttl = kind === 'err' ? 6500 : 3200;
  setTimeout(() => el.classList.add('out'), ttl - 300);
  setTimeout(() => el.remove(), ttl);
}
const dn = {};
function dispName(s) {
  if (s.code === 'REC') return t('rec');
  try {
    dn[lang] = dn[lang] || new Intl.DisplayNames([lang], {type:'region'});
    const n = dn[lang].of(s.code === 'UK' ? 'GB' : s.code);
    if (n && n !== s.code) return n;
  } catch {}
  return s.name;
}
function flag(code) {
  if (!/^[A-Za-z]{2}$/.test(code)) return `<span class="flag globe">${GLOBE}</span>`;
  const cc = (code.toUpperCase() === 'UK' ? 'gb' : code).toLowerCase();
  const emoji = [...cc.toUpperCase()].map(ch => String.fromCodePoint(127397 + ch.charCodeAt(0))).join('');
  return `<span class="flag"><img src="/assets/flags/${cc}.svg" alt="" loading="lazy" onerror="this.replaceWith(document.createTextNode('${emoji}'))"></span>`;
}
const quality = ms => ms == null ? ['r', t('q_none'), 0] : ms < 60 ? ['g', t('q_best'), 4] : ms < 120 ? ['g', t('q_good'), 3] : ms < 200 ? ['y', t('q_mid'), 2] : ['r', t('q_slow'), 1];
const bars = n => `<span class="bars q${n}">${'<i></i>'.repeat(4)}</span>`;
const pad = n => String(n).padStart(2, '0');
const fmtTime = s => `${pad(Math.floor(s / 3600))}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}`;
function formatBytes(value, perSecond = false) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let amount = Math.max(0, Number(value) || 0), unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
  const formatted = new Intl.NumberFormat(lang === 'fa' ? 'fa-IR' : (lang === 'ko' ? 'ko-KR' : 'en-US'), {
    maximumFractionDigits: unit === 0 ? 0 : 1
  }).format(amount);
  return `${formatted} ${units[unit]}${perSecond ? '/s' : ''}`;
}
function updateTrafficRates(next) {
  const sample = {
    at: performance.now(),
    uploaded: Number(next.uploaded_bytes) || 0,
    downloaded: Number(next.downloaded_bytes) || 0
  };
  if (next.state === 'connected' && trafficSample) {
    const seconds = (sample.at - trafficSample.at) / 1000;
    trafficRates = seconds > 0 ? {
      uploaded: Math.max(0, sample.uploaded - trafficSample.uploaded) / seconds,
      downloaded: Math.max(0, sample.downloaded - trafficSample.downloaded) / seconds
    } : {uploaded:0, downloaded:0};
  } else {
    trafficRates = {uploaded:0, downloaded:0};
  }
  trafficSample = next.state === 'connected' ? sample : null;
}
const byCode = code => servers.find(s => s.code === code);
function setSwap(el, text) {
  if (el.textContent === text) return;
  el.textContent = text; el.classList.remove('swap'); void el.offsetWidth; el.classList.add('swap');
}
function setNum(el, v) {
  v = String(v);
  if (el.textContent === v) return;
  el.textContent = v; el.classList.remove('bump'); void el.offsetWidth; el.classList.add('bump');
}
function renderQuota() {
  const qEl = $('#quota');
  const state = st.quota_state || 'unknown';
  const unlimited = state === 'unlimited' || Boolean(st.quota_unlimited);
  qEl.classList.remove('unknown', 'unlimited', 'error');

  if (unlimited) {
    qEl.classList.add('unlimited');
    $('#quota-value').textContent = t('quota_unlimited_val');
    $('#quota-caption').textContent = t('quota_unlimited_caption');
    $('#quota-total').textContent = t('quota_unlimited_total');
    $('#quota-fill').style.width = '100%';
    $('#quota-bar').setAttribute('aria-valuenow', '100');
    $('#quota-bar').setAttribute('aria-valuetext', t('quota_unlimited_val'));
    return;
  }

  const remaining = Number(st.quota_remaining);
  const total = Number(st.quota_limit);
  const known = state === 'limited' && Number.isFinite(remaining) && Number.isFinite(total) && total > 0 && remaining >= 0 && remaining <= total;

  if (known) {
    const bytesPerGb = 1024 ** 3;
    const remainingGb = remaining / bytesPerGb;
    const limitGb = total / bytesPerGb;
    const usedGb = Math.max(0, total - remaining) / bytesPerGb;
    const format = n => new Intl.NumberFormat(lang === 'fa' ? 'fa-IR' : (lang === 'ko' ? 'ko-KR' : 'en-US'), {maximumFractionDigits:1}).format(n);
    const percent = Math.max(0, Math.min(100, (remaining / total) * 100));
    $('#quota-value').textContent = `${format(remainingGb)} GB`;
    $('#quota-caption').textContent = t('quota_used', {n:format(usedGb)});
    $('#quota-total').textContent = t('quota_total', {n:format(limitGb)});
    $('#quota-fill').style.width = percent + '%';
    $('#quota-bar').setAttribute('aria-valuenow', String(Math.round(percent)));
    $('#quota-bar').setAttribute('aria-valuetext', t('quota_remaining', {n:format(remainingGb)}));
    return;
  }

  if (state === 'error') {
    qEl.classList.add('error');
    $('#quota-value').textContent = '—';
    $('#quota-caption').textContent = t('quota_error');
    $('#quota-total').textContent = t('quota_error_total');
    $('#quota-fill').style.width = '0%';
    $('#quota-bar').setAttribute('aria-valuenow', '0');
    $('#quota-bar').setAttribute('aria-valuetext', t('quota_error'));
    return;
  }

  qEl.classList.add('unknown');
  $('#quota-value').textContent = '—';
  $('#quota-caption').textContent = t('quota_checking');
  $('#quota-total').textContent = t('quota_total_unknown');
  $('#quota-fill').style.width = '0%';
  $('#quota-bar').setAttribute('aria-valuenow', '0');
  $('#quota-bar').setAttribute('aria-valuetext', t('quota_checking'));
}
function renderConnectionSettings() {
  const current = st.connection || {mode:'manual',protocol:'socks5',port:10808};
  if (!connectionDraft) connectionDraft = {...current};
  const locked = st.state === 'connected' || busy || connectionSaving;
  $$('input[name="connection-mode"]').forEach(input => {
    input.checked = input.value === connectionDraft.mode;
    input.disabled = locked || (input.value === 'tun' && !st.tun_available) ||
      (input.value === 'system' && !st.system_proxy_available);
  });
  $('#manual-fields').hidden = connectionDraft.mode !== 'manual';
  $('#manual-protocol').disabled = locked;
  $('#manual-port').disabled = locked;
  $('#manual-protocol').value = connectionDraft.protocol;
  const portInput = $('#manual-port');
  if (document.activeElement !== portInput && portInput.value !== String(connectionDraft.port)) {
    portInput.value = connectionDraft.port;
  }
  const note = connectionDraft.mode === 'tun'
    ? t(!st.tun_available ? 'mode_tun_missing' : !st.tun_admin ? 'mode_tun_admin' : 'mode_tun_note')
    : connectionDraft.mode === 'system' ? t('mode_system_note') : t('mode_manual_note');
  $('#conn-note').textContent = note;
}

/* ======================================================== language */
function applyLang(l, push) {
  // Close and restore any portaled custom dropdown before translated controls are rerendered.
  if (typeof closeRxDropdowns === 'function') closeRxDropdowns();
  lang = I[l] ? l : 'en';
  // The backend injects the build version into .ver; do not hardcode it here.
  const h = document.documentElement; h.lang = lang; h.dir = (lang === 'fa' || lang === 'ar') ? 'rtl' : 'ltr';
  $$('.ls').forEach(e => e.dataset.v = lang);
  const loginLanguage = $('#login-language'); if (loginLanguage) { loginLanguage.value = lang; if (loginLanguage._rxDropdownSync) loginLanguage._rxDropdownSync(); }
  $$('[data-i18n]').forEach(e => e.textContent = t(e.dataset.i18n));
  $$('[data-i18n-html]').forEach(e => e.innerHTML = t(e.dataset.i18nHtml));
  $$('[data-i18n-ph]').forEach(e => e.placeholder = t(e.dataset.i18nPh));
  $$('[data-i18n-aria]').forEach(e => e.setAttribute('aria-label', t(e.dataset.i18nAria)));
  $('#st-sub').dataset.k = '';
  lastListHtml = '';
  render();
  refreshSettingsLang();
  // Reveal the UI only after the selected locale has been applied to every translated element.
  document.documentElement.classList.remove('i18n-loading');
  if (push) { try { localStorage.setItem('rxfoxyvpn-lang', lang); } catch {} api('set_lang', {lang}).catch(() => {}); }
}
window.__setLang = l => { if (l !== lang) applyLang(l, false); };

/* ======================================================== screens */
async function refreshSavedAccounts() {
  try {
    const result = await api('saved_accounts');
    const select = $('#saved-accounts');
    select.replaceChildren(...result.accounts.map(email => new Option(email, email)));
    $('#saved-accounts-box').hidden = result.accounts.length === 0;
    $('#use-saved-account').disabled = result.accounts.length === 0;
    $('#remove-saved-account').disabled = result.accounts.length === 0;
  } catch {
    $('#saved-accounts-box').hidden = true;
  }
}
function updateCodeStepTexts() {
  const isTotp = pendingVerifMethod === 'totp-2fa';
  $('#code-title').textContent = t(isTotp ? 'totp_h' : 'code_h');
  $('#code-lead').textContent = t(isTotp ? 'totp_lead' : 'code_lead');
  $('#code-lbl').textContent = t(isTotp ? 'totp_label' : 'code_label');
  $('#code-resend').hidden = isTotp;
}
function setLoginMethod(m) {
  loginMethod = m === 'browser' ? 'browser' : 'direct';
  $$('#login-tabs [data-method]').forEach(btn => {
    const on = btn.dataset.method === loginMethod;
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-selected', String(on));
  });
  if (loginStep === 'main') {
    $('#f-login').hidden = loginMethod !== 'direct';
    $('#f-browser').hidden = loginMethod !== 'browser';
  }
}
function showLogin(step, verifMethod) {
  clearInterval(pollTimer);
  clearInterval(annTimer);
  document.body.classList.remove('in-app');
  closeSettings();
  $('#ann-overlay').hidden = true;
  $('#app').hidden = true; $('#login').hidden = false;

  loginStep = (step === 'code' || step === 'unblock') ? step : 'main';
  if (verifMethod) pendingVerifMethod = verifMethod;

  const isMain = loginStep === 'main';
  $('#login-tabs').hidden = !isMain;
  $('#f-login').hidden = !(isMain && loginMethod === 'direct');
  $('#f-browser').hidden = !(isMain && loginMethod === 'browser');
  $('#f-code').hidden = loginStep !== 'code';
  $('#f-unblock').hidden = loginStep !== 'unblock';

  updateCodeStepTexts();
  $('#err-login').textContent = '';
  $('#err-code').textContent = '';
  $('#err-unblock').textContent = '';
  $('#err-browser').textContent = '';
  refreshSavedAccounts();
}
async function showApp() {
  stopBrowserPoll();
  syncCaptchaModal(null);
  document.body.classList.add('in-app');
  $('#login').hidden = true; $('#app').hidden = false;
  render();
  await loadServers();
  loadSettings().then(maybeAutoConnect);
  void checkAnnouncements();
  clearInterval(annTimer);
  annTimer = setInterval(checkAnnouncements, 5 * 60 * 1000);
  clearInterval(pollTimer);
  pollTimer = setInterval(refresh, 2500);
}
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
/* the tray menu (next to the clock) can change the connection mode and the server behind the panel's back:
   pull those changes into the panel state so both always show the same thing */
function syncExternalChanges() {
  if (busy || connectionSaving) return;
  // connection mode / protocol / port
  const conn = st.connection ? JSON.stringify(st.connection) : '';
  if (conn !== lastServerConn) {
    lastServerConn = conn;
    if (st.connection) connectionDraft = {...st.connection};
  }
  // server: follow whichever server is actually connected
  const live = st.state === 'connected' && st.country ? st.country.code : null;
  if (live !== lastLiveCode) {
    lastLiveCode = live;
    if (live && live !== selected && byCode(live)) {
      selected = live;
      animList = true; lastListHtml = '';
    }
  }
}
async function refresh() {
  try {
    const next = await api('status');
    updateTrafficRates(next);
    st = next; stAt = Date.now();
    syncCaptchaModal(st.captcha_active ? st.captcha : null);
    if (!st.logged_in) {
      const step = st.pending_code ? 'code' : (st.pending_unblock ? 'unblock' : undefined);
      return showLogin(step, st.pending_verification_method);
    }
    if (st.lang && st.lang !== lang) return applyLang(st.lang, false);
    syncExternalChanges();
    render();
  } catch (e) { /* keep the last known state */ }
}

/* ======================================================== render */
function renderList() {
  const q = $('#q').value.trim().toLowerCase();
  let items = servers.filter(s => !q || dispName(s).toLowerCase().includes(q) || s.name.toLowerCase().includes(q) || s.code.toLowerCase() === q);
  if (sortPing) {
    const rec = items.filter(s => s.code === 'REC'), rest = items.filter(s => s.code !== 'REC');
    rest.sort((a, b) => (pings[a.code] ?? 1e9) - (pings[b.code] ?? 1e9));
    items = rec.concat(rest);
  }
  $('#count').textContent = servers.length ? `(${servers.length})` : '';
  let html;
  if (!items.length) html = servers.length ? `<div class="empty">${esc(t('nomatch'))}</div>` : '';
  else {
    const live = st.country && st.country.code;
    html = items.map((s, idx) => {
      const ms = pings[s.code], has = s.code in pings, [cls, , n] = quality(ms);
      const badge = has ? (ms == null ? '<span class="ms r">✕</span>' : `<span class="ms ${cls}"><span class="num">${ms}</span><small>ms</small></span><span class="${cls}">${bars(n)}</span>`) : '';
      const tag = s.code === live && st.state === 'connected' ? `<span class="live-tag"><i></i>${esc(t('live'))}</span>` : '';
      const sub = (s.code === 'REC' ? t('auto') : (lang === 'fa' ? s.name : s.code)) + ' · ' + t(s.count > 1 ? 'srv_n' : 'srv_1', {n:s.count});
      return `<button class="row spot${s.code === selected ? ' sel' : ''}" data-code="${esc(s.code)}" type="button" style="--i:${Math.min(idx, 16)}">
        ${flag(s.code)}
        <span class="rt"><span class="nm">${esc(dispName(s))}</span><span class="sub">${esc(sub)}</span></span>
        <span class="rm">${tag}${badge}</span></button>`;
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
function render() {
  const state = busy ? 'connecting' : st.state === 'connected' ? 'connected' : 'idle';
  // orb sub-state: off / ready / processing (disconnecting) / error
  const orb = state === 'connecting' ? (disconnecting ? 'processing' : '')
            : state === 'idle' ? (connError ? 'error' : selected ? 'ready' : 'off') : '';
  $('#app').className = (state + ' ' + orb).trim(); netMode = state;
  const sel = byCode(selected), live = st.country;

  const ing = disconnecting ? 'dis' : 'ing';
  setSwap($('#st-title'), state === 'connected' ? t('ttl_conn') : state === 'connecting' ? t('ttl_' + ing) : t('ttl_idle'));
  const sub = $('#st-sub');
  let subKey = state, subHtml;
  if (state === 'connected' && live) {
    subKey = 'c:' + live.code + lang;
    subHtml = `${flag(live.code)}<span>${esc(t('sub_conn', {n:dispName(live)}))}</span>`;
  } else {
    subKey = state + (state === 'connecting' ? ing : '') + lang;
    subHtml = esc(state === 'connecting' ? t('sub_' + ing) : t('sub_idle'));
  }
  if (sub.dataset.k !== subKey) {
    sub.dataset.k = subKey; sub.innerHTML = subHtml;
    sub.classList.remove('swap'); void sub.offsetWidth; sub.classList.add('swap');
  }
  $('#power').disabled = busy || !selected;
  $('#power').setAttribute('aria-label', state === 'connected' ? t('disconnect') : t('connect'));
  $('#pill').className = 'pill ' + state;
  $('#pill-t').textContent = state === 'connected' ? t('pill_on') : state === 'connecting' ? t('pill_' + ing) : t('pill_off');
  $('#pick-flag').innerHTML = sel ? flag(sel.code) : '';
  $('#pick-name').textContent = sel ? dispName(sel) : '—';
  $('#px-socks').textContent = st.socks || '—';
  $('#px-http').textContent = st.http || '—';
  $$('[data-copy="px-socks"]').forEach(button => button.disabled = !st.socks);
  $$('[data-copy="px-http"]').forEach(button => button.disabled = !st.http);
  $('#proxies').hidden = st.state !== 'connected' || (!st.socks && !st.http);
  $('#proxies').classList.toggle('off', st.state !== 'connected');
  renderQuota();
  renderConnectionSettings();
  $('#upload-rate').textContent = formatBytes(trafficRates.uploaded, true);
  $('#download-rate').textContent = formatBytes(trafficRates.downloaded, true);
  $('#uploaded-total').textContent = formatBytes(st.uploaded_bytes);
  $('#downloaded-total').textContent = formatBytes(st.downloaded_bytes);
  tick(); renderPing(); renderList(); renderSettingsState();
}
function tick() {
  const up = st.state === 'connected' ? st.uptime + Math.floor((Date.now() - stAt) / 1000) : 0;
  $('#s-up').textContent = fmtTime(up);
}
setInterval(tick, 1000);

/* ======================================================== actions */
async function doConnect(code) {
  if (busy) return;
  connError = false; clearTimeout(connErrTimer);
  busy = true; render();
  let shouldPing = false;
  try {
    st = await api('connect', {code}); stAt = Date.now();
    shouldPing = true;
    toast(t('t_conn', {n:dispName(byCode(code) || {code, name:code})}));
  } catch (e) {
    toast(e.message, 'err');
    if (e.data && e.data.need_login) { busy = false; return showLogin(); }
    connError = true; clearTimeout(connErrTimer);
    connErrTimer = setTimeout(() => { connError = false; render(); }, 4200);
  } finally {
    busy = false;
    await refresh();
    if (shouldPing) void pingServer(code, true);
  }
}
async function doDisconnect() {
  busy = true; disconnecting = true; render();
  try { st = await api('disconnect', {}); stAt = Date.now(); toast(t('t_disc')); }
  catch (e) { toast(e.message, 'err'); }
  finally { busy = false; disconnecting = false; await refresh(); }
}
const toggleConn = () => st.state === 'connected' ? doDisconnect() : doConnect(selected);
async function pingSelected() {
  if (pinging || !selected) return;
  await pingServer(selected, false);
}
async function pingServer(code, quiet) {
  if (pinging || !code) return;
  pinging = true; pingingCode = code; renderPing();
  try { pings[code] = (await api('ping', {code})).ms; }
  catch (e) {
    pings[code] = null;
    if (!quiet) toast(e.message, 'err');
  }
  finally { pinging = false; pingingCode = null; render(); }
}
async function pingAll() {
  if (pinging) return;
  pinging = true; renderPing(); $('#ping-all').textContent = t('pinging_all');
  try { Object.assign(pings, await api('ping_all', {})); toast(t('t_pingall')); }
  catch (e) { toast(e.message, 'err'); }
  finally { pinging = false; $('#ping-all').textContent = t('ping_all'); render(); }
}

/* ======================================================== events */
$('#power').onclick = toggleConn;
$('#ping').onclick = pingSelected;
$('#ping-all').onclick = pingAll;
$$('input[name="connection-mode"]').forEach(input => input.onchange = () => {
  connectionDraft = {...connectionDraft, mode:input.value};
  $('#conn-error').textContent = '';
  renderConnectionSettings();
  saveConnection();
});
async function saveConnection() {
  const port = Number($('#manual-port').value || connectionDraft.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    $('#conn-error').textContent = t('port_invalid');
    return;
  }
  connectionSaving = true;
  $('#conn-error').textContent = '';
  renderConnectionSettings();
  try {
    const result = await api('set_connection', {
      mode:connectionDraft.mode,
      protocol:$('#manual-protocol').value,
      port
    });
    connectionDraft = {...result.connection};
    st.connection = {...result.connection};
    toast(t('connection_saved'));
  } catch (e) {
    $('#conn-error').textContent = e.message;
  } finally {
    connectionSaving = false;
    renderConnectionSettings();
  }
}
$('#manual-protocol').onchange = () => {
  connectionDraft.protocol = $('#manual-protocol').value;
  saveConnection();
};
$('#manual-port').oninput = () => { connectionDraft.port = $('#manual-port').value; };
$('#manual-port').onchange = saveConnection;
$('#sort').onclick = () => { sortPing = !sortPing; $('#sort').classList.toggle('on', sortPing); animList = true; lastListHtml = ''; renderList(); };
$('#q').oninput = () => { animList = true; renderList(); };
$('#list').onclick = e => {
  const row = e.target.closest('.row'); if (!row) return;
  selected = row.dataset.code; $('#aside').classList.remove('open'); render();
  if (st.state === 'connected' && !busy && !(st.country && st.country.code === selected)) doConnect(selected);
};
$('#pick').onclick = () => { $('#aside').classList.toggle('open'); $('#q').focus({preventScroll:true}); };
$('#menu-btn').onclick = () => $('#aside').classList.toggle('open');
document.addEventListener('keydown', e => { if (e.key === 'Escape') $('#aside').classList.remove('open'); });
$$('[data-copy]').forEach(b => b.onclick = async () => {
  try { await navigator.clipboard.writeText($('#' + b.dataset.copy).textContent); toast(t('t_copied')); }
  catch { toast(t('t_copyfail'), 'err'); }
});
$('#logout').onclick = async () => {
  try { await api('logout', {}); } catch (e) { toast(e.message, 'err'); return; }
  st = {state:'idle', lang}; selected = null; showLogin();
};
$$('.ls button').forEach(b => b.onclick = () => { if (b.dataset.lang !== lang) applyLang(b.dataset.lang, true); });
const loginLanguage = $('#login-language'); if (loginLanguage) loginLanguage.onchange = () => { if (loginLanguage.value !== lang) applyLang(loginLanguage.value, true); };
document.addEventListener('change', e => {
  if (e.target.matches('[data-lang-select]') && e.target.value !== lang) applyLang(e.target.value, true);
});
document.addEventListener('click', e => {
  const a = e.target.closest('a[target=_blank]'); if (!a) return;
  e.preventDefault();
  api('open_url', {url: a.href}).catch(() => window.open(a.href, '_blank'));
});
$('#open-signup').onclick = () => $('#signup').showModal();
$('#close-signup').onclick = () => $('#signup').close();
$('#signup').addEventListener('click', e => { if (e.target === e.currentTarget) e.currentTarget.close(); });
$('#eye').onclick = () => {
  const p = $('#password'), show = p.type === 'password';
  p.type = show ? 'text' : 'password';
  $('#eye').innerHTML = ic(show ? 'i-eye-off' : 'i-eye');
};

/* ======================================================== Interactive Fastly CAPTCHA, Browser Auth & Announcements */
function syncCaptchaModal(prompt) {
  const overlay = $('#captcha-overlay');
  if (!overlay) return;
  if (!prompt || !prompt.prompt_id) {
    overlay.hidden = true;
    lastCaptchaPromptId = 0;
    return;
  }
  overlay.hidden = false;
  const noticeEl = $('#captcha-notice');
  const noticeMap = {
    wrong_answer: 'captcha_notice_wrong',
    expired: 'captcha_notice_expired',
    decode_failed: 'captcha_notice_decode',
    refresh_failed: 'captcha_notice_refresh_err'
  };
  if (prompt.notice && noticeMap[prompt.notice]) {
    noticeEl.textContent = t(noticeMap[prompt.notice]);
    noticeEl.hidden = false;
  } else {
    noticeEl.textContent = '';
    noticeEl.hidden = true;
  }

  if (prompt.image_data_uri) {
    $('#captcha-img').src = prompt.image_data_uri;
  }
  const loading = Boolean(prompt.loading);
  $('#btn-captcha-submit').disabled = loading;
  $('#btn-captcha-refresh').disabled = loading;
  $('#captcha-answer').disabled = loading;

  if (prompt.prompt_id !== lastCaptchaPromptId) {
    lastCaptchaPromptId = prompt.prompt_id;
    $('#err-captcha').textContent = '';
    $('#captcha-answer').value = '';
    setTimeout(() => $('#captcha-answer')?.focus(), 40);
  }
}

async function pollCaptchaOnce() {
  try {
    const r = await api('captcha');
    syncCaptchaModal(r.active ? r.challenge : null);
  } catch (_) {}
}

async function withCaptchaWatch(fn) {
  clearInterval(captchaTimer);
  captchaTimer = setInterval(pollCaptchaOnce, 700);
  try {
    return await fn();
  } finally {
    clearInterval(captchaTimer);
    captchaTimer = null;
    syncCaptchaModal(null);
  }
}

$('#f-captcha').onsubmit = async e => {
  e.preventDefault();
  const ans = $('#captcha-answer').value.trim();
  if (!ans) return;
  $('#err-captcha').textContent = '';
  $('#btn-captcha-submit').disabled = true;
  try {
    await api('captcha_submit', {answer: ans});
  } catch (err) {
    $('#err-captcha').textContent = err.message;
    $('#btn-captcha-submit').disabled = false;
  }
};
$('#btn-captcha-refresh').onclick = async () => {
  $('#err-captcha').textContent = '';
  $('#btn-captcha-refresh').disabled = true;
  try { await api('captcha_refresh', {}); }
  catch (err) { $('#err-captcha').textContent = err.message; $('#btn-captcha-refresh').disabled = false; }
};
$('#btn-captcha-cancel').onclick = async () => {
  try { await api('captcha_cancel', {}); } catch (_) {}
  syncCaptchaModal(null);
};

/* ---- Browser Sign-In ---- */
function stopBrowserPoll() {
  clearInterval(browserPollTimer);
  browserPollTimer = null;
}
function renderBrowserStatus(bs) {
  const active = Boolean(bs && bs.active);
  $('#browser-ctas').hidden = active;
  $('#browser-progress').hidden = !active;
  if (!active) return;

  const stageKey = {
    awaiting_browser: 'browser_stage_awaiting',
    checking: 'browser_stage_checking',
    waiting_for_confirmation: 'browser_stage_confirm',
    getting_tokens: 'browser_stage_tokens'
  }[bs.stage] || 'browser_stage_awaiting';
  $('#browser-stage-txt').textContent = t(stageKey);
  $('#browser-paste-box').hidden = bs.mode !== 'external';
}
async function pollBrowserAuth() {
  try {
    const bs = await api('browser_login_status');
    renderBrowserStatus(bs);
    if (bs.stage === 'succeeded') {
      stopBrowserPoll();
      await refresh();
      if (st.logged_in) showApp();
      return;
    }
    if (bs.stage === 'failed') {
      stopBrowserPoll();
      renderBrowserStatus({active: false});
      $('#err-browser').textContent = bs.error || t('e_http', {c: 400});
      return;
    }
    if (!bs.active) {
      stopBrowserPoll();
    }
  } catch (_) {}
}
async function startBrowserAuth(mode) {
  $('#err-browser').textContent = '';
  $('#btn-browser-webview').disabled = true;
  $('#btn-browser-external').disabled = true;
  try {
    const email = $('#browser-email').value.trim() || $('#email').value.trim();
    const bs = await api('browser_login_start', {mode, email});
    renderBrowserStatus(bs);
    stopBrowserPoll();
    browserPollTimer = setInterval(pollBrowserAuth, 1000);
  } catch (err) {
    $('#err-browser').textContent = err.message;
  } finally {
    $('#btn-browser-webview').disabled = false;
    $('#btn-browser-external').disabled = false;
  }
}
$('#btn-browser-webview').onclick = () => startBrowserAuth('webview2');
$('#btn-browser-external').onclick = () => startBrowserAuth('external');
$('#btn-browser-cancel').onclick = async () => {
  stopBrowserPoll();
  try { await api('browser_login_cancel', {}); } catch (_) {}
  renderBrowserStatus({active: false});
  $('#browser-redirect-url').value = '';
};
$('#btn-browser-complete').onclick = async () => {
  const url = $('#browser-redirect-url').value.trim();
  if (!url) return;
  $('#btn-browser-complete').disabled = true;
  $('#err-browser').textContent = '';
  try {
    await withCaptchaWatch(() => api('browser_login_complete', {redirect_url: url}));
    $('#browser-redirect-url').value = '';
    stopBrowserPoll();
    await refresh();
    showApp();
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-browser').textContent = err.message;
  } finally {
    $('#btn-browser-complete').disabled = false;
  }
};

$$('#login-tabs [data-method]').forEach(btn => {
  btn.onclick = () => setLoginMethod(btn.dataset.method);
});

/* ---- Sequential Home Announcements (persisted strictly on «متوجه شدم») ---- */
function renderAnnouncementModal() {
  const overlay = $('#ann-overlay');
  if (!overlay) return;
  if (!annQueue.length) {
    overlay.hidden = true;
    return;
  }
  const cur = annQueue[0];
  $('#ann-title').textContent = cur.title || '';
  $('#ann-message').textContent = cur.message || '';
  $('#ann-counter').textContent = t('ann_counter', {i: annShown + 1, n: annTotal});
  $('#ann-counter').hidden = annTotal <= 1;
  overlay.hidden = false;
}
async function checkAnnouncements() {
  if (!st.logged_in) return;
  try {
    const r = await api('announcements');
    const list = Array.isArray(r.announcements) ? r.announcements : [];
    if (!list.length) return;
    const existingIds = new Set(annQueue.map(a => a.id));
    let added = 0;
    for (const item of list) {
      if (item && item.id && !existingIds.has(item.id)) {
        annQueue.push(item);
        added++;
      }
    }
    if (added > 0 && $('#ann-overlay').hidden) {
      annTotal = annQueue.length;
      annShown = 0;
      renderAnnouncementModal();
    }
  } catch (_) {}
}
$('#btn-ann-ack').onclick = async () => {
  if (!annQueue.length) { $('#ann-overlay').hidden = true; return; }
  const cur = annQueue[0];
  const btn = $('#btn-ann-ack');
  btn.disabled = true;
  try {
    await api('ack_announcement', {id: cur.id});
    annQueue.shift();
    annShown++;
    renderAnnouncementModal();
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    btn.disabled = false;
  }
};

/* ---- Login, Saved Accounts, 2FA/TOTP & Security Unblock ---- */
function handleLoginResult(d, rememberToast) {
  if (rememberToast) toast(t(d.account_saved ? 'account_saved' : 'account_not_saved'), d.account_saved ? undefined : 'err');
  if (d.account_saved) refreshSavedAccounts();
  if (d.need_unblock) {
    showLogin('unblock');
    $('#unblock-code').focus();
    return false;
  }
  if (d.need_code) {
    showLogin('code', d.verification_method || 'email-2fa');
    $('#code').focus();
    return false;
  }
  return true;
}

$('#f-login').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#btn-login'); btn.disabled = true; $('#err-login').textContent = '';
  try {
    const remember = $('#remember-account').checked;
    const d = await withCaptchaWatch(() => api('login', {email: $('#email').value, password: $('#password').value, remember}));
    $('#password').value = '';
    if (handleLoginResult(d, remember)) { await refresh(); showApp(); }
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-login').textContent = err.message;
  } finally { btn.disabled = false; }
};
$('#use-saved-account').onclick = async () => {
  const button = $('#use-saved-account'), email = $('#saved-accounts').value;
  if (!email) return;
  button.disabled = true; $('#err-login').textContent = '';
  try {
    const result = await withCaptchaWatch(() => api('login_saved', {email}));
    if (handleLoginResult(result, false)) { await refresh(); showApp(); }
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-login').textContent = err.message;
  } finally { button.disabled = false; }
};
$('#remove-saved-account').onclick = async () => {
  const email = $('#saved-accounts').value;
  if (!email) return;
  try {
    const result = await api('delete_saved_account', {email});
    const select = $('#saved-accounts');
    select.replaceChildren(...result.accounts.map(account => new Option(account, account)));
    $('#saved-accounts-box').hidden = result.accounts.length === 0;
    $('#use-saved-account').disabled = result.accounts.length === 0;
    $('#remove-saved-account').disabled = result.accounts.length === 0;
    toast(result.accounts.length ? t('account_removed') : t('no_saved_account'));
  } catch (err) { $('#err-login').textContent = err.message; }
};
$('#f-code').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#btn-code'); btn.disabled = true; $('#err-code').textContent = '';
  try {
    await withCaptchaWatch(() => api('verify', {code: $('#code').value, method: pendingVerifMethod}));
    $('#code').value = '';
    await refresh();
    showApp();
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-code').textContent = err.message;
  } finally { btn.disabled = false; }
};
$('#code-resend').onclick = async () => {
  const btn = $('#code-resend'); btn.disabled = true; $('#err-code').textContent = '';
  try {
    await withCaptchaWatch(() => api('resend_code', {mode: 'verify'}));
    toast(t('code_resent'));
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-code').textContent = err.message;
  } finally { btn.disabled = false; }
};
$('#code-back').onclick = async () => {
  try { await api('cancel_auth', {}); } catch (_) {}
  $('#code').value = '';
  showLogin();
};
$('#f-unblock').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#btn-unblock'); btn.disabled = true; $('#err-unblock').textContent = '';
  try {
    const d = await withCaptchaWatch(() => api('unblock', {code: $('#unblock-code').value}));
    $('#unblock-code').value = '';
    if (handleLoginResult(d, false)) { await refresh(); showApp(); }
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-unblock').textContent = err.message;
  } finally { btn.disabled = false; }
};
$('#unblock-resend').onclick = async () => {
  const btn = $('#unblock-resend'); btn.disabled = true; $('#err-unblock').textContent = '';
  try {
    await withCaptchaWatch(() => api('resend_code', {mode: 'unblock'}));
    toast(t('code_resent'));
  } catch (err) {
    if (!err.data?.captcha_cancelled) $('#err-unblock').textContent = err.message;
  } finally { btn.disabled = false; }
};
$('#unblock-back').onclick = async () => {
  try { await api('cancel_auth', {}); } catch (_) {}
  $('#unblock-code').value = '';
  showLogin();
};
document.addEventListener('contextmenu', e => { if (!e.target.closest('input')) e.preventDefault(); });

/* spotlight that follows the cursor + click ripple */
document.addEventListener('mousemove', e => {
  const el = e.target.closest && e.target.closest('.spot'); if (!el) return;
  const r = el.getBoundingClientRect();
  el.style.setProperty('--mx', (e.clientX - r.left) + 'px');
  el.style.setProperty('--my', (e.clientY - r.top) + 'px');
});
document.addEventListener('pointerdown', e => {
  const b = e.target.closest && e.target.closest('.btn,.cta,.ghost');
  if (!b || b.disabled) return;
  const r = b.getBoundingClientRect(), d = Math.max(r.width, r.height) * 2;
  const s = document.createElement('span'); s.className = 'ripple';
  s.style.cssText = `width:${d}px;height:${d}px;left:${e.clientX - r.left - d / 2}px;top:${e.clientY - r.top - d / 2}px`;
  b.appendChild(s); setTimeout(() => s.remove(), 700);
});

/* ======================================================== settings page */
/* Theme-native select dropdowns. The original select stays as the source of truth for existing logic. */
function closeRxDropdowns(except) {
  // Close every open wrapper and clean up orphaned popup portals left behind by rerenders.
  $$('.rx-dd.open').forEach(dd => {
    if (dd === except) return;
    dd.classList.remove('open');
    const m = dd._rxMenu || dd.querySelector('.rx-dd-menu');
    if (m) {
      m.hidden = true;
      m.classList.remove('rx-dd-menu-portal');
      m.style.cssText = '';
      if (m.parentElement === document.body) {
        if (dd.isConnected) dd.appendChild(m);
        else m.remove();
      }
    }
    const b = dd._rxTrigger || dd.querySelector('.rx-dd-trigger');
    if (b) b.setAttribute('aria-expanded', 'false');
  });
  // A language/settings rerender can detach the wrapper while its menu is portaled to body.
  $$('.rx-dd-menu-portal').forEach(m => {
    m.hidden = true;
    m.classList.remove('rx-dd-menu-portal');
    m.style.cssText = '';
    const dd = m._rxOwner;
    if (dd && dd.isConnected) dd.appendChild(m); else m.remove();
  });
}
function positionRxDropdown(wrap, menu, trigger) {
  const r=trigger.getBoundingClientRect(), gap=7, margin=8;
  const below=window.innerHeight-r.bottom-gap-margin;
  const above=r.top-gap-margin;
  const openUp=below<Math.min(180,menu.scrollHeight) && above>below;
  const available=Math.max(100,openUp?above:below);
  menu.style.position='fixed';
  // Clear the CSS anchor rules: in RTL layouts, right:0 can override left and send the popup to the far edge.
  menu.style.right='auto';
  menu.style.left=Math.max(margin,Math.min(r.left,window.innerWidth-r.width-margin))+'px';
  menu.style.width=Math.min(r.width,window.innerWidth-margin*2)+'px';
  menu.style.maxHeight=Math.min(270,available)+'px';
  menu.style.top=openUp?'auto':(r.bottom+gap)+'px';
  menu.style.bottom=openUp?(window.innerHeight-r.top+gap)+'px':'auto';
}
function enhanceRxSelect(select) {
  if (!select || select.dataset.rxEnhanced === '1') return;
  select.dataset.rxEnhanced = '1'; select.classList.add('rx-dd-native');
  const wrap=document.createElement('div'); wrap.className='rx-dd';
  select.parentNode.insertBefore(wrap,select); wrap.appendChild(select);
  const trigger=document.createElement('button'); trigger.type='button'; trigger.className='rx-dd-trigger'; trigger.setAttribute('aria-haspopup','listbox'); trigger.setAttribute('aria-expanded','false');
  const label=document.createElement('span'); label.className='rx-dd-label';
  const arrow=document.createElement('span'); arrow.className='rx-dd-arrow'; arrow.textContent='⌄'; trigger.append(label,arrow);
  const menu=document.createElement('div'); menu.className='rx-dd-menu'; menu.setAttribute('role','listbox'); menu.hidden=true;
  wrap.append(trigger,menu); wrap._rxMenu=menu;wrap._rxTrigger=trigger;menu._rxOwner=wrap;
  function sync(){
    const selected=select.options[select.selectedIndex]; label.textContent=selected?selected.textContent:'';
    menu.replaceChildren();
    [...select.options].forEach((option,index)=>{
      const item=document.createElement('button'); item.type='button'; item.className='rx-dd-option'; item.setAttribute('role','option'); item.setAttribute('aria-selected',String(option.selected));
      const text=document.createElement('span'); text.textContent=option.textContent; item.appendChild(text);
      if(option.selected){const check=document.createElement('span');check.className='rx-dd-check';check.textContent='✓';item.appendChild(check)}
      item.disabled=option.disabled;
      item.addEventListener('click',()=>{
        // Close before dispatching change: language changes rebuild the settings DOM synchronously.
        closeRxDropdowns();
        select.selectedIndex=index;
        select.dispatchEvent(new Event('change',{bubbles:true}));
        if (select.isConnected) sync();
      });
      menu.appendChild(item);
    });
  }
  trigger.addEventListener('click',()=>{
    const open=!wrap.classList.contains('open');closeRxDropdowns(wrap);wrap.classList.toggle('open',open);trigger.setAttribute('aria-expanded',String(open));
    if(open){
      // Render exactly one popup in the document layer so cards/scroll containers cannot clip it.
      menu.hidden=false;menu._rxOwner=wrap;menu.classList.add('rx-dd-menu-portal');document.body.appendChild(menu);positionRxDropdown(wrap,menu,trigger);
      const active=menu.querySelector('[aria-selected="true"]');if(active)active.scrollIntoView({block:'nearest'});
    }else{menu.hidden=true;menu.classList.remove('rx-dd-menu-portal');menu.style.cssText='';wrap.appendChild(menu);}
  });
  select.addEventListener('change',sync);select._rxDropdownSync=sync;sync();
}
window.addEventListener('resize',()=>{$$('.rx-dd.open').forEach(dd=>{if(dd._rxMenu&&dd._rxTrigger)positionRxDropdown(dd,dd._rxMenu,dd._rxTrigger);});});
document.addEventListener('scroll',()=>{$$('.rx-dd.open').forEach(dd=>{if(dd._rxMenu&&dd._rxTrigger)positionRxDropdown(dd,dd._rxMenu,dd._rxTrigger);});},true);
function enhanceVisibleDropdowns(root=document) { root.querySelectorAll('.login-language select, #set-pane select').forEach(enhanceRxSelect); }
document.addEventListener('click',e=>{if(!e.target.closest('.rx-dd')&&!e.target.closest('.rx-dd-menu-portal'))closeRxDropdowns();});
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeRxDropdowns();});

var settingsState = null, autostartAvailable = false;
var settingsCat = 'general', settingsRestart = false, resetArmed = 0, autoTried = false;
const SET_SCHEMA = {
  general: [
    {k:'lang', type:'lang'},
    {k:'auto_connect', type:'switch'},
    {k:'autostart', type:'switch'},
    {k:'log_level', type:'select', opts:['trace','debug','info','warn','error']},
    {k:'logs', type:'logs'},
    {k:'reset', type:'reset'}
  ],
  routing: [
    {k:'bypass_lan', type:'switch'},
    {k:'ipv6', type:'switch'},
    {k:'udp_policy', type:'select', opts:['block','direct']},
    {k:'direct_apps', type:'list', ph:'cs2.exe\nsteam.exe', chips:[{key:'set_chip_cs2', add:['cs2.exe','steam.exe','steamwebhelper.exe']}]},
    {k:'direct_domains', type:'list', ph:'example.com', chips:[{key:'set_chip_ir', add:['ir']}]},
    {k:'direct_cidrs', type:'list', ph:'185.0.0.0/16'}
  ],
  dns: [
    {k:'dns_type', type:'select', opts:['https','tls','tcp']},
    {k:'dns_server', type:'text', ph:'1.1.1.1'},
    {k:'dns_path', type:'text', ph:'/dns-query', showIf:s => s.dns_type === 'https'},
    {k:'dns_strategy', type:'select', opts:['prefer_ipv4','ipv4_only','prefer_ipv6','ipv6_only']},
    {k:'direct_dns', type:'text', ph:'1.1.1.1'}
  ],
  inbound: [
    {k:'tun_stack', type:'select', opts:['auto','mixed','system','gvisor']},
    {k:'tun_mtu', type:'number'},
    {k:'strict_route', type:'switch'}
  ],
  about: [
    {k:'about_version', type:'about_version'},
    {k:'about_source', type:'about_source'},
    {k:'about_updates', type:'about_updates'}
  ]
};
const isNarrow = () => matchMedia('(max-width:900px)').matches;

async function loadSettings() {
  try {
    const r = await api('settings');
    settingsState = r.settings;
    autostartAvailable = !!r.autostart_available;
    if (r.relay) relayState = r.relay;
  } catch (e) { toast(e.message, 'err'); }
}
async function loadRelaySettings() {
  try {
    relayState = await api('relay_settings');
  } catch (e) { toast(e.message, 'err'); }
}

function renderRelayInto(container) {
  if (!container) return;
  const rs = relayState || {shared_enabled:true, personal_enabled:false, personal_only:false, has_personal_url:false, personal_host:''};
  const hostBadge = rs.has_personal_url && rs.personal_host
    ? `<div class="relay-host-pill">${ic('i-check')}<span>${esc(t('relay_host_active', {host: rs.personal_host}))}</span></div>`
    : `<div class="relay-host-pill off"><span>${esc(t('relay_host_none'))}</span></div>`;

  const healthHtml = relayHealthStatus
    ? `<div class="relay-health-out ${relayHealthStatus.ok ? 'ok' : 'err'}">${esc(relayHealthStatus.text)}</div>`
    : '';

  const wrap = document.createElement('div');
  wrap.className = 'set-card relay-panel';
  wrap.innerHTML = `
    <div class="set-row">
      <div class="set-txt"><b>${esc(t('relay_shared'))}</b><small>${esc(t('relay_shared_d'))}</small></div>
      <label class="sw"><input type="checkbox" data-relay-toggle="shared_enabled"${rs.shared_enabled ? ' checked' : ''}><i></i></label>
    </div>
    <div class="set-row col">
      <div style="display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%">
        <div class="set-txt"><b>${esc(t('relay_personal'))}</b><small>${esc(t('relay_personal_d'))}</small></div>
        <label class="sw"><input type="checkbox" data-relay-toggle="personal_enabled"${rs.personal_enabled ? ' checked' : ''}><i></i></label>
      </div>
      ${hostBadge}
      <div class="relay-url-editor">
        <label class="fld"><span>${esc(t('relay_url_label'))}</span>
          <div class="inp" dir="ltr">${ic('i-link')}<input type="password" class="has-eye" data-relay-url dir="ltr" autocomplete="off" spellcheck="false" placeholder="${esc(t('relay_url_ph'))}">
            <button class="eye" type="button" data-relay-eye aria-label="Toggle URL">${ic('i-eye')}</button>
          </div>
        </label>
        <small>${esc(t('relay_url_hint'))}</small>
        <div class="relay-btn-row">
          <button type="button" class="chip on" data-relay-save>${esc(t('relay_save_url'))}</button>
          <button type="button" class="chip" data-relay-test>${esc(t('relay_test_btn'))}</button>
          ${rs.has_personal_url ? `<button type="button" class="chip" data-relay-clear>${esc(t('relay_clear_btn'))}</button>` : ''}
        </div>
        ${healthHtml}
      </div>
    </div>
    <div class="set-row">
      <div class="set-txt"><b>${esc(t('relay_personal_only'))}</b><small>${esc(t('relay_personal_only_d'))}</small></div>
      <label class="sw"><input type="checkbox" data-relay-toggle="personal_only"${rs.personal_only ? ' checked' : ''}${!rs.personal_enabled ? ' disabled' : ''}><i></i></label>
    </div>
    <p class="relay-note">${esc(t('relay_privacy_note'))}</p>
  `;

  const refreshAllRelayViews = () => {
    if (!$('#settings').hidden && settingsCat === 'relay') renderRelayInto($('#set-pane'));
    if ($('#login-relay-dlg')?.open) renderRelayInto($('#login-relay-mount'));
  };

  wrap.querySelector('[data-relay-eye]').onclick = e => {
    const inp = wrap.querySelector('[data-relay-url]');
    const show = inp.type === 'password';
    inp.type = show ? 'text' : 'password';
    e.currentTarget.innerHTML = ic(show ? 'i-eye-off' : 'i-eye');
  };

  wrap.querySelectorAll('[data-relay-toggle]').forEach(chk => {
    chk.onchange = async () => {
      const key = chk.dataset.relayToggle;
      const val = chk.checked;
      const typedUrl = wrap.querySelector('[data-relay-url]').value.trim();
      const body = {[key]: val};
      if (key === 'personal_enabled' && val && typedUrl) body.personal_url = typedUrl;
      try {
        relayState = await api('set_relay_settings', body);
        toast(t('relay_saved'));
      } catch (err) {
        toast(err.message, 'err');
      }
      refreshAllRelayViews();
    };
  });

  wrap.querySelector('[data-relay-save]').onclick = async () => {
    const raw = wrap.querySelector('[data-relay-url]').value.trim();
    if (!raw) {
      wrap.querySelector('[data-relay-url]').focus();
      return;
    }
    const btn = wrap.querySelector('[data-relay-save]');
    btn.disabled = true;
    try {
      relayState = await api('set_relay_settings', {personal_url: raw, personal_enabled: true});
      relayHealthStatus = null;
      toast(t('relay_saved'));
      refreshAllRelayViews();
    } catch (err) {
      toast(err.message, 'err');
      btn.disabled = false;
    }
  };

  wrap.querySelector('[data-relay-test]').onclick = async () => {
    const raw = wrap.querySelector('[data-relay-url]').value.trim();
    const btn = wrap.querySelector('[data-relay-test]');
    btn.disabled = true;
    btn.textContent = t('relay_testing');
    try {
      const res = await api('test_personal_relay', raw ? {personal_url: raw} : {});
      const ms = res.elapsed_ms != null ? ` (${res.elapsed_ms} ms)` : '';
      relayHealthStatus = {ok: Boolean(res.ok), text: (res.message || '') + ms};
    } catch (err) {
      relayHealthStatus = {ok: false, text: err.message};
    } finally {
      refreshAllRelayViews();
    }
  };

  const clearBtn = wrap.querySelector('[data-relay-clear]');
  if (clearBtn) {
    clearBtn.onclick = async () => {
      clearBtn.disabled = true;
      try {
        relayState = await api('clear_personal_relay', {});
        relayHealthStatus = null;
        toast(t('relay_cleared'));
        refreshAllRelayViews();
      } catch (err) {
        toast(err.message, 'err');
        clearBtn.disabled = false;
      }
    };
  }

  container.replaceChildren(wrap);
}

$('#open-login-relay').onclick = async () => {
  await loadRelaySettings();
  relayHealthStatus = null;
  renderRelayInto($('#login-relay-mount'));
  $('#login-relay-dlg').showModal();
};
$('#close-login-relay').onclick = () => $('#login-relay-dlg').close();
$('#login-relay-dlg').addEventListener('click', e => { if (e.target === e.currentTarget) e.currentTarget.close(); });
function maybeAutoConnect() {
  if (autoTried) return; autoTried = true;
  const code = st.last_country;
  if (settingsState && settingsState.auto_connect && st.state === 'idle' && code && byCode(code) && !busy) {
    selected = code; doConnect(code);
  }
}
function setView(view) {
  $('#settings').dataset.view = view;
  $('#set-title').textContent = (view === 'pane' && isNarrow()) ? t('set_cat_' + settingsCat) : t('settings');
}
async function openSettings() {
  if (!settingsState) await loadSettings();
  document.body.classList.add('settings-open');
  $('#settings').hidden = false; $('#settings-btn').classList.add('on');
  setView(isNarrow() ? 'list' : 'pane');
  renderSettings(); renderSettingsState();
}
function closeSettings() {
  document.body.classList.remove('settings-open');
  const s = $('#settings'); if (s) s.hidden = true;
  const b = $('#settings-btn'); if (b) b.classList.remove('on');
}
function refreshSettingsLang() {
  if ($('#settings').hidden) return;
  const languageSelect = $('[data-lang-select]'); if (languageSelect) { languageSelect.value = lang; if (languageSelect._rxDropdownSync) languageSelect._rxDropdownSync(); }
  setView($('#settings').dataset.view); renderSettings(); renderSettingsState();
}
function settingRow(item) {
  const row = document.createElement('div');
  row.className = 'set-row'; row.dataset.row = item.k;
  const txt = `<div class="set-txt"><b>${esc(t('set_' + item.k))}</b><small>${esc(t('set_' + item.k + '_d'))}</small></div>`;
  const opt = (v, label) => `<option value="${esc(v)}">${esc(label)}</option>`;
  if (item.type === 'switch') {
    const off = item.k === 'autostart' && !autostartAvailable ? ' disabled' : '';
    row.innerHTML = txt + `<label class="sw"><input type="checkbox" data-k="${item.k}"${off}><i></i></label>`;
  } else if (item.type === 'select') {
    row.innerHTML = txt + `<div class="set-ctl"><select data-k="${item.k}">${item.opts.map(v => opt(v, t('set_' + item.k + '_' + v))).join('')}</select></div>`;
  } else if (item.type === 'lang') {
    row.innerHTML = txt + `<div class="set-ctl"><select data-lang-select>${opt('en', 'English')}${opt('fa', 'فارسی')}${opt('ar', 'العربية')}${opt('ru', 'Русский')}${opt('zh', '中文')}${opt('ko', '한국어')}</select></div>`;
  } else if (item.type === 'text' || item.type === 'number') {
    const num = item.type === 'number' ? ' min="0" max="9000" step="1" inputmode="numeric"' : '';
    row.innerHTML = txt + `<div class="set-ctl"><input type="${item.type}" data-k="${item.k}" placeholder="${esc(item.ph || '')}" autocomplete="off" spellcheck="false"${num}></div>`;
  } else if (item.type === 'list') {
    row.classList.add('col');
    const chips = (item.chips || []).map((c, i) => `<button type="button" class="chip" data-chip="${item.k}:${i}">${esc(t(c.key))}</button>`).join('');
    row.innerHTML = txt + `<textarea data-k="${item.k}" rows="4" placeholder="${esc(item.ph || '')}" spellcheck="false" autocomplete="off"></textarea>` + (chips ? `<div class="set-chips">${chips}</div>` : '');
  } else if (item.type === 'logs') {
    row.classList.add('col');
    row.innerHTML = txt + `<div class="set-chips"><button type="button" class="chip" id="set-logs-btn">${esc(t('set_logs_show'))}</button><button type="button" class="chip" id="set-logs-hide" hidden>${esc(t('set_logs_hide'))}</button></div><pre class="set-log" id="set-log" hidden></pre>`;
  } else if (item.type === 'about_version') {
    const version = document.querySelector('.logo .ver')?.textContent.trim() || 'Beta';
    row.classList.add('set-about-row');
    row.innerHTML = `<span class="set-about-icon"><svg class="i"><use href="#i-sliders"/></svg></span>` + txt + `<span class="set-about-value">${esc(version)}</span>`;
  } else if (item.type === 'about_source') {
    row.classList.add('set-about-row');
    row.innerHTML = `<span class="set-about-icon"><svg class="i"><use href="#i-link"/></svg></span>` + txt + `<a class="set-about-link" href="https://github.com/omidrx0/RxFoxyVPN" target="_blank" rel="noopener noreferrer">${esc(t('set_about_source_open'))} <span aria-hidden="true">↗</span></a>`;
  } else if (item.type === 'about_updates') {
    row.classList.add('set-about-row');
    row.innerHTML = `<span class="set-about-icon"><svg class="i"><use href="#i-download"/></svg></span>` + txt + `<button type="button" class="chip" id="set-check-updates">${esc(t('set_update_check'))}</button>`;
  } else if (item.type === 'reset') {
    row.innerHTML = txt + `<button type="button" class="chip" id="set-reset">${esc(t('set_reset_btn'))}</button>`;
  }
  return row;
}
function renderSettings() {
  const pane = $('#set-pane'); if (!pane) return;
  $$('.set-nav [data-cat]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.cat === settingsCat)));
  $('#set-cat-title').textContent = t('set_cat_' + settingsCat);
  if (settingsCat === 'relay') {
    if (!relayState) loadRelaySettings().then(() => renderRelayInto(pane));
    else renderRelayInto(pane);
    return;
  }
  const card = document.createElement('div'); card.className = 'set-card';
  SET_SCHEMA[settingsCat].forEach(item => card.appendChild(settingRow(item)));
  pane.replaceChildren(card);
  fillSettings();
  enhanceVisibleDropdowns(pane);
}
function fillSettings() {
  if (!settingsState) return;
  $$('#set-pane [data-k]').forEach(el => {
    const v = settingsState[el.dataset.k];
    if (el.type === 'checkbox') el.checked = !!v;
    else if (el.tagName === 'TEXTAREA') el.value = (v || []).join('\n');
    else el.value = v ?? '';
  });
  const ls = $('#set-pane [data-lang-select]'); if (ls) ls.value = lang;
  SET_SCHEMA[settingsCat].forEach(item => {
    if (!item.showIf) return;
    const row = $(`#set-pane [data-row="${item.k}"]`);
    if (row) row.hidden = !item.showIf(settingsState);
  });
}
function renderSettingsState() {
  if (st.state !== 'connected') settingsRestart = false;
  const s = $('#settings'); if (!s || s.hidden) return;
  const tun = (st.connection || {}).mode === 'tun';
  $('#set-tun-note').hidden = tun || settingsCat === 'general' || settingsCat === 'relay' || settingsCat === 'about';
  $('#set-restart').hidden = !settingsRestart;
}
async function saveSetting(key, value) {
  try {
    const r = await api('set_settings', {[key]: value});
    settingsState = r.settings;
    if (r.restart_needed) settingsRestart = true;
    toast(t('set_saved'));
  } catch (e) { toast(e.message, 'err'); }
  fillSettings(); renderSettingsState();
}
async function showLogs() {
  const pre = $('#set-log'); if (!pre) return;
  try {
    const r = await api('tun_log');
    pre.textContent = r.text || t('set_logs_empty');
    pre.hidden = false; $('#set-logs-hide').hidden = false;
    $('#set-logs-btn').textContent = t('set_logs_refresh');
    pre.scrollTop = pre.scrollHeight;
  } catch (e) { toast(e.message, 'err'); }
}
async function resetSettings() {
  const btn = $('#set-reset');
  if (Date.now() - resetArmed > 4000) {
    resetArmed = Date.now(); btn.textContent = t('set_reset_sure'); btn.classList.add('on');
    setTimeout(() => { if ($('#set-reset')) { $('#set-reset').textContent = t('set_reset_btn'); $('#set-reset').classList.remove('on'); } }, 4000);
    return;
  }
  resetArmed = 0;
  try {
    const r = await api('reset_settings', {});
    settingsState = r.settings; if (r.restart_needed) settingsRestart = true;
    toast(t('set_reset_done'));
  } catch (e) { toast(e.message, 'err'); }
  renderSettings(); renderSettingsState();
}
$('#settings-btn').onclick = () => $('#settings').hidden ? openSettings() : closeSettings();
$('#set-back').onclick = () => {
  if (isNarrow() && $('#settings').dataset.view === 'pane') setView('list'); else closeSettings();
};
$$('.set-nav [data-cat]').forEach(b => b.onclick = () => {
  settingsCat = b.dataset.cat; setView('pane'); renderSettings(); renderSettingsState();
});
$('#set-pane').addEventListener('change', e => {
  const el = e.target;
  if (el.matches('[data-lang-select]')) { if (el.value !== lang) applyLang(el.value, true); return; }
  const k = el.dataset.k; if (!k) return;
  let v;
  if (el.type === 'checkbox') v = el.checked;
  else if (el.tagName === 'TEXTAREA') v = el.value.split(/[\n,;]+/).map(x => x.trim()).filter(Boolean);
  else if (el.type === 'number') v = el.value === '' ? 0 : Number(el.value);
  else v = el.value;
  saveSetting(k, v);
});
$('#set-pane').addEventListener('click', e => {
  const chip = e.target.closest('[data-chip]');
  if (chip) {
    const [k, i] = chip.dataset.chip.split(':');
    const item = SET_SCHEMA[settingsCat].find(x => x.k === k);
    saveSetting(k, [...(settingsState[k] || []), ...item.chips[+i].add]);
    return;
  }
  if (e.target.closest('#set-logs-btn')) return showLogs();
  if (e.target.closest('#set-logs-hide')) {
    $('#set-log').hidden = true; $('#set-logs-hide').hidden = true; $('#set-logs-btn').textContent = t('set_logs_show');
    return;
  }
  if (e.target.closest('#set-reset')) resetSettings();
  if (e.target.closest('#set-check-updates')) checkForUpdates(true);
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#settings').hidden) $('#set-back').click(); });


// Branded update dialog: avoid the ugly browser-native window.confirm popup.
function showUpdateDialog({version, notes, canInstall, portableUrl, releaseUrl}) {
  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.className = 'rx-update-overlay';
    overlay.innerHTML = `
      <section class="rx-update-dialog" role="dialog" aria-modal="true" aria-labelledby="rx-update-title">
        <div class="rx-update-mark"><svg class="i"><use href="#i-download"/></svg></div>
        <div class="rx-update-kicker">${esc(t('set_update_kicker'))}</div>
        <h2 id="rx-update-title">${esc(t('set_update_available', {version}))}</h2>
        <p class="rx-update-subtitle">${esc(t('set_update_subtitle'))}</p>
        ${notes ? `<div class="rx-update-notes">${esc(notes)}</div>` : ''}
        <p class="rx-update-hint">${esc(canInstall ? t('set_update_install_prompt') : t('set_update_asset_missing'))}</p>
        <div class="rx-update-actions">
          <button type="button" class="ghost rx-update-later">${esc(t('set_update_later'))}</button>
          <button type="button" class="primary rx-update-accept">${esc(t(canInstall ? 'set_update_download_install' : 'set_update_download_portable'))}</button>
        </div>
      </section>`;
    document.body.appendChild(overlay);
    const finish = value => { overlay.remove(); resolve(value); };
    overlay.addEventListener('click', e => { if (e.target === overlay) finish(false); });
    overlay.querySelector('.rx-update-later').addEventListener('click', () => finish(false));
    overlay.querySelector('.rx-update-accept').addEventListener('click', () => finish(true));
    const onKey = e => { if (e.key === 'Escape') { document.removeEventListener('keydown', onKey); finish(false); } };
    document.addEventListener('keydown', onKey, {once:true});
    overlay.querySelector('.rx-update-accept').focus();
  });
}


function showUpdateProgress() {
  closeUpdateProgress();
  const overlay = document.createElement('div');
  overlay.className = 'rx-update-overlay rx-download-overlay';
  overlay.id = 'rx-update-progress';
  overlay.innerHTML = `<section class="rx-update-dialog rx-download-dialog" role="status" aria-live="polite">
    <div class="rx-download-spinner"></div>
    <div class="rx-update-kicker">${esc(t('set_update_kicker'))}</div>
    <h2>${esc(t('set_update_downloading'))}</h2>
    <p class="rx-update-subtitle" id="rx-download-status">${esc(t('set_update_download_wait'))}</p>
    <div class="rx-download-track"><span id="rx-download-fill"></span></div>
    <div class="rx-download-percent" id="rx-download-percent">0%</div>
  </section>`;
  document.body.appendChild(overlay);
}
function closeUpdateProgress() {
  document.getElementById('rx-update-progress')?.remove();
}
async function pollUpdateProgress() {
  const overlay = document.getElementById('rx-update-progress');
  if (!overlay) return;
  try {
    const p = await api('update_progress');
    const percent = Number.isFinite(Number(p.percent)) ? Math.max(0, Math.min(100, Number(p.percent))) : 0;
    const fill = document.getElementById('rx-download-fill');
    const label = document.getElementById('rx-download-percent');
    if (fill) fill.style.width = `${percent}%`;
    if (label) label.textContent = p.total > 0 ? `${percent}%` : t('set_update_download_wait');
  } catch (_) {}
}

/* ======================================================== GitHub release updates */
let updateCheckInFlight = false;
async function checkForUpdates(manual = false) {
  if (updateCheckInFlight) return;
  updateCheckInFlight = true;
  const button = $('#set-check-updates');
  if (button) { button.disabled = true; button.textContent = t('set_update_checking'); }
  try {
    const release = await api('update_check');
    if (!release.updateAvailable) {
      if (manual) toast(t('set_update_latest'));
      return;
    }
    const version = release.latestVersion || '';
    const notes = (release.releaseNotes || '').trim().slice(0, 1800);
    const accepted = await showUpdateDialog({
      version,
      notes,
      canInstall: Boolean(release.installerUrl),
      portableUrl: release.portableUrl || '',
      releaseUrl: release.releaseUrl || ''
    });
    if (accepted) {
      showUpdateProgress();
      const progressTimer = setInterval(pollUpdateProgress, 250);
      try {
        const result = await api('install_update', {});
        clearInterval(progressTimer);
        closeUpdateProgress();
        if (result.type === 'portable') {
          toast(t('set_update_portable_opened', {version: result.version || version}));
        } else {
          toast(t('set_update_started', {version: result.version || version}));
        }
      } catch (err) {
        clearInterval(progressTimer);
        closeUpdateProgress();
        toast(err.message || t('set_update_failed'), 'err');
      }
    }
  } catch (err) {
    if (manual) toast(t('set_update_failed'), 'err');
  } finally {
    updateCheckInFlight = false;
    if (button && button.isConnected) { button.disabled = false; button.textContent = t('set_update_check'); }
  }
}
// Check quietly after startup; failures must never interfere with VPN connectivity.
window.addEventListener('load', () => setTimeout(() => checkForUpdates(false), 3500), {once:true});

/* ======================================================== animated network background */
(function () {
  const cv = $('#net'), cx = cv.getContext('2d');
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const COL = {idle:[124,140,255], connecting:[255,95,176], connected:[77,196,255]};
  const SPD = {idle:.55, connecting:2.2, connected:1.05};
  let W = 0, H = 0, nodes = [], col = COL.idle.slice(), spd = SPD.idle, tm = 0;
  function resize() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    W = innerWidth; H = innerHeight; cv.width = W * dpr; cv.height = H * dpr; cx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const n = Math.round(Math.min(80, Math.max(30, W * H / 24000)));
    nodes = Array.from({length:n}, () => ({x:Math.random() * W, y:Math.random() * H, vx:(Math.random() - .5) * .45, vy:(Math.random() - .5) * .45, r:Math.random() * 1.5 + .7, p:Math.random() * 6.28}));
  }
  function frame() {
    if (!document.hidden) {
      tm += .016;
      const tc = COL[netMode] || COL.idle;
      for (let i = 0; i < 3; i++) col[i] += (tc[i] - col[i]) * .04;
      spd += ((SPD[netMode] || .55) - spd) * .04;
      cx.clearRect(0, 0, W, H);
      const rgb = col.map(Math.round).join(',');
      for (const a of nodes) {
        a.x += a.vx * spd; a.y += a.vy * spd;
        if (a.x < -20) a.x = W + 20; else if (a.x > W + 20) a.x = -20;
        if (a.y < -20) a.y = H + 20; else if (a.y > H + 20) a.y = -20;
      }
      const maxD = 150;
      for (let i = 0; i < nodes.length; i++) {
        const a = nodes[i];
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j], dx = a.x - b.x, dy = a.y - b.y, d = dx * dx + dy * dy;
          if (d < maxD * maxD) {
            const k = 1 - Math.sqrt(d) / maxD;
            cx.strokeStyle = `rgba(${rgb},${(k * .22).toFixed(3)})`; cx.lineWidth = 1;
            cx.beginPath(); cx.moveTo(a.x, a.y); cx.lineTo(b.x, b.y); cx.stroke();
          }
        }
        const glow = .45 + .35 * Math.sin(tm * 2 * spd + a.p);
        cx.fillStyle = `rgba(${rgb},${glow.toFixed(3)})`;
        cx.beginPath(); cx.arc(a.x, a.y, a.r, 0, 6.283); cx.fill();
      }
    }
    if (!reduce) requestAnimationFrame(frame);
  }
  addEventListener('resize', resize);
  resize(); frame();
})();

/* ======================================================== start */
// Apply the saved language immediately, before waiting for the backend status call.
// This prevents the English-first flash when the app is reopened.
const startupLang = (() => { try { const saved = localStorage.getItem('rxfoxyvpn-lang'); if (['en','fa','ar','ru','zh','ko'].includes(saved)) return saved; } catch {} const serverLang = document.documentElement.lang; return ['en','fa','ar','ru','zh','ko'].includes(serverLang) ? serverLang : 'en'; })();
enhanceVisibleDropdowns(document);
applyLang(startupLang, false);
(async () => {
  try {
    st = await api('status'); stAt = Date.now();
    syncCaptchaModal(st.captcha_active ? st.captcha : null);
    if (st.logged_in) showApp();
    else {
      const step = st.pending_code ? 'code' : (st.pending_unblock ? 'unblock' : undefined);
      showLogin(step, st.pending_verification_method);
    }
  } catch (e) { showLogin(); toast(e.message, 'err'); }
})();

// ---- living fox on the power button -------------------------------------------------
// The fox is a soft "skeleton": the tail bends from its root, the head turns from the neck,
// ears flick, the body breathes. Joints stay fixed, so the parts never come apart.
(function foxRig() {
  const fox = document.getElementById('fox-rig'), app = document.getElementById('app'),
        pw = document.getElementById('power');
  if (!fox || !app || !pw) return;
  const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const M = (x, y) => [100 + (x - 170) * .64, 100 + (y - 170) * .64];   // fox art space -> orb space
  const R = M(140, 290), TIP = M(251, 264), N = M(62, 232), B = M(110, 285), W = M(110, 250),
        EL = M(64, 180), ER = M(80, 177);
  const tl = Math.hypot(TIP[0] - R[0], TIP[1] - R[1]), ux = (TIP[0] - R[0]) / tl, uy = (TIP[1] - R[1]) / tl;
  const HY0 = M(0, 238)[1], HY1 = M(0, 212)[1];
  const rad = Math.PI / 180, clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v)), ss = t => t * t * (3 - 2 * t);
  const rot = (p, c, a) => { const s = Math.sin(a), k = Math.cos(a), x = p[0] - c[0], y = p[1] - c[1]; return [c[0] + x * k - y * s, c[1] + x * s + y * k]; };

  // parse the rest shapes once
  const parts = {};
  fox.querySelectorAll('[data-k]').forEach(el => {
    const k = el.dataset.k;
    if (k === 'nose') { parts[k] = {el, pt: [+el.getAttribute('cx'), +el.getAttribute('cy')]}; return; }
    const segs = []; (el.getAttribute('d').match(/[MLCQZ][^MLCQZ]*/g) || []).forEach(s => {
      const n = (s.slice(1).match(/-?\d*\.?\d+/g) || []).map(Number), pts = [];
      for (let i = 0; i < n.length; i += 2) pts.push([n[i], n[i + 1]]);
      segs.push({c: s[0], pts});
    });
    parts[k] = {el, segs};
  });
  const draw = (k, fn) => {
    const p = parts[k]; if (!p) return;
    if (p.pt) { const q = fn(p.pt); p.el.setAttribute('cx', q[0].toFixed(2)); p.el.setAttribute('cy', q[1].toFixed(2)); return; }
    p.el.setAttribute('d', p.segs.map(s => s.c + s.pts.map(q => { const r = fn(q); return r[0].toFixed(2) + ' ' + r[1].toFixed(2); }).join(' ')).join(''));
  };

  // poses (degrees / orb units / seconds)
  const POSE = {
    off:        {hr: 17, hx: 3, hy: 5,  el: -26, er: -30, tr: 12, wx: 0, wy: 3, wr: 0,  ws: .97, ta: 1.2, td: 7,   ha: .8, hd: 9,   ba: .03,  bd: 6},
    ready:      {hr: -2, hx: 0, hy: 0,  el: 0,   er: 0,   tr: 0,  wx: 0, wy: 0, wr: 0,  ws: 1,   ta: 4,   td: 3.6, ha: 2.4, hd: 7,  ba: .02,  bd: 5},
    connecting: {hr: -10, hx: 3, hy: -2, el: 12, er: 14,  tr: -6, wx: 2, wy: 0, wr: -2, ws: 1.02, ta: 9,  td: .62, ha: 2,  hd: 1.2, ba: .03,  bd: .9},
    processing: {hr: -4, hx: 0, hy: 0,  el: -14, er: -16, tr: -4, wx: 0, wy: 0, wr: -3, ws: 1,   ta: 17,  td: .9,  ha: 3,  hd: 1.5, ba: .025, bd: 1.2},
    connected:  {hr: -8, hx: 0, hy: -2, el: 8,   er: 9,   tr: -3, wx: 0, wy: 0, wr: 0,  ws: 1.02, ta: 5,  td: 3.2, ha: 2.5, hd: 6,  ba: .02,  bd: 4.5},
    error:      {hr: 18, hx: -1, hy: 5, el: -28, er: -32, tr: 18, wx: -3, wy: 2, wr: -3, ws: .94, ta: 1.5, td: 2,   ha: 1,  hd: 3,   ba: 0,    bd: 3}
  };
  const stateNow = () => { const c = app.classList;
    return c.contains('error') ? 'error' : c.contains('processing') ? 'processing' : c.contains('connecting') ? 'connecting'
         : c.contains('connected') ? 'connected' : c.contains('ready') ? 'ready' : 'off'; };

  let state = stateNow(), hover = false, press = false, lx = 0, ly = 0, tlx = 0, tly = 0;
  const sp = {};   // spring per parameter
  for (const k in POSE.off) sp[k] = {v: POSE[state][k], vel: 0};
  const kick = {l: {v: 0, vel: 0}, r: {v: 0, vel: 0}};
  const target = () => {
    const t = Object.assign({}, POSE[state]);
    if (hover && (state === 'ready' || state === 'connected')) { t.hr -= 9; t.hy -= 3; t.el += 14; t.er += 16; t.tr -= 8; t.ta += 4; }
    if (press) { t.ws *= .94; t.wy += 4; t.el -= 22; t.er -= 26; t.hr += 8; }
    return t;
  };
  new MutationObserver(() => { const s = stateNow(); if (s !== state) { state = s; if (calm) frame(0, true); } })
    .observe(app, {attributes: true, attributeFilter: ['class']});
  pw.addEventListener('pointerenter', () => hover = true);
  pw.addEventListener('pointerleave', () => { hover = false; press = false; });
  pw.addEventListener('pointerdown', () => press = true);
  addEventListener('pointerup', () => press = false);
  addEventListener('pointermove', e => {
    const r = pw.getBoundingClientRect();
    // While the settings page is open, <main> is display:none, so the button's rect is all zeros.
    // Dividing by that zero width/height gave NaN (e.g. pointer at x=0 or y=0) or +-Infinity, and the
    // NaN then stayed in lx/ly forever (lx += (NaN - lx) ...), so every head/ear path became "MNaN ...".
    // Only track the pointer while the button really has a size.
    if (!(r.width > 0 && r.height > 0)) return;
    const nx = (e.clientX - r.left - r.width / 2) / (r.width * 1.1),
          ny = (e.clientY - r.top - r.height / 2) / (r.height * 1.1);
    if (!Number.isFinite(nx) || !Number.isFinite(ny)) return;
    tlx = clamp(nx, -1, 1);
    tly = clamp(ny, -1, 1);
  }, {passive: true});
  const twitch = () => {
    if (state !== 'off' && state !== 'error') { const s = Math.random() < .5 ? kick.l : kick.r; s.vel -= 300 + Math.random() * 120; }
    setTimeout(twitch, 2500 + Math.random() * 4000);
  };
  setTimeout(twitch, 2500);

  let ph = {t: 0, h: 0, b: 0, w: 0}, last = 0, time = 0;
  function frame(now, snap) {
    const dt = snap ? 1 / 60 : clamp((now - last) / 1000, 0, 1 / 30); last = now; time += dt;
    const tg = target();
    for (const k in tg) {
      const s = sp[k];
      if (snap) { s.v = tg[k]; s.vel = 0; continue; }
      s.vel += ((tg[k] - s.v) * 120 - s.vel * 13) * dt; s.v += s.vel * dt;
    }
    for (const s of [kick.l, kick.r]) { s.vel += (-s.v * 220 - s.vel * 11) * dt; s.v += s.vel * dt; }
    lx += (tlx - lx) * Math.min(1, dt * 6); ly += (tly - ly) * Math.min(1, dt * 6);
    const P = k => sp[k].v, amp = calm ? 0 : 1;
    ph.t += dt / Math.max(.2, P('td')); ph.h += dt / Math.max(.5, P('hd')); ph.b += dt / Math.max(.5, P('bd')); ph.w += dt / (state === 'connecting' ? 1.1 : state === 'processing' ? 1.6 : state === 'off' ? 8 : 5);
    const S = x => Math.sin(x * 2 * Math.PI);

    const a1 = (P('tr') + amp * P('ta') * S(ph.t)) * rad, a2 = amp * .35 * P('ta') * S(ph.t - .18) * rad;
    const hAng = (P('hr') + ly * 6 + amp * P('ha') * S(ph.h)) * rad, hx = P('hx') + lx * 2, hy = P('hy') + ly * 1.5;
    const eL = (P('el') + lx * 3 + kick.l.v) * rad, eR = (P('er') + lx * 3 + kick.r.v) * rad;
    const bs = 1 + amp * P('ba') * S(ph.b), bsy = 1 + amp * P('ba') * 1.5 * S(ph.b);

    const tailF = p => { const t = clamp(((p[0] - R[0]) * ux + (p[1] - R[1]) * uy) / tl), w = ss(t); return rot(p, R, a1 * w + a2 * w * w); };
    const headF = p => { const w = ss(clamp((HY0 - p[1]) / (HY0 - HY1))), q = rot(p, N, hAng * w); return [q[0] + hx * w, q[1] + hy * w]; };
    const bodyF = p => [B[0] + (p[0] - B[0]) * bs, B[1] + (p[1] - B[1]) * bsy];
    ['tail', 'blade', 'st1', 'st2', 'rimt', 'glint'].forEach(k => draw(k, tailF));
    ['body', 'st3', 'rimb'].forEach(k => draw(k, bodyF));
    ['head', 'hl', 'eye', 'nose'].forEach(k => draw(k, headF));
    draw('earL', p => headF(rot(p, EL, eL)));
    draw('earR', p => headF(rot(p, ER, eR)));

    let wx = P('wx') + amp * (Math.sin(ph.w * 6.283 * .7) * .6), wy = P('wy') - amp * Math.abs(S(ph.w)) * 2.2;
    if (state === 'error' && amp) wx += Math.sin(time * 75) * .8;
    fox.setAttribute('transform', `translate(${wx.toFixed(2)} ${wy.toFixed(2)}) translate(${W[0]} ${W[1]}) rotate(${P('wr').toFixed(2)}) scale(${P('ws').toFixed(3)}) translate(${-W[0]} ${-W[1]})`);
  }
  const loop = now => { frame(now); requestAnimationFrame(loop); };
  if (calm) frame(0, true); else requestAnimationFrame(t => { last = t; loop(t); });
})();