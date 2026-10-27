package app

import (
	"context"
	"math"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"rxvpn/internal/apierr"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
)

const pingDialTimeout = 2500 * time.Millisecond

type serverItem struct {
	Code  string `json:"code"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// Servers lists the countries (downloading the list on first use).
func (a *App) Servers(ctx context.Context, _ Body) (any, error) {
	if _, err := a.EnsureCountries(ctx, false); err != nil {
		return nil, err
	}
	items := []serverItem{}
	for _, c := range a.SortedCountries() {
		items = append(items, serverItem{Code: c.Code, Name: foxy.DisplayName(c), Count: len(c.Targets)})
	}
	return map[string]any{"servers": items}, nil
}

// RefreshServers downloads the list again.
func (a *App) RefreshServers(ctx context.Context, _ Body) (any, error) {
	if _, err := a.EnsureCountries(ctx, true); err != nil {
		return nil, err
	}
	return a.Servers(ctx, nil)
}

// ---------------------------------------------------------------- ping

// PingResult is the answer of one country ping.
type PingResult struct {
	MS    *int `json:"ms"`
	OK    int  `json:"ok"`
	Total int  `json:"total"`
}

// tcpMS measures one TCP connect time in milliseconds (DNS is resolved first and NOT counted).
// A non-zero ifIndex pins the socket to that adapter so the probe bypasses the TUN interface.
func tcpMS(ctx context.Context, host string, port, ifIndex int) (float64, bool) {
	rctx, cancel := context.WithTimeout(ctx, pingDialTimeout)
	addrs, err := net.DefaultResolver.LookupIPAddr(rctx, host)
	cancel()
	if err != nil {
		return 0, false
	}
	for _, addr := range addrs {
		d := net.Dialer{Timeout: pingDialTimeout}
		if ifIndex != 0 {
			d.Control = platform.DialControl(ifIndex)
		}
		t0 := time.Now()
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(addr.IP.String(), strconv.Itoa(port)))
		if err != nil {
			continue
		}
		ms := float64(time.Since(t0)) / float64(time.Millisecond)
		_ = conn.Close()
		return ms, true
	}
	return 0, false
}

func pingCountry(ctx context.Context, c *foxy.Country, tries, maxTargets, ifIndex int) PingResult {
	n := min(maxTargets, len(c.Targets))
	picked := rand.Perm(len(c.Targets))[:n]
	best, found, ok := 0.0, false, 0
	for _, i := range picked {
		t := c.Targets[i]
		reached := false
		for k := 0; k < tries; k++ {
			if v, good := tcpMS(ctx, t.Host, t.Port, ifIndex); good {
				reached = true
				if !found || v < best {
					best, found = v, true
				}
			}
		}
		if reached {
			ok++
		}
	}
	res := PingResult{OK: ok, Total: n}
	if found {
		ms := max(1, int(math.Round(best)))
		res.MS = &ms
	}
	return res
}

// tunPingInterface returns the adapter index to pin probes to, and whether pinning is needed
// but impossible (connected in TUN mode without a known physical adapter).
func (a *App) tunPingInterface() (ifIndex int, blocked bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	if a.connected && a.connSettings.Mode == "tun" {
		return a.pingIfIndex, a.pingIfIndex == 0
	}
	return 0, false
}

// Ping measures the latency of one country.
func (a *App) Ping(ctx context.Context, body Body) (any, error) {
	if _, err := a.EnsureCountries(ctx, false); err != nil {
		return nil, err
	}
	c := a.getCountry(strings.ToUpper(str(body["code"])))
	if c == nil {
		return nil, apierr.New(locale.Tr("این کشور در فهرست سرورها نیست.", "This country is not in the server list."), 404)
	}
	idx, blocked := a.tunPingInterface()
	if blocked {
		return PingResult{}, nil
	}
	return pingCountry(ctx, c, 2, 3, idx), nil
}

// PingAll measures every country quickly and returns {code: ms | null}.
func (a *App) PingAll(ctx context.Context, _ Body) (any, error) {
	if _, err := a.EnsureCountries(ctx, false); err != nil {
		return nil, err
	}
	items := a.SortedCountries()
	idx, blocked := a.tunPingInterface()
	out := make(map[string]*int, len(items))
	if blocked {
		for _, c := range items {
			out[c.Code] = nil
		}
		return out, nil
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	jobs := make(chan *foxy.Country)
	for w := 0; w < 16; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for c := range jobs {
				r := pingCountry(ctx, c, 1, 2, idx)
				mu.Lock()
				out[c.Code] = r.MS
				mu.Unlock()
			}
		}()
	}
	for _, c := range items {
		jobs <- c
	}
	close(jobs)
	wg.Wait()
	return out, nil
}

// ---------------------------------------------------------------- flags

var flagRe = regexp.MustCompile(`^[a-z]{2}$`)

// Flag returns the PNG flag of a country: downloaded once, cached on disk afterwards so the
// panel keeps working offline. It returns nil when the flag is not available.
func (a *App) Flag(ctx context.Context, cc string) []byte {
	if !flagRe.MatchString(cc) {
		return nil
	}
	dir := config.FlagDir()
	path := filepath.Join(dir, cc+".png")
	if data, err := os.ReadFile(path); err == nil {
		return data
	}
	rctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	r, err := a.cp.Request(rctx, "GET", "https://flagcdn.com/w80/"+cc+".png",
		map[string]string{"Accept": "*/*"}, nil)
	if err != nil || r.Status != 200 || !strings.HasPrefix(r.Header.Get("Content-Type"), "image/") {
		return nil
	}
	_ = os.MkdirAll(dir, 0o755)
	_ = os.WriteFile(path, r.Body, 0o644)
	return r.Body
}
