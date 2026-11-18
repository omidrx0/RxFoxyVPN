package ui

// W-19 · language-file parity. Every locale must carry exactly the same keys as English,
// and every literal key the UI references must exist. Dynamic keys (built by concatenation,
// e.g. "errors." + code) are excluded from the static reference check; the errors catalog
// itself is checked exhaustively.

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
)

var (
	tCallRe    = regexp.MustCompile(`\bt\('([A-Za-z0-9_.\-]+)'`)
	dataI18nRe = regexp.MustCompile(`data-i18n(?:-ph|-aria)?="([A-Za-z0-9_.\-]+)"`)
	bareKeyRe  = regexp.MustCompile(`([A-Za-z_][A-Za-z0-9_.\-]*)\s*:`)
	jsIdentRe  = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_.\-]*$`)
)

const langs = "en fa ar ru zh ko"

// lexJSLangKeys scans a lang file the way a JS lexer would and returns every object-literal
// key, whether bare (foo: 'x') or quoted ("foo.bar": "x"). Comments are skipped and string
// contents never leak into key detection (apostrophes inside double-quoted strings are fine).
func lexJSLangKeys(src string) map[string]bool {
	keys := map[string]bool{}
	n := len(src)
	stripped := make([]byte, 0, n)
	i := 0
	for i < n {
		c := src[i]
		switch {
		case c == '/' && i+1 < n && src[i+1] == '/':
			for i < n && src[i] != '\n' {
				i++
			}
			stripped = append(stripped, ' ')
		case c == '/' && i+1 < n && src[i+1] == '*':
			i += 2
			for i+1 < n && !(src[i] == '*' && src[i+1] == '/') {
				i++
			}
			if i+1 < n {
				i += 2
			}
			stripped = append(stripped, ' ')
		case c == '\'' || c == '"' || c == '`':
			quote := c
			j := i + 1
			for j < n {
				if src[j] == '\\' {
					j += 2
					continue
				}
				if src[j] == quote {
					j++
					break
				}
				j++
			}
			content := src[i+1 : j-1]
			k := j
			for k < n && (src[k] == ' ' || src[k] == '\t' || src[k] == '\r' || src[k] == '\n') {
				k++
			}
			if k < n && src[k] == ':' && jsIdentRe.MatchString(content) {
				keys[content] = true
			}
			stripped = append(stripped, ' ')
			i = j
		default:
			stripped = append(stripped, c)
			i++
		}
	}
	for _, m := range bareKeyRe.FindAllStringSubmatch(string(stripped), -1) {
		keys[m[1]] = true
	}
	return keys
}

func langFileKeys(t *testing.T, name string) map[string]bool {
	t.Helper()
	data, err := os.ReadFile("lang/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return lexJSLangKeys(string(data))
}

func TestLangKeyParity(t *testing.T) {
	base := langFileKeys(t, "en.js")
	if len(base) == 0 {
		t.Fatal("en.js produced no keys")
	}
	for _, l := range strings.Fields(langs) {
		keys := langFileKeys(t, l+".js")
		var missing, extra []string
		for k := range base {
			if !keys[k] {
				missing = append(missing, k)
			}
		}
		for k := range keys {
			if !base[k] {
				extra = append(extra, k)
			}
		}
		sort.Strings(missing)
		sort.Strings(extra)
		for _, k := range missing {
			t.Errorf("%s.js: missing key %q", l, k)
		}
		for _, k := range extra {
			t.Errorf("%s.js: extra key %q", l, k)
		}
	}
}

var dynamicPrefixes = []string{
	"errors.", "set_", // concatenated at runtime ("set_" + key, "errors." + code + …)
	"routing_region_", "region_", "login_method_", "auto_rotate",
	"set_cat_", "data",
}

func TestReferencedKeysExist(t *testing.T) {
	base := langFileKeys(t, "en.js")
	referenced := map[string]string{} // key -> where it was seen
	addAll := func(file string, re *regexp.Regexp) {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range re.FindAllStringSubmatch(string(data), -1) {
			referenced[m[1]] = file
		}
	}
	for _, directory := range []string{".", "screens", "components", "services"} {
		scripts, err := os.ReadDir(directory)
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range scripts {
			if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".js") {
				addAll(filepath.Join(directory, entry.Name()), tCallRe)
			}
		}
	}
	addAll("index.html", dataI18nRe)
	for k, where := range referenced {
		skip := strings.HasSuffix(k, "_") // concatenation prefix, e.g. "pill_" + state
		for _, p := range dynamicPrefixes {
			if strings.HasPrefix(k, p) {
				skip = true
				break
			}
		}
		if skip {
			continue
		}
		if !base[k] {
			t.Errorf("%s references unknown key %q", where, k)
		}
	}
}

func TestUIResourcesAreEmbedded(t *testing.T) {
	index, err := FS.ReadFile("index.html")
	if err != nil {
		t.Fatal(err)
	}
	scriptSrc := regexp.MustCompile(`<script src="/([A-Za-z0-9_./-]+\.js)"></script>`)
	for _, match := range scriptSrc.FindAllSubmatch(index, -1) {
		name := string(match[1])
		if _, err := FS.ReadFile(name); err != nil {
			t.Errorf("index.html references %q, but it is not embedded: %v", name, err)
		}
	}
	styleSrc := regexp.MustCompile(`<link rel="stylesheet" href="/([A-Za-z0-9_./-]+\.css)">`)
	for _, match := range styleSrc.FindAllSubmatch(index, -1) {
		name := string(match[1])
		if _, err := FS.ReadFile(name); err != nil {
			t.Errorf("index.html references %q, but it is not embedded: %v", name, err)
		}
	}
}

func TestErrorsCatalogComplete(t *testing.T) {
	codes := []string{
		"CONNECTION_FAILED", "SERVER_UNREACHABLE", "AUTH_FAILED", "ACCOUNT_EXPIRED",
		"NO_ACTIVE_ACCOUNT", "NO_SERVER_SELECTED", "NETWORK_UNAVAILABLE", "DNS_FAILED",
		"CONNECTION_TIMEOUT", "TLS_HANDSHAKE_FAILED", "INVALID_CONFIGURATION", "CAPTCHA_REQUIRED",
		"SERVER_OVERLOADED", "VPN_PERMISSION_DENIED", "UNKNOWN_ERROR", "QUOTA_EXHAUSTED",
		"NOT_ENTITLED", "TOKEN_INVALID", "REAUTH_REQUIRED", "NO_USABLE_ACCOUNT",
		"ACCOUNT_RESERVED", "REGION_BLOCKED", "RATE_LIMITED", "CAPTCHA_BLOCKED",
		"BACKUP_INVALID", "BACKUP_EMPTY", "BACKUP_FAILED", "IMPORT_FAILED",
		"EXPORT_FAILED", "SERVER_LIST_FAILED", "STORAGE_ERROR",
	}
	for _, l := range strings.Fields(langs) {
		keys := langFileKeys(t, l+".js")
		for _, c := range codes {
			for _, suffix := range []string{"title", "desc", "hint"} {
				k := "errors." + c + "." + suffix
				if !keys[k] {
					t.Errorf("%s.js: missing %q", l, k)
				}
			}
		}
	}
}
