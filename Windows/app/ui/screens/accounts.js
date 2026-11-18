"use strict";
/* ======================================================== accounts pool (W-14/W-27) */
let poolState = null;
const lc = () => lang === 'fa' ? 'fa-IR' : lang === 'ko' ? 'ko-KR' : lang === 'zh' ? 'zh-CN' : lang === 'ar' ? 'ar' : lang === 'ru' ? 'ru' : 'en-US';
const dtMedium = iso => { if (!iso) return ''; const d = new Date(iso); return isNaN(d) ? '' : new Intl.DateTimeFormat(lc(), {dateStyle:'medium'}).format(d); };
const numFmt = (n, digits = 1) => new Intl.NumberFormat(lc(), {maximumFractionDigits: digits}).format(n);

async function loadPool() {
  try { poolState = await api('pool_accounts'); }
  catch (e) { poolState = null; toast(e.message, 'err'); }
  paintPool();
}
function statusBadge(a) {
  const active = Boolean(a.active);
  switch (a.status) {
    case 'ready': return `<span class="pool-status ${active ? 'on' : 'ok'}">${esc(t(active ? 'acc_active' : 'acc_status_ready'))}</span>`;
    case 'reserved': return `<span class="pool-status warn">${esc(t('acc_status_reserved'))}</span>`;
    case 'reauth_required': return `<span class="pool-status bad">${esc(t('acc_status_reauth'))}</span>`;
    case 'unavailable': return `<span class="pool-status mute">${esc(t('acc_status_unavailable'))}</span>`;
    default: return '';
  }
}
function accountReason(a) {
  const keys = {
    quota_exhausted: 'acc_reason_quota_exhausted',
    sign_in_required: 'acc_reason_signin_required',
    token_invalid: 'acc_reason_signin_required',
    not_entitled: 'acc_reason_not_entitled'
  };
  const key = keys[a.reason_code];
  return key ? t(key) : '';
}
function usageHTML(a) {
  const u = a.usage;
  const reason = accountReason(a);
  const retryAt = a.retry_at ? new Date(a.retry_at) : null;
  const showRetryAt = retryAt && !Number.isNaN(retryAt.getTime()) && retryAt.getTime() > Date.now()
    && ['reserved', 'unavailable'].includes(a.status);
  const reasonHTML = reason ? `<div class="pool-reason">${esc(reason)}</div>` : '';
  const retryHTML = showRetryAt
    ? `<div class="pool-retry">${esc(t('acc_back_on', {date: new Intl.DateTimeFormat(lc(), {dateStyle:'medium', timeStyle:'short'}).format(retryAt)}))}</div>` : '';
  if (!u || (!u.unlimited && (!Number.isFinite(Number(u.limit_bytes)) || Number(u.limit_bytes) <= 0))) {
    return `${reasonHTML}${retryHTML}<div class="pool-usage-txt dim">${esc(t('acc_quota_unknown'))}</div>`;
  }
  if (u.unlimited) {
    return `${reasonHTML}${retryHTML}<div class="pool-usage-txt">${esc(t('acc_unlimited'))}</div>`;
  }
  const limit = Number(u.limit_bytes), used = Math.max(0, Number(u.used_bytes) || 0);
  const percent = Math.max(0, Math.min(100, used / limit * 100));
  const wholePercent = Math.trunc(percent);
  const rawThreshold = Number(settingsState?.rotation_pct ?? poolState?.threshold_percent);
  const threshold = Math.max(50, Math.min(100, rawThreshold || 95));
  const crossed = percent >= threshold;
  const resetAt = u.reset_at ? new Date(u.reset_at) : null;
  const resetText = resetAt && !Number.isNaN(resetAt.getTime()) && resetAt.getTime() > Date.now()
    ? ` · ${esc(t('acc_reset', {date: dtMedium(u.reset_at)}))}` : '';
  const quotaText = t('acc_used_of', {used: formatBytes(used), total: formatBytes(limit)}) + resetText;
  return `${reasonHTML}${retryHTML}
    <div class="pool-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${wholePercent}" aria-valuetext="${esc(numFmt(wholePercent, 0) + '%')}">
      <span class="pool-fill${crossed ? ' over' : ''}" style="width:${percent}%"></span>
      <span class="pool-mark" style="inset-inline-start:${threshold}%"></span>
    </div>
    <div class="pool-quota-line"><span class="pool-usage-txt">${esc(quotaText)}</span><b>${numFmt(wholePercent, 0)}%</b></div>`;
}
const ARROW_UP = '<svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5"/><path d="M5 12l7-7 7 7"/></svg>';
const ARROW_DOWN = '<svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M19 12l-7 7-7-7"/></svg>';
const MORE_DOTS = '<svg class="i" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/></svg>';
function poolRowHTML(a, i, total) {
  const active = Boolean(a.active);
  const id = esc(a.id);
  const useBtn = !active && a.enabled && a.status === 'ready'
    ? `<button type="button" class="acc-select" data-pool="activate" data-id="${id}" data-email="${esc(a.email)}">${esc(t('acc_select'))}</button>` : '';
  const statusColor = a.status === 'reauth_required' ? 'bad'
    : a.status === 'unavailable' ? 'mute'
      : a.status === 'reserved' ? 'warn' : active ? 'on' : 'ok';
  const rowStatus = statusBadge(a);
  const refreshBtn = `<button type="button" class="pool-menu-item" data-pool="refresh" data-id="${id}">${ic('i-refresh')}${esc(t('acc_refresh'))}</button>`;
  const removeBtn = `<button type="button" class="pool-menu-item danger" data-pool="remove" data-id="${id}" data-email="${esc(a.email)}">${ic('i-trash')}${esc(t('acc_remove'))}</button>`;
  const toggleTitle = t(a.enabled ? 'acc_disable' : 'acc_enable');
  const menu = `<details class="pool-menu pool-account-menu">
    <summary class="pool-menu-trigger" aria-label="${esc(t('acc_more_actions'))}">${MORE_DOTS}</summary>
    <div class="pool-menu-popup">${refreshBtn}${removeBtn}</div>
  </details>`;
  const moveBtns = `<div class="pool-order">
      <button type="button" class="pool-move" data-pool="move" data-id="${id}" data-delta="-1" ${i === 0 ? 'disabled' : ''} aria-label="${esc(t('acc_move_up'))}">${ARROW_UP}</button>
      <button type="button" class="pool-move" data-pool="move" data-id="${id}" data-delta="1" ${i === total - 1 ? 'disabled' : ''} aria-label="${esc(t('acc_move_down'))}">${ARROW_DOWN}</button>
    </div>`;
  const signInAgain = a.status === 'reauth_required'
    ? `<button type="button" class="acc-select reauth" data-pool="relogin" data-email="${esc(a.email)}">${esc(t('acc_relogin'))}</button>` : '';
  return `<article class="pool-row${active ? ' active' : ''}${a.enabled ? '' : ' is-disabled'}" dir="${['fa','ar'].includes(lang) ? 'rtl' : 'ltr'}">
    <div class="pool-row-head">
      <span class="pool-rank ${statusColor}">${numFmt(i + 1, 0)}</span>
      <div class="pool-info">
        <b class="pool-email">${esc(a.email)}</b>
        ${rowStatus}
      </div>
      <button type="button" class="acc-toggle" role="switch" aria-checked="${a.enabled}" aria-label="${esc(toggleTitle)}" data-pool="toggle" data-id="${id}" data-enabled="${a.enabled}"><span></span></button>
    </div>
    <div class="pool-details">${usageHTML(a)}</div>
    <div class="pool-row-actions">${moveBtns}${signInAgain}${useBtn}<span class="pool-actions-spacer"></span>${menu}</div>
  </article>`;
}
function paintPool() {
  const list = $('#pool-list'); if (!list) return;
  const data = poolState;
  const sectionTitle = list.closest('.accounts-section')?.querySelector('.accounts-section-title');
  if (sectionTitle) sectionTitle.hidden = !data || !data.available || !Array.isArray(data.accounts) || data.accounts.length === 0;
  const refreshButton = $('#pool-refresh-all');
  if (refreshButton && !refreshButton.classList.contains('is-loading')) {
    refreshButton.disabled = !data || !data.available || !Array.isArray(data.accounts) || data.accounts.length === 0;
  }
  if (!data || !data.available || !Array.isArray(data.accounts)) {
    list.innerHTML = `<div class="pool-empty"><span class="pool-empty-icon">${ic('i-user-add')}</span><b>${esc(t('accounts_none'))}</b><small>${esc(t('accounts_none_d'))}</small><button type="button" class="pool-add" id="pool-add">${esc(t('accounts_add'))}</button></div>`;
    return;
  }
  if (!data.accounts.length) {
    list.innerHTML = `<div class="pool-empty"><span class="pool-empty-icon">${ic('i-user-add')}</span><b>${esc(t('accounts_none'))}</b><small>${esc(t('accounts_none_d'))}</small><button type="button" class="pool-add" id="pool-add">${esc(t('accounts_add'))}</button></div>`;
    return;
  }
  list.innerHTML = `<div class="set-card pool-card">${data.accounts.map((a, i) => poolRowHTML(a, i, data.accounts.length)).join('')}
    <button type="button" class="pool-add pool-add-wide" id="pool-add">${esc(t('accounts_add'))}</button></div>`;
}
function renderAccountsInto(pane) {
  const prefs = SET_SCHEMA.accounts.map(accountPreferenceRow).map(el => el.outerHTML).join(`<div class="account-pref-divider"></div>`);
  pane.innerHTML = `<div class="accounts-page" id="pool-panel">
    <div class="accounts-toolbar">
      <button type="button" class="pool-menu-trigger pool-refresh" id="pool-refresh-all" aria-label="${esc(t('acc_refresh_all'))}">${ic('i-refresh')}</button>
      <button type="button" class="pool-menu-trigger pool-add-icon" id="pool-add-icon" aria-label="${esc(t('accounts_add'))}">${ic('i-add')}</button>
      <details class="pool-menu pool-tools">
        <summary class="pool-menu-trigger" aria-label="${esc(t('accounts_menu'))}">${MORE_DOTS}</summary>
        <div class="pool-menu-popup">
          <button type="button" class="pool-menu-item" id="pool-export">${ic('i-download')}${esc(t('accounts_export_backup'))}</button>
          <button type="button" class="pool-menu-item" id="pool-restore">${ic('i-upload')}${esc(t('accounts_import_backup'))}</button>
          <input type="file" id="pool-import-file" accept=".zip,application/zip" hidden>
        </div>
      </details>
    </div>
    <div class="accounts-section">
      <h3 class="accounts-section-title">${esc(t('accounts_list_section'))}</h3>
      <div class="pool-list" id="pool-list"></div>
    </div>
    <div class="accounts-section">
      <h3 class="accounts-section-title">${esc(t('accounts_options_section'))}</h3>
      <div class="accounts-prefs set-card">
      ${prefs}
      </div>
    </div>
    <div class="set-card accounts-quota-hint">
      <span class="accounts-quota-icon"><svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/></svg></span>
      <span id="accounts-quota-hint">${esc(accountsQuotaHint())}</span>
    </div>
    <a class="set-card accounts-signup" href="https://accounts.firefox.com" target="_blank" rel="noopener noreferrer">
      <span class="accounts-signup-icon">${ic('i-user-add')}</span>
      <div class="accounts-signup-copy">
        <b>${esc(t('accounts_no_firefox'))}</b>
        <p>${esc(t('accounts_signup_hint'))}</p>
      </div>
      <span class="accounts-signup-open" aria-hidden="true"><svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h6"/></svg></span>
    </a>
  </div>`;
  fillSettings();
  enhanceVisibleDropdowns(pane);
  paintPool();
}
function confirmOverlay({ kicker, title, bodyHTML, accept, acceptClass }) {
  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.className = 'rx-update-overlay';
    overlay.innerHTML = `<section class="rx-update-dialog" role="dialog" aria-modal="true" aria-labelledby="rx-confirm-title">
      ${kicker ? `<div class="rx-update-kicker">${esc(kicker)}</div>` : ''}
      <h2 id="rx-confirm-title">${esc(title)}</h2>
      <div class="rx-confirm-body">${bodyHTML}</div>
      <div class="rx-update-actions">
        <button type="button" class="ghost rx-confirm-cancel">${esc(t('backup_cancel'))}</button>
        <button type="button" class="primary ${acceptClass || ''} rx-confirm-accept">${esc(accept)}</button>
      </div></section>`;
    const onKey = e => { if (e.key === 'Escape') finish(false); };
    const finish = v => { document.removeEventListener('keydown', onKey, true); overlay.remove(); resolve(v); };
    overlay.addEventListener('click', e => { if (e.target === overlay) finish(false); });
    overlay.querySelector('.rx-confirm-cancel').addEventListener('click', () => finish(false));
    overlay.querySelector('.rx-confirm-accept').addEventListener('click', () => finish(true));
    document.addEventListener('keydown', onKey, true);
    document.body.appendChild(overlay);
    overlay.querySelector('.rx-confirm-accept').focus();
  });
}
async function handlePoolAction(el) {
  const action = el.dataset.pool, id = el.dataset.id;
  if (el.disabled) return;
  el.disabled = true;
  try {
    switch (action) {
      case 'activate': {
        await api('pool_activate', { id });
        toast(t('acc_activated', { email: el.dataset.email || '' }));
        break;
      }
      case 'toggle': {
        await api('pool_toggle', { id, enabled: el.dataset.enabled !== 'true' });
        break;
      }
      case 'move': {
        await api('pool_move', { id, delta: Number(el.dataset.delta) });
        break;
      }
      case 'refresh': {
        await api('pool_refresh', { id });
        toast(t('account_refreshed'));
        break;
      }
      case 'remove': {
        el.disabled = false;
        const ok = await confirmOverlay({
          title: t('acc_remove_confirm_title'),
          bodyHTML: `<p>${esc(t('acc_remove_confirm', {email: el.dataset.email || ''}))}</p>`,
          accept: t('acc_remove'),
          acceptClass: 'danger'
        });
        if (!ok) return;
        await api('pool_remove', { id });
        break;
      }
      case 'relogin': {
        el.disabled = false;
        void openAccountSignin(el.dataset.email || '');
        return;
      }
    }
  } catch (err) {
    toast(err.message, 'err');
    el.disabled = false;
    return;
  }
  await loadPool();
}
async function poolRefreshAll() {
  const btn = $('#pool-refresh-all'); if (!btn || btn.disabled) return;
  btn.disabled = true;
  btn.classList.add('is-loading');
  try {
    const r = await api('pool_refresh_all', {});
    const failed = Number(r.failed) || 0;
    if (failed > 0) toast(t('accounts_refreshed_failed', { n: failed }), 'err');
    else toast(t('accounts_refreshed', { n: Number(r.refreshed) || 0 }));
  } catch (e) { toast(e.message, 'err'); }
  await loadPool();
  btn.classList.remove('is-loading');
  btn.disabled = !poolState || !poolState.available || !Array.isArray(poolState.accounts) || poolState.accounts.length === 0;
}
async function poolExport() {
  // Section 7: native Windows Save dialog (window.rxbackup_save, bound by the desktop shell).
  // No browser download — the chosen path is written by the Go side, atomically.
  const save = typeof window !== 'undefined' ? window.rxbackup_save : null;
  const btn = $('#pool-export');
  if (typeof save !== 'function') {
    toast(t('backup_export_failed'), 'err');
    return;
  }
  if (btn) btn.disabled = true;
  try {
    const r = await save(t('backup_export'), t('backup_zip_filter'));
    if (!r || typeof r !== 'object') {
      throw new Error(t('backup_export_failed'));
    }
    if (r.canceled) return; // user cancelled: no file, no error
    if (r.ok) {
      toast(t('backup_export_done', {p: r.path || ''}));
      return;
    }
    throw new Error(r.error || t('backup_export_failed'));
  } catch (e) {
    toast((e && e.message) || t('backup_export_failed'), 'err');
  } finally { if (btn) btn.disabled = false; }
}
async function rawBackupCall(path, raw) {
  const r = await fetch('/api/' + path, {
    method: 'POST',
    headers: { 'X-RXVPN': '1', 'Content-Type': 'application/octet-stream' },
    body: raw
  });
  let d = {}; try { d = await r.json(); } catch {}
  if (!r.ok) { const info = apiErrorText(d, r.status); const e = new Error(info.text); e.code = info.code; throw e; }
  return d;
}
async function poolImportFile(file) {
  let raw;
  try { raw = await file.arrayBuffer(); }
  catch { toast(t('backup_export_failed_short'), 'err'); return; }
  let inspect;
  try { inspect = await rawBackupCall('backup_inspect', raw); }
  catch (e) { toast(e.message, 'err'); return; }
  const created = inspect.created_at_epoch
    ? new Intl.DateTimeFormat(lc(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(Number(inspect.created_at_epoch) * 1000))
    : '';
  const rows = (inspect.accounts || []).map(a =>
    `<li><span dir="ltr">${esc(a.email)}</span><i class="${a.exists ? 'upd' : ''}">${esc(t(a.exists ? 'backup_update' : 'backup_new'))}</i></li>`).join('');
  const body = `<p>${esc(t('backup_restore_body', { n: Number(inspect.accounts_count) || 0, new: Number(inspect.new_count) || 0, updates: Number(inspect.update_count) || 0 }))}</p>`
    + (created ? `<p class="dim">${esc(t('backup_created_at', { date: created }))}</p>` : '')
    + (rows ? `<ul class="backup-rows">${rows}</ul>` : '');
  const ok = await confirmOverlay({
    kicker: t('backup_restore'),
    title: t('backup_restore_title'),
    bodyHTML: body,
    accept: t('backup_restore_confirm')
  });
  if (!ok) return;
  try {
    const r = await rawBackupCall('backup_import', raw);
    toast(t('backup_done', { added: Number(r.added) || 0, updated: Number(r.updated) || 0 }));
    await loadPool();
  } catch (e) { toast(e.message, 'err'); }
}

