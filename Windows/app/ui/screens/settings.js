"use strict";
/* ======================================================== settings page */
const SELECT_LANGUAGE_NAMES = {en:'English',fa:'فارسی',ar:'العربية',ko:'한국어',ru:'Русский',zh:'简体中文'};
let rxSelectDialogSelect = null, rxSelectDialogTrigger = null, rxSelectPendingIndex = -1;
function closeRxDropdowns(restoreFocus = true) {
  const overlay = $('#select-modal-overlay');
  if (!overlay || overlay.hidden) return;
  overlay.hidden = true;
  rxSelectDialogTrigger?.setAttribute('aria-expanded', 'false');
  if (restoreFocus && rxSelectDialogTrigger?.isConnected) rxSelectDialogTrigger.focus();
  rxSelectDialogSelect = null;
  rxSelectDialogTrigger = null;
  rxSelectPendingIndex = -1;
}
function selectDialogTitle(select) {
  if (select.matches('[data-lang-select]')) return t('language_dialog_title');
  return select.closest('.set-row')?.querySelector('.set-copy b')?.textContent.trim()
    || select.closest('.conn-field')?.querySelector('span')?.textContent.trim()
    || select.getAttribute('aria-label')
    || t('select_option');
}
function openRxSelectDialog(select, trigger) {
  if (select.disabled || ![...select.options].some(option => !option.disabled)) return;
  closeRxDropdowns(false);
  rxSelectDialogSelect = select;
  rxSelectDialogTrigger = trigger;
  rxSelectPendingIndex = select.selectedIndex >= 0 && !select.options[select.selectedIndex].disabled
    ? select.selectedIndex
    : [...select.options].findIndex(option => !option.disabled);

  const overlay = $('#select-modal-overlay');
  const title = selectDialogTitle(select);
  const selectedLabel = trigger.querySelector('.rx-select-label, [data-select-value]');
  trigger.setAttribute('aria-label', `${title}: ${selectedLabel?.textContent || ''}`);
  $('#select-modal-title').textContent = title;
  const options = $('#select-modal-options');
  options.setAttribute('aria-label', title);
  options.replaceChildren();
  [...select.options].forEach((option, index) => {
    const row = document.createElement('label');
    row.className = 'select-modal-option';
    row.classList.toggle('selected', index === rxSelectPendingIndex);
    row.classList.toggle('disabled', option.disabled);
    const copy = document.createElement('span');
    copy.className = 'select-modal-option-label';
    copy.textContent = option.textContent.trim();
    const indicator = document.createElement('span');
    indicator.className = 'select-modal-radio';
    indicator.setAttribute('aria-hidden', 'true');
    const radio = document.createElement('input');
    radio.type = 'radio';
    radio.name = 'select-modal-option';
    radio.value = String(index);
    radio.checked = index === rxSelectPendingIndex;
    radio.disabled = option.disabled;
    radio.addEventListener('change', () => {
      rxSelectPendingIndex = index;
      options.querySelectorAll('.select-modal-option').forEach((item, itemIndex) => {
        item.classList.toggle('selected', itemIndex === index);
      });
    });
    row.append(copy, indicator, radio);
    options.appendChild(row);
  });
  trigger.setAttribute('aria-expanded', 'true');
  overlay.hidden = false;
  requestAnimationFrame(() => {
    (options.querySelector('input:checked:not(:disabled)') || options.querySelector('input:not(:disabled)'))?.focus();
  });
}
function confirmRxSelectDialog() {
  const select = rxSelectDialogSelect;
  const trigger = rxSelectDialogTrigger;
  const index = rxSelectPendingIndex;
  closeRxDropdowns(false);
  if (!select || index < 0 || index >= select.options.length) return;
  const changed = select.selectedIndex !== index;
  select.selectedIndex = index;
  if (changed) select.dispatchEvent(new Event('change', {bubbles:true}));
  if (select.isConnected) select._rxSelectSync?.();
  if (trigger?.isConnected) trigger.focus();
}
function enhanceRxSelect(select) {
  if (!select || select.dataset.rxEnhanced === '1') return;
  select.dataset.rxEnhanced = '1'; select.classList.add('rx-select-native'); select.tabIndex = -1; select.setAttribute('aria-hidden','true');
  const choiceRow = select.closest('.set-row-choice');
  let trigger, label;
  if (choiceRow) {
    trigger = choiceRow.querySelector('.set-choice-trigger');
    label = choiceRow.querySelector('[data-select-value]');
  } else {
    const wrap=document.createElement('div'); wrap.className='rx-select-wrap';
    select.parentNode.insertBefore(wrap,select); wrap.appendChild(select);
    trigger=document.createElement('button');
    trigger.type='button';
    trigger.className='rx-select-trigger';
    label=document.createElement('span'); label.className='rx-select-label';
    const arrow=document.createElement('span'); arrow.className='rx-select-arrow'; arrow.setAttribute('aria-hidden','true'); arrow.textContent='⌄';
    trigger.append(label,arrow);
    wrap.appendChild(trigger);
  }
  if (!trigger || !label) return;
  trigger.setAttribute('aria-haspopup','dialog');
  trigger.setAttribute('aria-controls','select-modal-overlay');
  trigger.setAttribute('aria-expanded','false');
  function sync(){
    const selected=select.options[select.selectedIndex];
    label.textContent=selected?selected.textContent.trim():'';
    if (select.matches('[data-lang-select]') && langPreference === 'auto') {
      label.textContent = `${t('auto')} (${SELECT_LANGUAGE_NAMES[lang] || SELECT_LANGUAGE_NAMES.en})`;
    }
    trigger.disabled=select.disabled || ![...select.options].some(option => !option.disabled);
    const accessibleName = selectDialogTitle(select);
    if (accessibleName) trigger.setAttribute('aria-label', `${accessibleName}: ${label.textContent}`);
  }
  trigger.addEventListener('click',()=>openRxSelectDialog(select,trigger));
  select.addEventListener('change',sync);
  select._rxSelectSync=sync;
  new MutationObserver(sync).observe(select,{attributes:true,attributeFilter:['disabled'],childList:true,subtree:true});
  sync();
}
function enhanceVisibleDropdowns(root=document) { root.querySelectorAll('select:not(.account-pref-select)').forEach(enhanceRxSelect); }
$('#select-modal-cancel').addEventListener('click',()=>closeRxDropdowns());
$('#select-modal-confirm').addEventListener('click',confirmRxSelectDialog);
$('#select-modal-overlay').addEventListener('click',e=>{if(e.target.id==='select-modal-overlay')closeRxDropdowns();});
document.addEventListener('keydown',e=>{
  if(e.key==='Escape'&&!$('#select-modal-overlay').hidden){
    e.preventDefault();
    e.stopPropagation();
    closeRxDropdowns();
  }
},true);

