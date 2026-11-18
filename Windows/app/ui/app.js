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
  $('#tb-browser-back').addEventListener('pointerdown', e => e.stopPropagation());
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
let loginMethod = 'direct', pendingVerifMethod = 'email-2fa';
let signinBusyCount = 0;
let captchaTimer = null, lastCaptchaPromptId = 0;
let browserPollTimer = null;
let relayState = null, relayHealthStatus = null;
let annQueue = [], annTotal = 0, annShown = 0, annTimer = null;

/* ======================================================== helpers */
const API_ERROR_CODE = /^[A-Z0-9_]{2,64}$/;
function apiErrorText(d, status) {
  // The backend answers failures with {error, error_code, retryable}. When the code is known,
  // render the localized title + description — never a raw code or stack (W-18/W-19).
  const code = d && typeof d.error_code === 'string' && API_ERROR_CODE.test(d.error_code) ? d.error_code : '';
  if (code) {
    const probe = 'errors.' + code + '.title';
    if ((I[lang] && I[lang][probe]) !== undefined || (I.en && I.en[probe] !== undefined)) {
      return { code, retryable: Boolean(d.retryable), text: t(probe) + ' — ' + t('errors.' + code + '.desc') };
    }
  }
  return { code: '', retryable: false, text: (d && d.error) || t('e_http', {c: status}) };
}
async function api(path, body) {
  const opt = body === undefined ? {} :
    {method:'POST', headers:{'Content-Type':'application/json','X-RXVPN':'1'}, body:JSON.stringify(body)};
  let r;
  try { r = await fetch('/api/' + path, opt); }
  catch { throw new Error(t('e_link')); }
  let d = {}; try { d = await r.json(); } catch {}
  if (!r.ok) {
    const info = apiErrorText(d, r.status);
    const e = new Error(info.text);
    e.data = d; e.status = r.status; e.code = info.code; e.retryable = info.retryable;
    throw e;
  }
  return d;
}
function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast' + (kind === 'err' ? ' err' : '');
  el.innerHTML = ic(kind === 'err' ? 'i-alert' : 'i-check') + '<span></span>';
  el.lastChild.textContent = msg;
  $('#toasts').replaceChildren(el);
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
  const noAccount = !hasActiveAccount();
  qEl.classList.toggle('no-account', noAccount);
  const nq = $('#quota-no-account');
  if (nq) nq.hidden = !noAccount;
  if (noAccount) {
    // Android parity: no active account -> no quota numbers, show a tappable shortcut to Accounts
    qEl.classList.remove('unknown', 'unlimited', 'error');
    const rf0 = $('#quota-refresh'); if (rf0) rf0.hidden = true;
    return;
  }
  const state = st.quota_state || 'unknown';
  const unlimited = state === 'unlimited' || Boolean(st.quota_unlimited);
  qEl.classList.remove('unknown', 'unlimited', 'error');
  // W-13: contextual call to action under the quota card, staleness marker and threshold line
  const threshold = Math.max(50, Math.min(100, Number((typeof settingsState !== 'undefined' && settingsState && settingsState.rotation_pct) || 95)));
  const mark = $('#quota-mark');
  if (mark) { mark.hidden = state !== 'limited'; mark.style.insetInlineStart = threshold + '%'; }
  const rf = $('#quota-refresh');
  if (rf) {
    const checked = st.quota_checked_at ? Date.parse(st.quota_checked_at) : NaN;
    rf.hidden = !(Number.isFinite(checked) && (Date.now() - checked) > 5 * 60 * 1000);
  }
  const cta = $('#quota-cta');
  const pool = st.pool || {};
  if (cta) {
    if (state === 'error') {
      cta.hidden = false; cta.textContent = t('quota_check_again'); cta.dataset.action = 'retry';
    } else if (pool.available && !pool.active_account) {
      cta.hidden = false; cta.textContent = t('accounts_cta_not_active'); cta.dataset.action = 'accounts';
    } else {
      cta.hidden = true; cta.dataset.action = '';
    }
  }

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
    $('#quota-caption').textContent = t('quota_error_card');
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
$('#quota-refresh').addEventListener('click', async () => {
  const rf = $('#quota-refresh');
  rf.disabled = true;
  try {
    await api('refresh_quota', {});
    $('#quota-caption').textContent = t('quota_checking');
    rf.hidden = true;
  } catch (e) { toast(e.message, 'err'); }
  rf.disabled = false;
});
$('#quota-no-account').addEventListener('click', () => { void openAccountsPane(); });
$('#quota-cta').addEventListener('click', async () => {
  const cta = $('#quota-cta');
  const action = cta.dataset.action;
  if (action === 'retry') {
    cta.disabled = true;
    try {
      await api('refresh_quota', {});
      $('#quota-caption').textContent = t('quota_checking');
    } catch (e) { toast(e.message, 'err'); }
    setTimeout(() => { cta.disabled = false; refresh(); }, 3000);
  } else if (action === 'accounts') {
    await openAccountsPane();
  }
});
async function openAccountsPane() {
  await openSettings();
  settingsCat = 'accounts';
  renderSettings(); setView('pane'); renderSettingsState();
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
  $('#manual-protocol')._rxSelectSync?.();
  const portInput = $('#manual-port');
  if (document.activeElement !== portInput && portInput.value !== String(connectionDraft.port)) {
    portInput.value = connectionDraft.port;
  }
  const note = connectionDraft.mode === 'tun'
    ? t(!st.tun_available ? 'mode_tun_missing' : !st.tun_admin ? 'mode_tun_admin' : 'mode_tun_note')
    : connectionDraft.mode === 'system' ? t('mode_system_note') : t('mode_manual_note');
  $('#conn-note').textContent = note;
}

