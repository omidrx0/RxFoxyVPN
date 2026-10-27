package tun

import (
	"bytes"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	"rxvpn/internal/config"
)

func TestMakeConfigMatchesPython(t *testing.T) {
	raw, err := os.ReadFile("../testdata/golden.json")
	if err != nil {
		t.Fatal(err)
	}
	var g struct {
		Tun []struct {
			Changes json.RawMessage
			Port    int
			Want    map[string]any
		}
	}
	if err := json.Unmarshal(raw, &g); err != nil {
		t.Fatal(err)
	}
	for _, tc := range g.Tun {
		dec := json.NewDecoder(bytes.NewReader(tc.Changes))
		dec.UseNumber()
		var changes map[string]any
		if err := dec.Decode(&changes); err != nil {
			t.Fatal(err)
		}
		s, err := config.Sanitize(changes, config.DefaultSettings())
		if err != nil {
			t.Fatalf("%s: %v", tc.Changes, err)
		}
		data, err := json.Marshal(MakeConfig(tc.Port, s))
		if err != nil {
			t.Fatal(err)
		}
		var got map[string]any
		if err := json.Unmarshal(data, &got); err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(got, tc.Want) {
			gj, _ := json.MarshalIndent(got, "", " ")
			wj, _ := json.MarshalIndent(tc.Want, "", " ")
			t.Errorf("settings %s\n got:\n%s\n want:\n%s", tc.Changes, gj, wj)
		}
	}
}
