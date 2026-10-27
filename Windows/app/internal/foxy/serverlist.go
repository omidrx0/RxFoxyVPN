package foxy

import (
	"context"
	"encoding/json"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ServerListRefresh is how often the list is re-downloaded while the app runs.
const ServerListRefresh = 6 * time.Hour

// Target is one tunnel server.
type Target struct {
	Host string
	Port int
}

// Country groups the servers of one country (code "REC" is the recommended / automatic pick).
type Country struct {
	Code    string
	Name    string
	Targets []Target
}

// DisplayName is the name shown in the panel.
func DisplayName(c *Country) string {
	if c.Code == "REC" {
		return "Recommended (auto)"
	}
	return c.Name
}

// SortedCountries puts REC first, then sorts by display name.
func SortedCountries(countries map[string]*Country) []*Country {
	out := make([]*Country, 0, len(countries))
	for _, c := range countries {
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		ri, rj := out[i].Code != "REC", out[j].Code != "REC"
		if ri != rj {
			return !ri
		}
		return strings.ToLower(DisplayName(out[i])) < strings.ToLower(DisplayName(out[j]))
	})
	return out
}

// flexInt accepts a JSON number or a numeric string.
type flexInt int

func (f *flexInt) UnmarshalJSON(b []byte) error {
	s := strings.Trim(strings.TrimSpace(string(b)), `"`)
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	if n, err := strconv.ParseFloat(s, 64); err == nil {
		*f = flexInt(n)
	} else {
		*f = 0
	}
	return nil
}

// truthy follows Python truthiness for the "quarantined" flag.
type truthy bool

func (t *truthy) UnmarshalJSON(b []byte) error {
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		*t = false
		return nil
	}
	switch x := v.(type) {
	case nil:
		*t = false
	case bool:
		*t = truthy(x)
	case float64:
		*t = x != 0
	case string:
		*t = x != ""
	case []any:
		*t = len(x) > 0
	case map[string]any:
		*t = len(x) > 0
	}
	return nil
}

type rawProtocol struct {
	Name string  `json:"name"`
	Host string  `json:"host"`
	Port flexInt `json:"port"`
}

type rawServer struct {
	Hostname    string        `json:"hostname"`
	Port        flexInt       `json:"port"`
	Quarantined truthy        `json:"quarantined"`
	Protocols   []rawProtocol `json:"protocols"`
}

type rawCity struct {
	Servers []rawServer `json:"servers"`
}

type rawCountry struct {
	Code   string    `json:"code"`
	Name   string    `json:"name"`
	Cities []rawCity `json:"cities"`
}

// rawRecord is a Remote Settings record: the country is either nested under "country" or the
// record itself.
type rawRecord struct {
	rawCountry
	Country *rawCountry `json:"country"`
}

// ParseServerList turns the Remote Settings "data" array into countries keyed by upper-case code.
func ParseServerList(records []json.RawMessage) map[string]*Country {
	out := map[string]*Country{}
	for _, rr := range records {
		var rec rawRecord
		if json.Unmarshal(rr, &rec) != nil {
			continue
		}
		c := rec.Country
		if c == nil {
			c = &rec.rawCountry
		}
		if c.Code == "" || strings.ToLower(c.Name) == "catchall anycast" {
			continue
		}
		var targets []Target
		for _, city := range c.Cities {
			for _, srv := range city.Servers {
				if srv.Quarantined {
					continue
				}
				host, port := "", 0
				if len(srv.Protocols) == 0 {
					host, port = srv.Hostname, int(srv.Port)
				} else {
					var p *rawProtocol
					for i := range srv.Protocols {
						if srv.Protocols[i].Name == "connect" {
							p = &srv.Protocols[i]
							break
						}
					}
					if p == nil {
						continue
					}
					host = p.Host
					if host == "" {
						host = srv.Hostname
					}
					port = int(p.Port)
					if port == 0 {
						port = int(srv.Port)
					}
				}
				if host != "" {
					if port == 0 {
						port = 443
					}
					targets = append(targets, Target{host, port})
				}
			}
		}
		if len(targets) == 0 {
			continue
		}
		key := strings.ToUpper(c.Code)
		if existing, ok := out[key]; ok {
			for _, t := range targets {
				if !containsTarget(existing.Targets, t) {
					existing.Targets = append(existing.Targets, t)
				}
			}
		} else {
			out[key] = &Country{Code: key, Name: c.Name, Targets: targets}
		}
	}
	return out
}

func containsTarget(list []Target, t Target) bool {
	for _, x := range list {
		if x == t {
			return true
		}
	}
	return false
}

// FetchCountries downloads and parses the live server list.
func FetchCountries(ctx context.Context, cp *ControlPlane) (map[string]*Country, error) {
	r, err := cp.Request(ctx, "GET", serverListURL, nil, nil)
	if err != nil {
		return nil, err
	}
	if !r.OK() {
		return nil, upstreamf("Remote Settings fetch failed: HTTP %d", r.Status)
	}
	var doc struct {
		Data []json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(r.Body, &doc); err != nil {
		return nil, upstreamf("Remote Settings returned invalid JSON: %v", err)
	}
	countries := ParseServerList(doc.Data)
	slog.Info("server list", "countries", len(countries))
	return countries, nil
}
