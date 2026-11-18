"use strict";
/* ======================================================== language */
function resolveLanguagePreference(preference) {
  if (preference === 'auto') {
    const systemLanguage = (navigator.languages?.[0] || navigator.language || 'en').split(/[-_]/)[0].toLowerCase();
    return I[systemLanguage] ? systemLanguage : 'en';
  }
  return I[preference] ? preference : 'en';
}
function applyLang(preference, push) {
  // Close the selector before translated settings are rerendered.
  if (typeof closeRxDropdowns === 'function') closeRxDropdowns();
  langPreference = preference === 'auto' || I[preference] ? preference : 'en';
  lang = resolveLanguagePreference(langPreference);
  // The backend injects the build version into .ver; do not hardcode it here.
  const h = document.documentElement; h.lang = lang; h.dir = (lang === 'fa' || lang === 'ar') ? 'rtl' : 'ltr';
  $$('.ls').forEach(e => e.dataset.v = lang);
  const loginLanguage = $('#login-language'); if (loginLanguage) { loginLanguage.value = langPreference; loginLanguage._rxSelectSync?.(); }
  const settingsLanguage = $('[data-lang-select]'); if (settingsLanguage) { settingsLanguage.value = langPreference; settingsLanguage._rxSelectSync?.(); }
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
  try { localStorage.setItem('rxfoxyvpn-lang', langPreference); } catch {}
  if (push) api('set_lang', {lang}).catch(() => {});
}
window.__setLang = l => applyLang(l, false);

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
let openAccountsAfterLogin = false; // set when the user taps «Add account» / «Re-login» from the pool panel
let signinStep = 'main', signinMethod = 'direct';

// Sign-in method preference comes from Settings (same source Android uses: SignInMethod).
function preferredSigninMethod() {
  const m = (typeof settingsState !== 'undefined' && settingsState && settingsState.sign_in_method) || loginMethod || 'direct';
  return m === 'browser' ? 'browser' : 'direct';
}
function updateCodeStepTexts() {
  const isTotp = pendingVerifMethod === 'totp-2fa';
  $('#code-title').textContent = t(isTotp ? 'totp_h' : 'code_h');
  $('#code-lead').textContent = t(isTotp ? 'totp_lead' : 'code_lead');
  $('#code-lbl').textContent = t(isTotp ? 'totp_label' : 'code_label');
  $('#code-resend').hidden = isTotp;
}
function signinIsBusy() {
  if ($('.signin-dialog')?.classList.contains('captcha-active')) return false;
  if (signinBusyCount > 0) return true;
  // NOTE: #use-saved-account is intentionally NOT here — it stays disabled whenever
  // there are no saved accounts (persistent state, not in-flight), which would block
  // dismissing the sign-in overlay forever on a fresh profile.
  return ['#btn-login','#btn-code','#btn-unblock','#btn-browser-webview','#btn-browser-complete','#code-resend','#unblock-resend']
    .some(sel => { const el = $(sel); return el && el.disabled; });
}
// Android SignInDialog parity: sign-in is an in-app overlay, never a separate page.
function syncSigninVisibility() {
  const browserMain = signinStep === 'main' && signinMethod === 'browser';
  $('#signin-overlay').hidden = browserMain;
  $('#browser-overlay').hidden = !browserMain;
  $('#f-login').hidden = signinStep !== 'main';
  $('#f-code').hidden = signinStep !== 'code';
  $('#f-unblock').hidden = signinStep !== 'unblock';
}
function openSignin(step, verifMethod, method) {
  clearInterval(pollTimer);
  clearInterval(annTimer);
  closeSettings();
  $('#ann-overlay').hidden = true;

  signinStep = (step === 'code' || step === 'unblock') ? step : 'main';
  if (verifMethod) pendingVerifMethod = verifMethod;
  if (signinStep === 'main') {
    signinMethod = method === 'browser' || method === 'direct' ? method : preferredSigninMethod();
  }

  ['#err-login', '#err-code', '#err-unblock', '#err-browser'].forEach(id => { const el = $(id); if (el) el.textContent = ''; });
  updateCodeStepTexts();
  syncSigninVisibility();
  refreshSavedAccounts();
}
async function openAccountSignin(initialEmail = '') {
  openAccountsAfterLogin = true;
  await loadSettings();
  const selectedMethod = preferredSigninMethod();
  if (selectedMethod === 'browser') {
    $('#browser-email').value = initialEmail;
    clearInterval(pollTimer);
    clearInterval(annTimer);
    closeSettings();
    $('#ann-overlay').hidden = true;
    $('#signin-overlay').hidden = true;
    $('#browser-overlay').hidden = false;
    stopBrowserPoll();
    await startBrowserAuth('webview2');
    return;
  }
  openSignin('main', undefined, selectedMethod);
  $('#email').value = initialEmail;
}
function closeSignin(force) {
  if (!force && signinIsBusy()) return; // dismiss-block-while-loading, like the Android dialog
  if ($('.signin-dialog')?.classList.contains('captcha-active')) void cancelCaptchaChallenge();
  $('#signin-overlay').hidden = true;
  $('#browser-overlay').hidden = true;
  stopBrowserPoll();
  if (st.logged_in) { showApp(); return; }
  // signed out: drop back onto the (always visible) home screen with the no-account state
  showHome();
}
function showSigninForPending(step) {
  openSignin(step, st.pending_verification_method);
}
/* sign-in overlays: dismiss rules (blocked while a request is in flight) */
$('#signin-close').onclick = () => closeSignin(false);
$('#browser-close').onclick = () => closeSignin(false);
$('#signin-overlay').addEventListener('pointerdown', e => { if (e.target.id === 'signin-overlay') closeSignin(false); });
$('#browser-overlay').addEventListener('pointerdown', e => { if (e.target.id === 'browser-overlay') closeSignin(false); });

