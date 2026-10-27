package config

import (
	"bytes"
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

type sanitizeCase struct {
	Changes json.RawMessage
	Ok      map[string]any
	FA, EN  string
}

func TestSanitizeMatchesPython(t *testing.T) {
	raw, err := os.ReadFile("../testdata/golden.json")
	if err != nil {
		t.Fatal(err)
	}
	var g struct {
		Sanitize []sanitizeCase
		Defaults map[string]any
	}
	if err := json.Unmarshal(raw, &g); err != nil {
		t.Fatal(err)
	}

	// the factory defaults must be identical
	gotDefaults := roundTrip(t, DefaultSettings())
	if !reflect.DeepEqual(gotDefaults, g.Defaults) {
		t.Errorf("defaults differ:\n got  %v\n want %v", gotDefaults, g.Defaults)
	}

	for _, tc := range g.Sanitize {
		dec := json.NewDecoder(bytes.NewReader(tc.Changes))
		dec.UseNumber() // like the HTTP layer
		var changes map[string]any
		if err := dec.Decode(&changes); err != nil {
			t.Fatal(err)
		}
		got, err := Sanitize(changes, DefaultSettings())
		if tc.Ok != nil {
			if err != nil {
				t.Errorf("%s: unexpected error %v", tc.Changes, err)
				continue
			}
			if g := roundTrip(t, got); !reflect.DeepEqual(g, tc.Ok) {
				t.Errorf("%s:\n got  %v\n want %v", tc.Changes, g, tc.Ok)
			}
			continue
		}
		se, ok := err.(*SettingError)
		if !ok {
			t.Errorf("%s: expected a SettingError, got %v", tc.Changes, err)
			continue
		}
		if se.FA != tc.FA || se.EN != tc.EN {
			t.Errorf("%s:\n got  %q / %q\n want %q / %q", tc.Changes, se.FA, se.EN, tc.FA, tc.EN)
		}
	}
}

func roundTrip(t *testing.T, v any) map[string]any {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestEngineChanged(t *testing.T) {
	a := DefaultSettings()
	b := a
	b.AutoConnect, b.Autostart = true, true
	if EngineChanged(a, b) {
		t.Error("auto_connect / autostart must not count as engine changes")
	}
	b.TunMTU = 1500
	if !EngineChanged(a, b) {
		t.Error("tun_mtu is an engine change")
	}
}

func TestFromMapDropsInvalid(t *testing.T) {
	s := FromMap(map[string]any{"log_level": "debug", "tun_mtu": "999999", "bogus": true})
	if s.LogLevel != "debug" || s.TunMTU != 0 {
		t.Errorf("unexpected result %+v", s)
	}
}
