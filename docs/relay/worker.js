const SECRET = "";

const ALLOWED = [
  "mozilla.org", "mozilla.com", "firefox.com", // accounts, Guardian, server list
  "github.com", "githubusercontent.com", "jsdelivr.net", // geo files, flags
];

// Headers that describe this hop, not the request.
const DROP = ["host", "connection", "content-length", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "forwarded", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-real-ip", "true-client-ip"];

function fail(status, why) {
  return new Response(why, { status, headers: { "X-Relay-Error": why, "Content-Type": "text/plain" } });
}

const STATES = {
  works: {
    status: 200,
    color: "#34d399",
    badge: "Online",
    title: "Relay active",
    text: "Copy this URL into RxVpN under Connection settings to use the relay.",
  },
  wrong: {
    status: 403,
    color: "#f87171",
    badge: "Denied",
    title: "Invalid secret",
    text: "The secret in the URL does not match. Append <code>?secret=YOUR_SECRET</code> using the value set in the worker.",
  },
  unset: {
    status: 503,
    color: "#fbbf24",
    badge: "Setup needed",
    title: "Secret missing",
    text: "Define <code>SECRET</code> in the worker script (or as a binding), redeploy, then open this page with <code>?secret=…</code>.",
  },
};

function page(state) {
  const s = STATES[state];
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RxVpN · Relay</title>
<style>
  :root {
    --bg: #07070f;
    --surface: #12121f;
    --border: rgba(148, 163, 255, 0.12);
    --text: #e8e8f0;
    --dim: #8b8ba3;
    --accent: #6366f1;
    --accent-soft: rgba(99, 102, 241, 0.15);
    --status: ${s.color};
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
    font-family: "Inter", system-ui, -apple-system, sans-serif;
    background: var(--bg);
    color: var(--text);
    background-image:
      radial-gradient(ellipse 60% 40% at 50% -20%, rgba(99,102,241,0.18), transparent),
      radial-gradient(ellipse 40% 30% at 80% 80%, rgba(139,92,246,0.1), transparent);
  }
  .wrap {
    width: 100%;
    max-width: 400px;
  }
  .panel {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 28px 24px;
    position: relative;
  }
  .panel::after {
    content: "";
    position: absolute;
    top: 0; left: 24px; right: 24px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(99,102,241,0.4), transparent);
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 22px;
  }
  .name {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    font-weight: 600;
    letter-spacing: 0.02em;
  }
  .mark {
    width: 32px;
    height: 32px;
    border-radius: 9px;
    background: linear-gradient(145deg, #6366f1, #8b5cf6);
    display: grid;
    place-items: center;
    font-size: 13px;
    font-weight: 800;
    color: #fff;
    letter-spacing: -0.03em;
  }
  .status {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--status);
    background: color-mix(in srgb, var(--status) 14%, transparent);
    border: 1px solid color-mix(in srgb, var(--status) 30%, transparent);
    padding: 5px 11px;
    border-radius: 20px;
  }
  h1 {
    font-size: 20px;
    font-weight: 650;
    margin-bottom: 8px;
    letter-spacing: -0.02em;
  }
  .desc {
    font-size: 14px;
    line-height: 1.55;
    color: var(--dim);
  }
  .desc code {
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    font-size: 12.5px;
    background: var(--accent-soft);
    color: #a5b4fc;
    padding: 1px 6px;
    border-radius: 4px;
  }
  .foot {
    margin-top: 24px;
    padding-top: 16px;
    border-top: 1px solid var(--border);
    font-size: 12px;
    color: var(--dim);
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--status);
    box-shadow: 0 0 8px var(--status);
  }
</style>
</head>
<body>
  <div class="wrap">
    <div class="panel">
      <div class="top">
        <div class="name">
          <div class="mark">Rx</div>
          RxVpN Relay
        </div>
        <span class="status">${s.badge}</span>
      </div>
      <h1>${s.title}</h1>
      <p class="desc">${s.text}</p>
      <div class="foot">
        <span class="dot"></span>
        Cloudflare Worker endpoint
      </div>
    </div>
  </div>
</body>
</html>`;
  return new Response(html, { status: s.status, headers: { "Content-Type": "text/html; charset=utf-8" } });
}

function health() {
  return new Response(JSON.stringify({
    relay: "rxvpn", version: 1, ready: true,
  }), {
    status: 200,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

export default {
  async fetch(request, env = {}) {
    const here = new URL(request.url);
    const probe = here.searchParams.get("health") === "1";
    const browser = !here.searchParams.has("url") && !probe;
    const secret = env.SECRET || SECRET;
    if (!secret) return browser ? page("unset") : fail(503, "no secret is set in the relay");
    if (here.searchParams.get("secret") !== secret) return browser ? page("wrong") : fail(403, "wrong secret");
    if (probe) {
      if (request.method !== "GET") return fail(405, "health requires GET");
      return health();
    }
    if (browser) return page("works");
    let target;
    try {
      target = new URL(here.searchParams.get("url") || "");
    } catch {
      return fail(400, "url must be an https URL");
    }
    if (target.protocol !== "https:") return fail(400, "url must be an https URL");
    if (target.username || target.password || target.hash || (target.port && target.port !== "443")) {
      return fail(400, "target must not contain credentials, a fragment or a non-HTTPS port");
    }
    const host = target.hostname.toLowerCase();
    if (!ALLOWED.some((domain) => host === domain || host.endsWith("." + domain))) {
      return fail(403, `host not allowed: ${host}`);
    }
    const headers = new Headers();
    for (const [name, value] of request.headers) {
      const lower = name.toLowerCase();
      if (DROP.includes(lower) || lower.startsWith("cf-")) continue;
      headers.set(name, value);
    }
    const hasBody = request.method !== "GET" && request.method !== "HEAD";
    const body = hasBody ? await request.arrayBuffer() : undefined;
    try {
      const upstream = await fetch(target, { method: request.method, headers, body, redirect: "manual" });
      const responseHeaders = new Headers(upstream.headers);
      responseHeaders.set("X-RxVpN-Relay", "1");
      return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: responseHeaders });
    } catch {
      return fail(502, "upstream request failed");
    }
  },
};
