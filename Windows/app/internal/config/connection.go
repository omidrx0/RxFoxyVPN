package config

import (
	"encoding/json"

	"rxvpn/internal/locale"
)

// Connection is how the local proxy is exposed: TUN, Windows system proxy, or a manual port.
type Connection struct {
	Mode     string `json:"mode"`     // tun | system | manual
	Protocol string `json:"protocol"` // socks5 | http (manual mode only)
	Port     int    `json:"port"`
}

// DefaultConnection is manual mode on SOCKS5 port 10808.
func DefaultConnection() Connection {
	return Connection{Mode: "manual", Protocol: "socks5", Port: 10808}
}

// ValidMode / ValidProtocol are the accepted values.
func ValidMode(m string) bool     { return m == "tun" || m == "system" || m == "manual" }
func ValidProtocol(p string) bool { return p == "socks5" || p == "http" }

// LoadConnection reads connection.json, ignoring anything invalid.
func LoadConnection() Connection {
	c := DefaultConnection()
	m, err := ReadJSONMap(ConnectionPath())
	if err != nil {
		return c
	}
	if s, ok := m["mode"].(string); ok && ValidMode(s) {
		c.Mode = s
	}
	if s, ok := m["protocol"].(string); ok && ValidProtocol(s) {
		c.Protocol = s
	}
	if n, ok := m["port"].(json.Number); ok {
		if p, err := n.Int64(); err == nil && p >= 1 && p <= 65535 {
			c.Port = int(p)
		}
	}
	return c
}

// SaveConnection writes connection.json.
func SaveConnection(c Connection) error {
	data, err := MarshalJSON(c, false)
	if err != nil {
		return err
	}
	return WriteFileAtomic(ConnectionPath(), data, 0o644)
}

// LoadLang restores the panel language chosen last time.
func LoadLang() {
	m, err := ReadJSONMap(UIPath())
	if err != nil {
		return
	}
	if s, ok := m["lang"].(string); ok {
		locale.Set(s)
	}
}

// SaveLang remembers the panel language.
func SaveLang() error {
	data, err := MarshalJSON(map[string]string{"lang": locale.Get()}, false)
	if err != nil {
		return err
	}
	return WriteFileAtomic(UIPath(), data, 0o644)
}