var settingsState = null, autostartAvailable = false;
var settingsCat = 'general', settingsRestart = false, resetArmed = 0, autoTried = false;
const SET_SCHEMA = {
  general: [
    {k:'lang', type:'lang'},
    {k:'auto_connect', type:'switch'},
    {k:'autostart', type:'switch'},
    {k:'reset', type:'reset'}
  ],
  // Android parity: sign-in method & auto rotation live on the Accounts page (SignInMethod row + rotation Slider)
  accounts: [
    {k:'sign_in_method', type:'select', opts:['direct','browser'], label:'login_method', desc:'login_method_d', optionT:'login_method_'},
    {k:'rotation_pct', type:'range', min:50, max:100, step:5, label:'auto_rotate', desc:'auto_rotate_hint'}
  ],
  logs: [
    {k:'log_level', type:'select', opts:['trace','debug','info','warn','error']},
    {k:'logs', type:'logs'}
  ],
  routing: [
    {k:'routing_region', type:'region', label:'routing_region_label', desc:'region_desc'},
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
    {k:'tun_mtu', type:'number', min:1280, max:9000},
    {k:'strict_route', type:'switch'}
  ],
  about: [
    {k:'about_version', type:'about_version'},
    {k:'about_source', type:'about_source'},
    {k:'about_updates', type:'about_updates'}
  ]
};
const SETTING_ROW_ICONS = {
  lang:['i-globe','cyan'], auto_connect:['i-power','good'], autostart:['i-bolt','blue'], reset:['i-refresh','bad'],
  log_level:['i-list','cyan'], logs:['i-list','cyan'],
  routing_region:['i-globe','blue'], bypass_lan:['i-route','warn'], ipv6:['i-globe','blue'], udp_policy:['i-shield','violet'],
  direct_apps:['i-user-add','pink'], direct_domains:['i-link','pink'], direct_cidrs:['i-route','pink'],
  dns_type:['i-shield','cyan'], dns_server:['i-server','blue'], dns_path:['i-link','blue'], dns_strategy:['i-route','violet'], direct_dns:['i-server','cyan'],
  tun_stack:['i-inbound','blue'], tun_mtu:['i-sliders','cyan'], strict_route:['i-route','warn']
};
const isNarrow = () => matchMedia('(max-width:900px)').matches;

