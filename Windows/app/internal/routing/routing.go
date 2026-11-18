// Package routing implements the regional "direct routing" policy (W-20): IPv4 CIDRs and
// domain suffixes of one region that sing-box sends straight to the local network instead of
// the tunnel. The zone files are the ones the Android app ships (ipdeny exports), embedded in
// the binary; they are validated line by line before use and never read from disk at runtime.
package routing

import (
	"embed"
	"fmt"
	"log/slog"
	"net/netip"
	"regexp"
	"strings"
	"sync"
)

// Region names the direct-routing region. NONE means no regional rules.
type Region string

const (
	RegionNone Region = "NONE"
	RegionIR   Region = "IR"
	RegionCN   Region = "CN"
	RegionRU   Region = "RU"
	RegionAF   Region = "AF"
	RegionTR   Region = "TR"
	RegionID   Region = "ID"
	RegionBR   Region = "BR"
)

// Regions lists the selectable regions, NONE first (UI order).
var Regions = []Region{RegionNone, RegionIR, RegionCN, RegionRU, RegionAF, RegionTR, RegionID, RegionBR}

//go:embed zones
var zoneFS embed.FS

const maxZoneBytes = 512 << 10 // each embedded zone is far smaller; hard cap as a guard
const maxZoneLines = 20000
const maxCIDRs = 20000

var (
	cidrRe = regexp.MustCompile(`^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$`)

	suffixes = map[Region][]string{
		RegionIR: {"ir", "aparat.com", "digikala.com", "filimo.com", "torob.com", "sheypoor.com", "namnak.com", "tsetmc.com"},
		RegionCN: {"cn", "baidu.com", "taobao.com", "tmall.com", "jd.com", "qq.com", "163.com", "126.com", "weibo.com", "bilibili.com", "zhihu.com", "alipay.com", "sina.com.cn"},
		RegionRU: {"ru", "su", "xn--p1ai", "vk.com", "vk.me", "yandex.com", "dzen.ru", "avito.ru", "ozon.ru", "mail.ru", "ok.ru"},
		RegionAF: {"af", "tolafghan.com", "pajhwok.com"},
		RegionTR: {"tr", "xn--y9a3aq", "trendyol.com", "hepsiburada.com", "n11.com", "sahibinden.com", "gittigidiyor.com"},
		RegionID: {"id", "co.id", "ac.id", "go.id", "or.id", "web.id", "tokopedia.com", "bukalapak.com", "traveloka.com", "blibli.com", "gojek.com"},
		RegionBR: {"br", "com.br", "globo.com", "uol.com.br", "mercadolivre.com.br", "americanas.com.br", "magazineluiza.com.br"},
	}

	zoneFiles = map[Region]string{
		RegionIR: "zones/routing_ir.zone",
		RegionCN: "zones/routing_cn.zone",
		RegionRU: "zones/routing_ru.zone",
		RegionAF: "zones/routing_af.zone",
		RegionTR: "zones/routing_tr.zone",
		RegionID: "zones/routing_id.zone",
		RegionBR: "zones/routing_br.zone",
	}

	mu     sync.Mutex
	cache  = map[Region][]string{}
	loaded = map[Region]int{}
	failed = map[Region]bool{}
)

// Normalize turns an arbitrary string into a valid Region. Unknown values are NONE.
func Normalize(v string) Region {
	switch Region(strings.ToUpper(strings.TrimSpace(v))) {
	case RegionIR, RegionCN, RegionRU, RegionAF, RegionTR, RegionID, RegionBR:
		return Region(strings.ToUpper(strings.TrimSpace(v)))
	}
	return RegionNone
}

// Known reports whether v names a region we can route. Matching is case-insensitive.
func Known(v string) bool {
	switch Region(strings.ToUpper(strings.TrimSpace(v))) {
	case RegionNone, RegionIR, RegionCN, RegionRU, RegionAF, RegionTR, RegionID, RegionBR:
		return true
	}
	return false
}

// CIDRs returns the validated IPv4 CIDRs of the region (a cached copy; callers must not mutate
// the slice), or nil for NONE / a broken zone file. A parse failure is logged and yields nil —
// domain rules still work.
func CIDRs(region Region) []string {
	if region == RegionNone {
		return nil
	}
	file, ok := zoneFiles[region]
	if !ok {
		slog.Warn("unknown routing region", "region", region)
		return nil
	}
	mu.Lock()
	defer mu.Unlock()
	if out, ok := cache[region]; ok {
		return out
	}
	if failed[region] {
		return nil
	}
	out, err := parseZone(region, file)
	if err != nil {
		failed[region] = true
		slog.Warn("routing zone failed to parse", "region", region, "err", err)
		return nil
	}
	cache[region] = out
	loaded[region] = len(out)
	return out
}

// DomainSuffixes returns the region's default domain suffixes, or nil for NONE.
func DomainSuffixes(region Region) []string {
	out := suffixes[region]
	if out == nil {
		return nil
	}
	cp := make([]string, len(out))
	copy(cp, out)
	return cp
}

// Count reports how many CIDRs the region parsed to (0 for NONE / a broken zone).
func Count(region Region) int {
	CIDRs(region)
	mu.Lock()
	defer mu.Unlock()
	return loaded[region]
}

func parseZone(region Region, file string) ([]string, error) {
	raw, err := zoneFS.ReadFile(file)
	if err != nil {
		return nil, err
	}
	if len(raw) > maxZoneBytes {
		return nil, fmt.Errorf("zone file %s exceeds the size cap", file)
	}
	lines := strings.Split(string(raw), "\n")
	if len(lines) > maxZoneLines {
		return nil, fmt.Errorf("zone file %s has %d lines; at most %d allowed", file, len(lines), maxZoneLines)
	}
	seen := map[string]bool{}
	out := []string{}
	for i, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "//") {
			continue
		}
		if !cidrRe.MatchString(line) {
			return nil, fmt.Errorf("zone file %s line %d: invalid CIDR %q", file, i+1, line)
		}
		prefix, err := netip.ParsePrefix(line)
		if err != nil {
			return nil, fmt.Errorf("zone file %s line %d: %w", file, i+1, err)
		}
		if !prefix.Addr().Is4() {
			return nil, fmt.Errorf("zone file %s line %d: not an IPv4 CIDR %q", file, i+1, line)
		}
		masked := prefix.Masked().String()
		if seen[masked] {
			continue
		}
		seen[masked] = true
		out = append(out, masked)
		if len(out) > maxCIDRs {
			return nil, fmt.Errorf("zone file %s: more than %d CIDRs", file, maxCIDRs)
		}
	}
	return out, nil
}
