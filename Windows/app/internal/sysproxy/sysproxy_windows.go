//go:build windows

package sysproxy

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const internetSettingsKey = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`

func supported() bool { return true }

func openKey(access uint32) (registry.Key, error) {
	return registry.OpenKey(registry.CURRENT_USER, internetSettingsKey, access)
}

func capture() (snapshot, error) {
	k, err := openKey(registry.QUERY_VALUE)
	if err != nil {
		return nil, err
	}
	defer k.Close()
	snap := snapshot{}
	for _, name := range proxyValueNames {
		_, typ, err := k.GetValue(name, nil)
		if errors.Is(err, registry.ErrNotExist) {
			snap[name] = nil
			continue
		}
		if err != nil && !errors.Is(err, registry.ErrShortBuffer) {
			return nil, err
		}
		switch typ {
		case registry.DWORD:
			n, _, err := k.GetIntegerValue(name)
			if err != nil {
				return nil, err
			}
			snap[name] = &value{Value: uint32(n), Type: typ}
		case registry.SZ, registry.EXPAND_SZ:
			s, _, err := k.GetStringValue(name)
			if err != nil {
				return nil, err
			}
			snap[name] = &value{Value: s, Type: typ}
		default:
			snap[name] = nil
		}
	}
	return snap, nil
}

func asUint32(v any) uint32 {
	switch n := v.(type) {
	case uint32:
		return n
	case json.Number:
		i, _ := n.Int64()
		return uint32(i)
	case float64:
		return uint32(n)
	case int:
		return uint32(n)
	}
	return 0
}

func write(snap snapshot) error {
	k, err := openKey(registry.QUERY_VALUE | registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	for _, name := range proxyValueNames {
		entry := snap[name]
		if entry == nil {
			if err := k.DeleteValue(name); err != nil && !errors.Is(err, registry.ErrNotExist) {
				return err
			}
			continue
		}
		switch entry.Type {
		case registry.DWORD:
			err = k.SetDWordValue(name, asUint32(entry.Value))
		case registry.EXPAND_SZ:
			err = k.SetExpandStringValue(name, fmt.Sprint(entry.Value))
		default:
			err = k.SetStringValue(name, fmt.Sprint(entry.Value))
		}
		if err != nil {
			return err
		}
	}
	notifyChange()
	return nil
}

var procInternetSetOption = windows.NewLazySystemDLL("wininet.dll").NewProc("InternetSetOptionW")

// notifyChange tells running programs (browsers ...) that the proxy settings changed.
func notifyChange() {
	const settingsChanged, refresh = 39, 37
	procInternetSetOption.Call(0, settingsChanged, 0, 0)
	procInternetSetOption.Call(0, refresh, 0, 0)
}

// current reads the active system proxy so the control-plane HTTP client can follow it, the
// way Python requests did (this is what lets sign-in / token refresh work through the tunnel
// when it is the only way to reach Mozilla).
func current() (enabled bool, server, override string) {
	k, err := openKey(registry.QUERY_VALUE)
	if err != nil {
		return false, "", ""
	}
	defer k.Close()
	if n, _, err := k.GetIntegerValue("ProxyEnable"); err != nil || n == 0 {
		return false, "", ""
	}
	server, _, _ = k.GetStringValue("ProxyServer")
	override, _, _ = k.GetStringValue("ProxyOverride")
	return server != "", server, override
}

func systemProxyFor(req *http.Request) (*url.URL, error) {
	enabled, server, override := current()
	if !enabled || bypassed(req.URL.Hostname(), override) {
		return nil, nil
	}
	chosen := server
	if strings.Contains(server, "=") {
		chosen = ""
		for _, part := range strings.Split(server, ";") {
			kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
			if len(kv) == 2 && strings.EqualFold(kv[0], req.URL.Scheme) {
				chosen = kv[1]
			}
		}
	}
	if chosen == "" {
		return nil, nil
	}
	if !strings.Contains(chosen, "://") {
		chosen = "http://" + chosen
	}
	return url.Parse(chosen)
}
