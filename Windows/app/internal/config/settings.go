package config

import (
	"fmt"
	"net/netip"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Settings is the flat set of options shown in the panel as General / Routing / DNS / Inbound.
// The JSON keys are the ones the panel (web/app.js) already uses.
type Settings struct {
	// general
	AutoConnect bool   `json:"auto_connect"`
	Autostart   bool   `json:"autostart"`
	LogLevel    string `json:"log_level"`
	// routing (TUN)
	BypassLAN     bool     `json:"bypass_lan"`
	IPv6          bool     `json:"ipv6"`
	UDPPolicy     string   `json:"udp_policy"`
	DirectApps    []string `json:"direct_apps"`
	DirectDomains []string `json:"direct_domains"`
	DirectCIDRs   []string `json:"direct_cidrs"`
	// dns (TUN)
	DNSType     string `json:"dns_type"`
	DNSServer   string `json:"dns_server"`
	DNSPath     string `json:"dns_path"`
	DNSStrategy string `json:"dns_strategy"`
	DirectDNS   string `json:"direct_dns"`
	// inbound (TUN)
	TunStack    string `json:"tun_stack"`
	TunMTU      int    `json:"tun_mtu"`
	StrictRoute bool   `json:"strict_route"`
}

// DefaultSettings returns the factory settings.
func DefaultSettings() Settings {
	return Settings{
		LogLevel:      "info",
		BypassLAN:     true,
		IPv6:          true,
		UDPPolicy:     "block",
		DirectApps:    []string{},
		DirectDomains: []string{},
		DirectCIDRs:   []string{},
		DNSType:       "https",
		DNSServer:     "1.1.1.1",
		DNSPath:       "/dns-query",
		DNSStrategy:   "prefer_ipv4",
		DirectDNS:     "1.1.1.1",
		TunStack:      "auto",
		TunMTU:        0,
		StrictRoute:   true,
	}
}

var choices = map[string][]string{
	"log_level":    {"trace", "debug", "info", "warn", "error"},
	"udp_policy":   {"block", "direct"},
	"dns_type":     {"https", "tls", "tcp"},
	"dns_strategy": {"prefer_ipv4", "ipv4_only", "prefer_ipv6", "ipv6_only"},
	"tun_stack":    {"auto", "mixed", "system", "gvisor"},
}

const listLimit = 100

var (
	appRe   = regexp.MustCompile(`^[A-Za-z0-9_.()+\- ]{1,80}$`)
	labelRe = regexp.MustCompile(`^[a-z0-9_]([a-z0-9_\-]{0,61}[a-z0-9_])?$`)
	pathRe  = regexp.MustCompile(`^/[A-Za-z0-9._~%/\-]{0,120}$`)
	splitRe = regexp.MustCompile(`[\r\n,;]+`)
)

// SettingError is an invalid setting value; it carries a Persian and an English message.
type SettingError struct{ FA, EN string }

func (e *SettingError) Error() string { return e.EN }

func serr(fa, en string) *SettingError { return &SettingError{FA: fa, EN: en} }

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func splitItems(value any) ([]string, error) {
	var raw []string
	switch v := value.(type) {
	case string:
		raw = splitRe.Split(v, -1)
	case []string:
		raw = v
	case []any:
		for _, x := range v {
			raw = append(raw, fmt.Sprint(x))
		}
	default:
		return nil, serr("مقدار باید فهرست باشد.", "The value must be a list.")
	}
	items := []string{}
	for _, r := range raw {
		item := strings.TrimSpace(r)
		if item != "" && !contains(items, item) {
			items = append(items, item)
		}
	}
	if len(items) > listLimit {
		return nil, serr(fmt.Sprintf("حداکثر %d مورد مجاز است.", listLimit),
			fmt.Sprintf("At most %d entries are allowed.", listLimit))
	}
	return items, nil
}

func cleanApp(item string) (string, error) {
	if !appRe.MatchString(item) || strings.Trim(item, ". ") == "" {
		return "", serr("نام برنامه نامعتبر است: "+item, "Invalid program name: "+item)
	}
	return item, nil
}

func cleanDomain(item string) (string, error) {
	domain := strings.ToLower(item)
	domain = strings.TrimLeft(domain, "*")
	domain = strings.TrimLeft(domain, ".")
	domain = strings.TrimRight(domain, ".")
	bad := serr("دامنه نامعتبر است: "+item, "Invalid domain: "+item)
	if domain == "" || len(domain) > 253 {
		return "", bad
	}
	for _, label := range strings.Split(domain, ".") {
		if !labelRe.MatchString(label) {
			return "", bad
		}
	}
	return domain, nil
}

func cleanCIDR(item string) (string, error) {
	bad := serr("IP یا CIDR نامعتبر است: "+item, "Invalid IP or CIDR: "+item)
	var prefix netip.Prefix
	if strings.Contains(item, "/") {
		p, err := netip.ParsePrefix(item)
		if err != nil {
			return "", bad
		}
		prefix = p.Masked()
	} else {
		a, err := netip.ParseAddr(item)
		if err != nil || a.Zone() != "" {
			return "", bad
		}
		prefix = netip.PrefixFrom(a, a.BitLen())
	}
	if !prefix.IsValid() {
		return "", bad
	}
	if prefix.Bits() == 0 {
		return "", serr("مسیر پیش‌فرض (/0) مجاز نیست.", "A default route (/0) is not allowed.")
	}
	return prefix.String(), nil
}

func cleanIP(value any, labelFA, labelEN string) (string, error) {
	a, err := netip.ParseAddr(strings.TrimSpace(fmt.Sprint(value)))
	if err != nil || a.Zone() != "" {
		return "", serr(labelFA+" باید یک آدرس IP معتبر باشد.", labelEN+" must be a valid IP address.")
	}
	if a.IsUnspecified() || a.IsMulticast() {
		return "", serr(labelFA+" نامعتبر است.", labelEN+" is not valid.")
	}
	return a.String(), nil
}

func setBool(dst *bool, value any) error {
	b, ok := value.(bool) // strict: 0/1 or "true" are not booleans
	if !ok {
		return serr("مقدار باید روشن یا خاموش باشد.", "The value must be on or off.")
	}
	*dst = b
	return nil
}

func setChoice(dst *string, key string, value any) error {
	s, ok := value.(string)
	if !ok || !contains(choices[key], s) {
		return serr("گزینه نامعتبر است.", "Invalid option.")
	}
	*dst = s
	return nil
}

func setList(dst *[]string, value any, clean func(string) (string, error)) error {
	items, err := splitItems(value)
	if err != nil {
		return err
	}
	out := []string{}
	for _, x := range items {
		c, err := clean(x)
		if err != nil {
			return err
		}
		if !contains(out, c) {
			out = append(out, c)
		}
	}
	*dst = out
	return nil
}

// set validates one key and stores the cleaned value.
func (s *Settings) set(key string, value any) error {
	switch key {
	case "auto_connect":
		return setBool(&s.AutoConnect, value)
	case "autostart":
		return setBool(&s.Autostart, value)
	case "bypass_lan":
		return setBool(&s.BypassLAN, value)
	case "ipv6":
		return setBool(&s.IPv6, value)
	case "strict_route":
		return setBool(&s.StrictRoute, value)
	case "log_level", "udp_policy", "dns_type", "dns_strategy", "tun_stack":
		var dst *string
		switch key {
		case "log_level":
			dst = &s.LogLevel
		case "udp_policy":
			dst = &s.UDPPolicy
		case "dns_type":
			dst = &s.DNSType
		case "dns_strategy":
			dst = &s.DNSStrategy
		default:
			dst = &s.TunStack
		}
		return setChoice(dst, key, value)
	case "direct_apps":
		return setList(&s.DirectApps, value, cleanApp)
	case "direct_domains":
		return setList(&s.DirectDomains, value, cleanDomain)
	case "direct_cidrs":
		return setList(&s.DirectCIDRs, value, cleanCIDR)
	case "dns_server":
		ip, err := cleanIP(value, "آدرس DNS", "The DNS address")
		if err != nil {
			return err
		}
		s.DNSServer = ip
		return nil
	case "direct_dns":
		ip, err := cleanIP(value, "آدرس DNS مستقیم", "The direct DNS address")
		if err != nil {
			return err
		}
		s.DirectDNS = ip
		return nil
	case "dns_path":
		path := strings.TrimSpace(fmt.Sprint(value))
		if path == "" {
			path = "/dns-query"
		}
		if !strings.HasPrefix(path, "/") {
			path = "/" + path
		}
		if !pathRe.MatchString(path) {
			return serr("مسیر DoH نامعتبر است.", "Invalid DoH path.")
		}
		s.DNSPath = path
		return nil
	case "tun_mtu":
		text := strings.TrimSpace(fmt.Sprint(value))
		mtu := 0
		if text != "" {
			n, err := strconv.Atoi(text)
			if err != nil {
				return serr("MTU باید عدد باشد.", "MTU must be a number.")
			}
			mtu = n
		}
		if mtu != 0 && (mtu < 1280 || mtu > 9000) {
			return serr("MTU باید 0 (خودکار) یا بین 1280 تا 9000 باشد.",
				"MTU must be 0 (automatic) or between 1280 and 9000.")
		}
		s.TunMTU = mtu
		return nil
	}
	return serr("تنظیم ناشناخته: "+key, "Unknown setting: "+key)
}

// Sanitize returns base updated with the validated changes. Keys are applied in sorted order
// so the first reported error is deterministic.
func Sanitize(changes map[string]any, base Settings) (Settings, error) {
	result := base
	keys := make([]string, 0, len(changes))
	for k := range changes {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if err := result.set(k, changes[k]); err != nil {
			return base, err
		}
	}
	return result, nil
}

// FromMap merges a saved JSON object into the defaults, silently dropping anything invalid.
func FromMap(saved map[string]any) Settings {
	result := DefaultSettings()
	for k, v := range saved {
		tmp := result
		if err := tmp.set(k, v); err == nil {
			result = tmp
		}
	}
	return result
}

// nonEngine lists the settings that only change what the panel does, not the sing-box config.
func (s Settings) engineView() Settings {
	s.AutoConnect, s.Autostart = false, false
	return s
}

// EngineChanged reports whether a and b differ in any setting that reaches the sing-box config.
func EngineChanged(a, b Settings) bool {
	return !reflect.DeepEqual(a.engineView(), b.engineView())
}

// LoadSettings reads the settings file (defaults when it is missing or broken).
func LoadSettings() Settings {
	m, err := ReadJSONMap(SettingsPath())
	if err != nil {
		return DefaultSettings()
	}
	return FromMap(m)
}

// SaveSettings writes the settings file.
func SaveSettings(s Settings) error {
	data, err := MarshalJSON(s, true)
	if err != nil {
		return err
	}
	return WriteFileAtomic(SettingsPath(), data, 0o644)
}
