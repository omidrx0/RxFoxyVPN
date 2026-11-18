"use strict";
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
      releaseUrl: release.releaseUrl || ''
    });
    if (accepted) {
      const url = release.releaseUrl || '';
      if (url) api('open_url', {url}).catch(() => window.open(url, '_blank', 'noopener'));
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

