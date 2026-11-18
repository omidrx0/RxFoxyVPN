"use strict";
/* ======================================================== Interactive Fastly CAPTCHA, Browser Auth & Announcements */
let captchaCanceling = false;

function syncCaptchaSubmitState() {
  const answer = $('#captcha-answer');
  const image = $('#captcha-img');
  const submit = $('#btn-captcha-submit');
  if (!answer || !image || !submit) return;
  submit.disabled = answer.disabled || !answer.value.trim() || !image.hasAttribute('src');
}

function syncCaptchaModal(prompt) {
  const overlay = $('#captcha-overlay');
  const dialog = $('#captcha-dialog');
  const inlineHost = $('#signin-captcha-slot');
  const signinOverlay = $('#signin-overlay');
  const signinDialog = $('.signin-dialog');
  if (!overlay || !dialog || !inlineHost) return;
  if (captchaCanceling && prompt && prompt.prompt_id) return;
  if (!prompt || !prompt.prompt_id) {
    overlay.hidden = true;
    inlineHost.hidden = true;
    signinDialog?.classList.remove('captcha-active');
    if (dialog.parentElement !== overlay) overlay.append(dialog);
    dialog.classList.remove('captcha-inline');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    lastCaptchaPromptId = 0;
    return;
  }
  const showInline = Boolean(signinOverlay && !signinOverlay.hidden);
  if (showInline) {
    if (dialog.parentElement !== inlineHost) inlineHost.append(dialog);
    inlineHost.hidden = false;
    overlay.hidden = true;
    dialog.classList.add('captcha-inline');
    dialog.removeAttribute('role');
    dialog.removeAttribute('aria-modal');
    signinDialog?.classList.add('captcha-active');
  } else {
    if (dialog.parentElement !== overlay) overlay.append(dialog);
    inlineHost.hidden = true;
    overlay.hidden = false;
    dialog.classList.remove('captcha-inline');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    signinDialog?.classList.remove('captcha-active');
  }
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

  const image = $('#captcha-img');
  if (prompt.image_data_uri) image.src = prompt.image_data_uri;
  else image.removeAttribute('src');
  image.alt = t('captcha_h');
  const loading = Boolean(prompt.loading);
  $('#captcha-answer').disabled = loading;
  $('#btn-captcha-refresh').disabled = loading;
  syncCaptchaSubmitState();

  if (prompt.prompt_id !== lastCaptchaPromptId) {
    lastCaptchaPromptId = prompt.prompt_id;
    $('#err-captcha').textContent = '';
    $('#captcha-answer').value = '';
    syncCaptchaSubmitState();
    setTimeout(() => $('#captcha-answer')?.focus(), 40);
  }
}

$('#captcha-answer').addEventListener('input', syncCaptchaSubmitState);

async function pollCaptchaOnce() {
  try {
    const r = await api('captcha');
    syncCaptchaModal(r.active ? r.challenge : null);
  } catch (_) {}
}

async function withCaptchaWatch(fn) {
  signinBusyCount++;
  clearInterval(captchaTimer);
  captchaTimer = setInterval(pollCaptchaOnce, 700);
  try {
    return await fn();
  } finally {
    signinBusyCount--;
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
    syncCaptchaSubmitState();
  }
};
$('#btn-captcha-refresh').onclick = async () => {
  $('#err-captcha').textContent = '';
  $('#btn-captcha-refresh').disabled = true;
  try { await api('captcha_refresh', {}); }
  catch (err) { $('#err-captcha').textContent = err.message; $('#btn-captcha-refresh').disabled = false; }
};
async function cancelCaptchaChallenge() {
  captchaCanceling = true;
  syncCaptchaModal(null);
  try { await api('captcha_cancel', {}); } catch (_) {}
  captchaCanceling = false;
  syncCaptchaModal(null);
}
$('#btn-captcha-cancel').onclick = cancelCaptchaChallenge;

/* ---- Browser Sign-In ---- */
function stopBrowserPoll() {
  clearInterval(browserPollTimer);
  browserPollTimer = null;
}
function renderBrowserStatus(bs) {
  const active = Boolean(bs && bs.active);
  $('#tb-browser-back').hidden = !(active && bs.mode === 'webview2');
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
      const message = bs.error || t('e_http', {c: 400});
      if ($('#browser-overlay').hidden) toast(message, 'err');
      else $('#err-browser').textContent = message;
      return;
    }
    if (!bs.active) {
      stopBrowserPoll();
    }
  } catch (_) {}
}
async function startBrowserAuth(mode, showInlineError = true) {
  $('#err-browser').textContent = '';
  $('#btn-browser-webview').disabled = true;
  try {
    const email = $('#browser-email').value.trim() || $('#email').value.trim();
    const bs = await api('browser_login_start', {mode, email});
    renderBrowserStatus(bs);
    stopBrowserPoll();
    if (bs.stage === 'failed') {
      $('#err-browser').textContent = bs.error || t('e_http', {c: 400});
      return;
    }
    browserPollTimer = setInterval(pollBrowserAuth, 1000);
  } catch (err) {
    if (showInlineError) $('#err-browser').textContent = err.message;
    else toast(err.message, 'err');
  } finally {
    $('#btn-browser-webview').disabled = false;
  }
}
$('#btn-browser-webview').onclick = () => startBrowserAuth('webview2');
async function cancelBrowserAuth() {
  stopBrowserPoll();
  try { await api('browser_login_cancel', {}); } catch (_) {}
  renderBrowserStatus({active: false});
  $('#browser-redirect-url').value = '';
}
$('#btn-browser-cancel').onclick = cancelBrowserAuth;
$('#tb-browser-back').onclick = cancelBrowserAuth;
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
function handleLoginResult(d) {
  if (d.need_unblock) {
    openSignin('unblock');
    $('#unblock-code').focus();
    return false;
  }
  if (d.need_code) {
    openSignin('code', d.verification_method || 'email-2fa');
    $('#code').focus();
    return false;
  }
  return true;
}

$('#f-login').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#btn-login'); btn.disabled = true; $('#err-login').textContent = '';
  try {
    const d = await withCaptchaWatch(() => api('login', {email: $('#email').value, password: $('#password').value}));
    $('#password').value = '';
    if (handleLoginResult(d)) { await refresh(); showApp(); }
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
    if (handleLoginResult(result)) { await refresh(); showApp(); }
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
  openSignin();
};
$('#f-unblock').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#btn-unblock'); btn.disabled = true; $('#err-unblock').textContent = '';
  try {
    const d = await withCaptchaWatch(() => api('unblock', {code: $('#unblock-code').value}));
    $('#unblock-code').value = '';
    if (handleLoginResult(d)) { await refresh(); showApp(); }
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
  openSignin();
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

