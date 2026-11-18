"use strict";
/* ======================================================== start */
// Apply the saved language immediately, before waiting for the backend status call.
// This prevents the English-first flash when the app is reopened.
const startupLang = (() => { try { const saved = localStorage.getItem('rxfoxyvpn-lang'); if (saved === 'auto' || I[saved]) return saved; } catch {} const serverLang = document.documentElement.lang; return I[serverLang] ? serverLang : 'en'; })();
enhanceVisibleDropdowns(document);
applyLang(startupLang, false);
(async () => {
  try {
    st = await api('status'); stAt = Date.now();
    syncCaptchaModal(st.captcha_active ? st.captcha : null);
    if (st.logged_in) { showApp(); }
    else if (st.pending_code || st.pending_unblock) {
      // resume an interrupted sign-in (2FA / unblock) instead of stranding the user
      document.body.classList.add('in-app');
      $('#app').hidden = false;
      render();
      void loadServers();
      showSigninForPending(st.pending_code ? 'code' : 'unblock');
      loadSettings().then(() => { if (settingsState && settingsState.sign_in_method) loginMethod = settingsState.sign_in_method; });
    }
    else { void showHome(); }
  } catch (e) { showHome(); toast(e.message, 'err'); }
})();

