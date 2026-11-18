"use strict";
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