async function loadSettings() {
  try {
    const r = await api('settings');
    settingsState = r.settings;
    loginMethod = settingsState.sign_in_method || loginMethod;
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
  const relayText = (icon, tone, title, description) => `
    <div class="set-txt set-txt-icon">
      <span class="set-setting-icon" data-tone="${tone}">${ic(icon)}</span>
      <span class="set-copy"><b>${esc(t(title))}</b><small>${esc(t(description))}</small></span>
    </div>`;
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
      ${relayText('i-shield', 'cyan', 'relay_shared', 'relay_shared_d')}
      <label class="sw"><input type="checkbox" data-relay-toggle="shared_enabled"${rs.shared_enabled ? ' checked' : ''}><i></i></label>
    </div>
    <div class="set-row col">
      <div style="display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%">
        ${relayText('i-server', 'violet', 'relay_personal', 'relay_personal_d')}
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
      ${relayText('i-route', 'warn', 'relay_personal_only', 'relay_personal_only_d')}
      <label class="sw"><input type="checkbox" data-relay-toggle="personal_only"${rs.personal_only ? ' checked' : ''}${!rs.personal_enabled ? ' disabled' : ''}><i></i></label>
    </div>
    <p class="relay-note">${esc(t('relay_privacy_note'))}</p>
  `;

  const refreshAllRelayViews = () => {
    if (!$('#settings').hidden && settingsCat === 'relay') renderRelayInto($('#set-pane'));
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

function maybeAutoConnect() {
  if (autoTried) return; autoTried = true;
  const code = st.last_country;
  if (!hasAnyAccount()) return; // Android: no account -> no connect, never flip state
  if (settingsState && settingsState.auto_connect && st.state === 'idle' && code && byCode(code) && !busy) {
    selected = code; doConnect(code);
  }
}
function setView(view) {
  $('#settings').dataset.view = view;
  $('#set-title').textContent = (view === 'pane' && isNarrow())
    ? t(settingsCat === 'connection' ? 'set_cat_connection' : 'set_cat_' + settingsCat)
    : t('settings');
}
async function openSettings() {
  if (!settingsState) await loadSettings();
  document.body.classList.add('settings-open');
  $('#settings').hidden = false; $('#settings-btn').classList.add('on');
  setView(isNarrow() ? 'list' : 'pane');
  renderSettings(); renderSettingsState();
}
function closeSettings() {
  restoreConnectionSettingsHome();
  document.body.classList.remove('settings-open');
  const s = $('#settings'); if (s) s.hidden = true;
  const b = $('#settings-btn'); if (b) b.classList.remove('on');
}
function restoreConnectionSettingsHome() {
  const anchor = $('#connection-settings-anchor'), connectionSettings = $('#conn-settings'), proxies = $('#proxies');
  if (anchor && connectionSettings && proxies) anchor.after(connectionSettings, proxies);
}
function refreshSettingsLang() {
  if ($('#settings').hidden) return;
  const languageSelect = $('[data-lang-select]'); if (languageSelect) { languageSelect.value = langPreference; languageSelect._rxSelectSync?.(); }
  setView($('#settings').dataset.view); renderSettings(); renderSettingsState();
}
function settingRow(item) {
  const row = document.createElement('div');
  row.className = 'set-row'; row.dataset.row = item.k;
  const labelKey = 'set_' + item.k, descKey = labelKey + '_d';
  const isAbout = item.type.startsWith('about_');
  const [icon, tone] = SETTING_ROW_ICONS[item.k] || ['i-sliders','cyan'];
  const iconTile = isAbout ? '' : `<span class="set-setting-icon" data-tone="${tone}">${ic(icon)}</span>`;
  const txt = `<div class="set-txt${isAbout ? '' : ' set-txt-icon'}">${iconTile}<span class="set-copy"><b>${esc(t(item.label || labelKey))}</b><small>${esc(t(item.desc || descKey))}</small></span></div>`;
  const choiceTxt = `<span class="set-txt set-txt-icon">${iconTile}<span class="set-copy"><b>${esc(t(item.label || labelKey))}</b><small data-select-value></small></span></span>`;
  const opt = (v, label) => `<option value="${esc(v)}">${esc(label)}</option>`;
  const choice = select => {
    row.classList.add('set-row-choice');
    row.innerHTML = `<button type="button" class="set-choice-trigger">${choiceTxt}<span class="set-choice-chevron" aria-hidden="true">${ic('i-chev')}</span></button><div class="set-choice-select">${select}</div>`;
  };
  if (item.type === 'switch') {
    const off = item.k === 'autostart' && !autostartAvailable ? ' disabled' : '';
    row.innerHTML = txt + `<label class="sw"><input type="checkbox" data-k="${item.k}"${off}><i></i></label>`;
  } else if (item.type === 'select') {
    const optT = item.optionT || ('set_' + item.k + '_');
    choice(`<select data-k="${item.k}">${item.opts.map(v => opt(v, t(optT + v))).join('')}</select>`);
  } else if (item.type === 'region') {
    const REGIONS = ['none','ir','cn','ru','af','tr','id','br'];
    choice(`<select data-k="${item.k}">${REGIONS.map(v => opt(v, t('region_' + v))).join('')}</select>`);
  } else if (item.type === 'range') {
    row.innerHTML = txt + `<div class="set-ctl"><input type="range" class="set-range" data-k="${item.k}" min="${item.min ?? 50}" max="${item.max ?? 100}" step="${item.step ?? 5}" aria-label="${esc(t(item.label || 'set_' + item.k))}"><output class="range-val" data-range-val>95%</output></div>`;
  } else if (item.type === 'lang') {
    choice(`<select data-lang-select>${opt('auto', t('auto'))}${opt('en', SELECT_LANGUAGE_NAMES.en)}${opt('fa', SELECT_LANGUAGE_NAMES.fa)}${opt('ar', SELECT_LANGUAGE_NAMES.ar)}${opt('ko', SELECT_LANGUAGE_NAMES.ko)}${opt('ru', SELECT_LANGUAGE_NAMES.ru)}${opt('zh', SELECT_LANGUAGE_NAMES.zh)}</select>`);
  } else if (item.type === 'text' || item.type === 'number') {
    const num = item.type === 'number' ? ` min="${item.min ?? 0}" max="${item.max ?? 9000}" step="1" inputmode="numeric"` : '';
    row.innerHTML = txt + `<div class="set-ctl"><input type="${item.type}" data-k="${item.k}" placeholder="${esc(item.ph || '')}" autocomplete="off" spellcheck="false"${num}></div>`;
  } else if (item.type === 'list') {
    row.classList.add('col');
    const chips = (item.chips || []).map((c, i) => `<button type="button" class="chip" data-chip="${item.k}:${i}">${esc(t(c.key))}</button>`).join('');
    row.innerHTML = txt + `<textarea data-k="${item.k}" rows="4" placeholder="${esc(item.ph || '')}" spellcheck="false" autocomplete="off"></textarea>` + (chips ? `<div class="set-chips">${chips}</div>` : '');
  } else if (item.type === 'logs') {
    row.classList.add('col');
    row.innerHTML = txt + `<div class="set-chips"><button type="button" class="chip" id="set-logs-btn">${esc(t('set_logs_show'))}</button><button type="button" class="chip" id="set-logs-copy" hidden>${esc(t('logs_copy'))}</button><button type="button" class="chip" id="set-logs-clear" hidden>${esc(t('logs_clear'))}</button><button type="button" class="chip" id="set-logs-hide" hidden>${esc(t('set_logs_hide'))}</button></div><pre class="set-log" id="set-log" hidden></pre>`;
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
function accountPreferenceRow(item) {
  const row = document.createElement('div');
  row.className = `account-pref${item.k === 'rotation_pct' ? ' rotation-pref' : ''}`;
  row.dataset.row = item.k;
  const labelKey = item.label || 'set_' + item.k;
  if (item.k === 'sign_in_method') {
    row.innerHTML = `
      <span class="account-pref-icon">${ic('i-login', 'account-pref-login')}</span>
      <div class="account-pref-copy"><b>${esc(t(labelKey))}</b><small data-pref-current>${esc(t(item.optionT + (settingsState?.sign_in_method || 'direct')))}</small></div>
      <span class="account-pref-tail">${ic('i-chev', 'account-pref-chevron')}</span>
      <button class="account-pref-select" type="button" value="${settingsState?.sign_in_method === 'browser' ? 'browser' : 'direct'}" data-k="${item.k}" aria-label="${esc(t(labelKey))}" aria-haspopup="dialog" aria-controls="signin-method-overlay" aria-expanded="false"></button>`;
  } else {
    const currentValue = Number(settingsState?.rotation_pct) || 95;
    const ticks = Array.from({length: (item.max - item.min) / item.step - 1}, (_, index) => {
      const value = item.min + item.step * (index + 1);
      const position = (value - item.min) / (item.max - item.min) * 100;
      return `<span class="rotation-tick${value <= currentValue ? ' active' : ''}" data-value="${value}" style="inset-inline-start:${position}%"></span>`;
    }).join('');
    const progress = (currentValue - item.min) / (item.max - item.min) * 100;
    row.innerHTML = `
      <div class="rotation-pref-head">
        <span class="account-pref-icon">${ic('i-swap')}</span>
        <b>${esc(t(labelKey))}</b>
        <span class="account-pref-current" data-pref-current>${numFmt(currentValue, 0)}%</span>
      </div>
      <div class="rotation-range" dir="${['fa','ar'].includes(lang) ? 'rtl' : 'ltr'}">
        <div class="rotation-range-track" aria-hidden="true">
          <span class="rotation-range-fill" style="width:${progress}%"></span>
          ${ticks}
        </div>
        <input class="rotation-slider" type="range" min="${item.min}" max="${item.max}" step="${item.step}" value="${currentValue}" data-k="${item.k}" aria-label="${esc(t(labelKey))}">
      </div>
      <small>${esc(t(item.desc))}</small>`;
  }
  return row;
}
let signInMethodTrigger = null;
function syncSignInMethodOptions(method) {
  $$('#signin-method-options input[name="signin-method"]').forEach(input => {
    input.checked = input.value === method;
    input.closest('.signin-method-option')?.classList.toggle('selected', input.checked);
  });
}
function openSignInMethodDialog(trigger) {
  const overlay = $('#signin-method-overlay');
  if (!overlay || !trigger) return;
  signInMethodTrigger = trigger;
  const method = trigger.value === 'browser' ? 'browser' : 'direct';
  syncSignInMethodOptions(method);
  trigger.setAttribute('aria-expanded', 'true');
  overlay.hidden = false;
  requestAnimationFrame(() => $('#signin-method-options input:checked')?.focus());
}
function closeSignInMethodDialog() {
  const overlay = $('#signin-method-overlay');
  if (!overlay || overlay.hidden) return;
  overlay.hidden = true;
  signInMethodTrigger?.setAttribute('aria-expanded', 'false');
  signInMethodTrigger?.focus();
}
async function chooseSignInMethod(method) {
  if (!['direct', 'browser'].includes(method) || $('#signin-method-overlay').hidden) return;
  const trigger = signInMethodTrigger;
  if (!trigger) return;
  trigger.value = method;
  syncSignInMethodOptions(method);
  closeSignInMethodDialog();
  await saveSetting('sign_in_method', method);
}
$('#signin-method-options').addEventListener('click', e => {
  const option = e.target.closest('.signin-method-option');
  const radio = option?.querySelector('input[name="signin-method"]');
  if (radio) void chooseSignInMethod(radio.value);
});
$('#signin-method-options').addEventListener('change', e => {
  const radio = e.target.closest('input[name="signin-method"]');
  if (radio) void chooseSignInMethod(radio.value);
});
$('#signin-method-cancel').onclick = closeSignInMethodDialog;
$('#signin-method-overlay').addEventListener('click', e => {
  if (e.target.id === 'signin-method-overlay') closeSignInMethodDialog();
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && !$('#signin-method-overlay').hidden) {
    e.preventDefault();
    e.stopPropagation();
    closeSignInMethodDialog();
  }
}, true);
function renderSettings() {
  const pane = $('#set-pane'); if (!pane) return;
  $$('.set-nav [data-cat]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.cat === settingsCat)));
  $('#set-cat-title').textContent = t(settingsCat === 'connection' ? 'set_cat_connection' : 'set_cat_' + settingsCat);
  if (settingsCat === 'connection') {
    pane.replaceChildren($('#conn-settings'), $('#proxies'));
    enhanceVisibleDropdowns(pane);
    return;
  }
  restoreConnectionSettingsHome();
  if (settingsCat === 'accounts') {
    // render the skeleton with the last known data immediately, then fetch fresh state
    renderAccountsInto(pane);
    loadPool();
    return;
  }
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
  if (settingsCat === 'logs') {
    window.setTimeout(() => { void showLogs(); }, 0);
  }
}
function fillSettings() {
  if (!settingsState) return;
  $$('#set-pane [data-k]').forEach(el => {
    const v = settingsState[el.dataset.k];
    if (el.dataset.k === 'sign_in_method') el.value = v === 'browser' ? 'browser' : 'direct';
    else if (el.type === 'checkbox') el.checked = !!v;
    else if (el.tagName === 'TEXTAREA') el.value = (v || []).join('\n');
    else el.value = v ?? '';
    if (el.tagName === 'SELECT') el._rxSelectSync?.();
    if (el.type === 'range') {
      const out = el.parentElement && el.parentElement.querySelector('[data-range-val]');
      if (out) out.textContent = `${el.value}%`;
      if (el.classList.contains('rotation-slider')) syncRotationSlider(el);
    }
    if (el.dataset.k === 'sign_in_method' || el.dataset.k === 'rotation_pct') syncAccountPreferenceValue(el);
    if (el.dataset.k === 'routing_region') syncRegionDesc(el);
  });
  const quotaHint = $('#accounts-quota-hint');
  if (quotaHint) quotaHint.textContent = accountsQuotaHint();
  const ls = $('#set-pane [data-lang-select]'); if (ls) { ls.value = langPreference; ls._rxSelectSync?.(); }
  (SET_SCHEMA[settingsCat] || []).forEach(item => {
    if (!item.showIf) return;
    const row = $(`#set-pane [data-row="${item.k}"]`);
    if (row) row.hidden = !item.showIf(settingsState);
  });
}
function syncAccountPreferenceValue(el) {
  const label = el.closest('.account-pref')?.querySelector('[data-pref-current]');
  if (!label) return;
  if (el.dataset.k === 'sign_in_method') {
    label.textContent = t('login_method_' + el.value);
    return;
  }
  label.textContent = `${numFmt(Number(el.value), 0)}%`;
  const hint = $('#accounts-quota-hint');
  if (hint) hint.textContent = accountsQuotaHint(Number(el.value));
}
function syncRotationSlider(el) {
  const min = Number(el.min), max = Number(el.max), value = Number(el.value);
  const range = el.closest('.rotation-range');
  if (!range || !(max > min)) return;
  const progress = Math.max(0, Math.min(100, (value - min) / (max - min) * 100));
  const fill = range.querySelector('.rotation-range-fill');
  if (fill) fill.style.width = `${progress}%`;
  range.querySelectorAll('.rotation-tick').forEach(tick => {
    tick.classList.toggle('active', Number(tick.dataset.value) <= value);
  });
}
function accountsQuotaHint(rotationValue) {
  const value = Number(rotationValue ?? settingsState?.rotation_pct);
  return t('accounts_quota_hint', {percent: numFmt(value >= 50 && value <= 100 ? value : 95, 0)});
}
function renderSettingsState() {
  if (st.state !== 'connected') settingsRestart = false;
  const s = $('#settings'); if (!s || s.hidden) return;
  const tun = (st.connection || {}).mode === 'tun';
  $('#set-tun-note').hidden = tun || ['general', 'relay', 'accounts', 'about', 'logs', 'connection'].includes(settingsCat);
  $('#set-restart').hidden = !settingsRestart;
}
async function saveSetting(key, value) {
  try {
    const r = await api('set_settings', {[key]: value});
    settingsState = r.settings;
    if (settingsState.sign_in_method) loginMethod = settingsState.sign_in_method;
    if (r.restart_needed) settingsRestart = true;
    toast(t('set_saved'));
  } catch (e) { toast(e.message, 'err'); }
  fillSettings(); renderSettingsState();
  if (key === 'rotation_pct') paintPool();
}
async function showLogs() {
  const pre = $('#set-log'); if (!pre) return;
  try {
    const r = await api('tun_log');
    pre.textContent = r.text || t('logs_empty');
    pre.hidden = false; $('#set-logs-hide').hidden = false;
    $('#set-logs-copy').hidden = false; $('#set-logs-clear').hidden = false;
    $('#set-logs-btn').textContent = t('set_logs_refresh');
    pre.scrollTop = pre.scrollHeight;
  } catch (e) { toast(e.message, 'err'); }
}
async function copyLogs() {
  const pre = $('#set-log'); if (!pre) return;
  try {
    await navigator.clipboard.writeText(pre.textContent || '');
    toast(t('logs_copied'));
  } catch { toast(t('e_link'), 'err'); }
}
async function clearLogs() {
  try {
    await api('clear_tun_log', {});
    const pre = $('#set-log');
    if (pre) pre.textContent = '';
    toast(t('logs_clear_done'));
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
$$('.set-nav [data-action="servers"]').forEach(btn => btn.onclick = () => openServerListPage());
$('#set-back').onclick = () => {
  if (isNarrow() && $('#settings').dataset.view === 'pane') setView('list'); else closeSettings();
};
$$('.set-nav [data-cat]').forEach(b => b.onclick = () => {
  settingsCat = b.dataset.cat; setView('pane'); renderSettings(); renderSettingsState();
});
$('#set-pane').addEventListener('change', e => {
  const el = e.target;
  if (el.id === 'pool-import-file') { const f = el.files && el.files[0]; el.value = ''; if (f) poolImportFile(f); return; }
  if (el.matches('[data-lang-select]')) { if (el.value !== langPreference) applyLang(el.value, true); return; }
  const k = el.dataset.k; if (!k) return;
  let v;
  if (el.type === 'checkbox') v = el.checked;
  else if (el.tagName === 'TEXTAREA') v = el.value.split(/[\n,;]+/).map(x => x.trim()).filter(Boolean);
  else if (el.type === 'number' || el.type === 'range') v = el.value === '' ? Number(el.min || 0) : Number(el.value);
  else v = el.value;
  saveSetting(k, v);
  if (k === 'routing_region') syncRegionDesc(el);
});
$('#set-pane').addEventListener('input', e => {
  const el = e.target;
  if (el.type === 'range') {
    if (el.classList.contains('rotation-slider')) {
      syncAccountPreferenceValue(el);
      syncRotationSlider(el);
    }
    const out = el.parentElement && el.parentElement.querySelector('[data-range-val]');
    if (out) out.textContent = `${el.value}%`;
  }
});
function syncRegionDesc(sel) {
  if (sel.closest('.set-row-choice')) { sel._rxSelectSync?.(); return; }
  const small = sel.closest('.set-row')?.querySelector('.set-txt small');
  if (!small) return;
  small.textContent = t(sel.value === 'none' ? 'region_none_desc' : 'region_desc');
}
$('#set-pane').addEventListener('click', e => {
  const methodTrigger = e.target.closest('.account-pref-select');
  if (methodTrigger) { openSignInMethodDialog(methodTrigger); return; }
  const menu = e.target.closest('.pool-menu');
  $$('#set-pane .pool-menu[open]').forEach(open => { if (open !== menu) open.removeAttribute('open'); });
  if (menu && !e.target.closest('.pool-menu-trigger')) menu.removeAttribute('open');
  const poolEl = e.target.closest('[data-pool]');
  if (poolEl && $('#pool-panel')) { handlePoolAction(poolEl); return; }
  if (e.target.closest('#pool-add, #pool-add-icon')) { void openAccountSignin(); return; }
  if (e.target.closest('#pool-refresh-all')) { poolRefreshAll(); return; }
  if (e.target.closest('#pool-export')) { poolExport(); return; }
  if (e.target.closest('#pool-restore')) { const f = $('#pool-import-file'); if (f) f.click(); return; }
  const chip = e.target.closest('[data-chip]');
  if (chip) {
    const [k, i] = chip.dataset.chip.split(':');
    const item = SET_SCHEMA[settingsCat].find(x => x.k === k);
    saveSetting(k, [...(settingsState[k] || []), ...item.chips[+i].add]);
    return;
  }
  if (e.target.closest('#set-logs-btn')) return showLogs();
  if (e.target.closest('#set-logs-copy')) return copyLogs();
  if (e.target.closest('#set-logs-clear')) return clearLogs();
  if (e.target.closest('#set-logs-hide')) {
    $('#set-log').hidden = true; $('#set-logs-hide').hidden = true;
    $('#set-logs-copy').hidden = true; $('#set-logs-clear').hidden = true;
    $('#set-logs-btn').textContent = t('set_logs_show');
    return;
  }
  if (e.target.closest('#set-reset')) resetSettings();
  if (e.target.closest('#set-check-updates')) checkForUpdates(true);
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#settings').hidden) $('#set-back').click(); });


// Branded update dialog: avoid the ugly browser-native window.confirm popup.
function showUpdateDialog({version, notes, releaseUrl}) {
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
        <p class="rx-update-hint">${esc(t('set_update_open_hint'))}</p>
        <div class="rx-update-actions">
          <button type="button" class="ghost rx-update-later">${esc(t('set_update_later'))}</button>
          <button type="button" class="primary rx-update-accept">${esc(t('set_update_view_release'))}</button>
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


