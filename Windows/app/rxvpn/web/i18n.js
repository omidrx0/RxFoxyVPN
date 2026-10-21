"use strict";
/* ======================================================== i18n engine
   Texts live in one file per language: lang/fa.js, lang/en.js, ... each one fills I.<code>.
   To add a language: create lang/<code>.js, add its <script> tag in index.html (before app.js)
   and a button with data-lang="<code>" in the language switch. */
const I = {};
const DEFAULT_LANG = 'en', FALLBACK_LANG = 'en';
let lang = DEFAULT_LANG;
const t = (k, v) => {
  let s = (I[lang] && I[lang][k]) ?? (I[FALLBACK_LANG] && I[FALLBACK_LANG][k]) ?? k;
  if (v) for (const x in v) s = s.split('{' + x + '}').join(v[x]);
  return s;
};