"use strict";
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