async function showHome() {
  document.body.classList.add('in-app');
  $('#signin-overlay').hidden = true;
  $('#browser-overlay').hidden = true;
  $('#app').hidden = false;
  render();
  await loadServers();
  clearInterval(annTimer);
  clearInterval(pollTimer);
  pollTimer = setInterval(refresh, 2500);
}
async function showApp() {
  stopBrowserPoll();
  syncCaptchaModal(null);
  document.body.classList.add('in-app');
  $('#signin-overlay').hidden = true;
  $('#browser-overlay').hidden = true;
  $('#app').hidden = false;
  render();
  await loadServers();
  loadSettings().then(maybeAutoConnect);
  void checkAnnouncements();
  // W-14: after an «Add account» / «Re-login» flow, land back on the accounts panel, not home
  if (openAccountsAfterLogin) {
    openAccountsAfterLogin = false;
    void openAccountsPane();
  }
  clearInterval(annTimer);
  annTimer = setInterval(checkAnnouncements, 5 * 60 * 1000);
  clearInterval(pollTimer);
  pollTimer = setInterval(refresh, 2500);
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
      // a sign-in that is mid-flight (or blocked by 2FA) reopens the popup; otherwise stay on home
      if (st.pending_code || st.pending_unblock) {
        return showSigninForPending(st.pending_code ? 'code' : 'unblock');
      }
      syncExternalChanges();
      render();
      return;
    }
    if (st.lang && st.lang !== lang) return applyLang(st.lang, false);
    syncExternalChanges();
    render();
  } catch (e) { /* keep the last known state */ }
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
// Android parity (RxFoxyNavGraph): never start a connection without an added account — route to Accounts instead.
function hasAnyAccount() {
  const p = st && st.pool;
  if (p && p.available) return (Number(p.total) || 0) > 0;
  return Boolean(st && st.logged_in);
}
// Android HomeScreen: quota card shows only when an account is active; otherwise the no-account card appears.
function hasActiveAccount() {
  const p = st && st.pool;
  if (p && p.available) return Boolean(p.active_account);
  return Boolean(st && st.logged_in);
}
async function doConnect(code) {
  if (busy) return;
  if (!hasAnyAccount()) { void openAccountsPane(); return; }
  if (!hasActiveAccount()) {
    await openAccountsPane();
    toast(t('connect_no_active_account'), 'err');
    return;
  }
  connError = false; clearTimeout(connErrTimer);
  busy = true; render();
  let shouldPing = false;
  try {
    st = await api('connect', {code}); stAt = Date.now();
    shouldPing = true;
    toast(t('t_conn', {n:dispName(byCode(code) || {code, name:code})}));
  } catch (e) {
    if ((e.code === 'NO_ACTIVE_ACCOUNT' || e.data?.error_code === 'NO_ACTIVE_ACCOUNT') && hasAnyAccount()) {
      await openAccountsPane();
      toast(t('connect_no_active_account'), 'err');
      return;
    }
    toast(e.message, 'err');
    if (e.data && e.data.need_login) { busy = false; return openSignin(); }
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
/* ======================================================== events */
$('#power').onclick = toggleConn;
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
$$('[data-copy]').forEach(b => b.onclick = async () => {
  try { await navigator.clipboard.writeText($('#' + b.dataset.copy).textContent); toast(t('t_copied')); }
  catch { toast(t('t_copyfail'), 'err'); }
});
$$('.ls button').forEach(b => b.onclick = () => { if (b.dataset.lang !== langPreference) applyLang(b.dataset.lang, true); });
const loginLanguage = $('#login-language'); if (loginLanguage) loginLanguage.onchange = () => { if (loginLanguage.value !== langPreference) applyLang(loginLanguage.value, true); };
document.addEventListener('change', e => {
  if (e.target.matches('[data-lang-select]') && e.target.value !== langPreference) applyLang(e.target.value, true);
});
document.addEventListener('click', e => {
  const a = e.target.closest('a[target=_blank]'); if (!a) return;
  e.preventDefault();
  api('open_url', {url: a.href}).catch(() => window.open(a.href, '_blank'));
});
$('#close-signup').onclick = () => $('#signup').close();
$('#signup').addEventListener('click', e => { if (e.target === e.currentTarget) e.currentTarget.close(); });
$('#eye').onclick = () => {
  const p = $('#password'), show = p.type === 'password';
  p.type = show ? 'text' : 'password';
  $('#eye').innerHTML = ic(show ? 'i-eye-off' : 'i-eye');
};

