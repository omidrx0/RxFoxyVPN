package foxy

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"reflect"
	"sort"
	"testing"
)

func loadGolden(t *testing.T) map[string]json.RawMessage {
	t.Helper()
	raw, err := os.ReadFile("../testdata/golden.json")
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func section[T any](t *testing.T, g map[string]json.RawMessage, name string) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(g[name], &v); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return v
}

func TestDeriveAuthPW(t *testing.T) {
	type c struct{ Email, Password, Want string }
	for _, tc := range section[[]c](t, loadGolden(t), "auth_pw") {
		if got := DeriveAuthPW(tc.Email, tc.Password); got != tc.Want {
			t.Errorf("DeriveAuthPW(%q) = %s, want %s", tc.Email, got, tc.Want)
		}
	}
}

func TestHKDF(t *testing.T) {
	type c struct {
		Ikm, Info string
		Len       int
		Want      string
	}
	for _, tc := range section[[]c](t, loadGolden(t), "hkdf") {
		ikm, _ := hex.DecodeString(tc.Ikm)
		if got := hex.EncodeToString(HKDF(ikm, []byte(tc.Info), tc.Len, nil)); got != tc.Want {
			t.Errorf("HKDF = %s, want %s", got, tc.Want)
		}
	}
}

func TestHawkCredentials(t *testing.T) {
	type c struct{ Token, ID, Key string }
	tc := section[c](t, loadGolden(t), "hawk_creds")
	id, key, err := DeriveHawkCredentials(tc.Token)
	if err != nil {
		t.Fatal(err)
	}
	if id != tc.ID || hex.EncodeToString(key) != tc.Key {
		t.Errorf("got id=%s key=%x, want id=%s key=%s", id, key, tc.ID, tc.Key)
	}
}

func TestHawkHeader(t *testing.T) {
	type c struct {
		Method, URL, ID, Key, Body string
		Ts                         int64
		Nonce, Want                string
	}
	for _, tc := range section[[]c](t, loadGolden(t), "hawk_header") {
		key, _ := hex.DecodeString(tc.Key)
		got, err := HawkHeaderAt(tc.Method, tc.URL, tc.ID, key, []byte(tc.Body), tc.Ts, tc.Nonce)
		if err != nil {
			t.Fatal(err)
		}
		if got != tc.Want {
			t.Errorf("HawkHeaderAt(%s %s)\n got  %s\n want %s", tc.Method, tc.URL, got, tc.Want)
		}
	}
}

func TestJWTExpiry(t *testing.T) {
	type c struct {
		Token string
		Want  *int64
	}
	for _, tc := range section[[]c](t, loadGolden(t), "jwt") {
		got, ok := JWTExpiry(tc.Token)
		switch {
		case tc.Want == nil && ok:
			t.Errorf("%q: got %d, want none", tc.Token, got)
		case tc.Want != nil && (!ok || got != *tc.Want):
			t.Errorf("%q: got %d/%v, want %d", tc.Token, got, ok, *tc.Want)
		}
	}
}

func TestParseServerList(t *testing.T) {
	type want struct {
		Name    string
		Targets [][2]any
	}
	var sec struct {
		Records []json.RawMessage
		Want    map[string]want
	}
	if err := json.Unmarshal(loadGolden(t)["serverlist"], &sec); err != nil {
		t.Fatal(err)
	}
	got := ParseServerList(sec.Records)
	var gotKeys, wantKeys []string
	for k := range got {
		gotKeys = append(gotKeys, k)
	}
	for k := range sec.Want {
		wantKeys = append(wantKeys, k)
	}
	sort.Strings(gotKeys)
	sort.Strings(wantKeys)
	if !reflect.DeepEqual(gotKeys, wantKeys) {
		t.Fatalf("countries: got %v, want %v", gotKeys, wantKeys)
	}
	for code, w := range sec.Want {
		c := got[code]
		if c.Name != w.Name || len(c.Targets) != len(w.Targets) {
			t.Errorf("%s: got %+v, want %+v", code, c, w)
			continue
		}
		for i, tg := range c.Targets {
			if tg.Host != w.Targets[i][0].(string) || float64(tg.Port) != w.Targets[i][1].(float64) {
				t.Errorf("%s target %d: got %+v, want %v", code, i, tg, w.Targets[i])
			}
		}
	}
}

func TestPBKDF2KnownVector(t *testing.T) {
	// RFC 7914 section 11: PBKDF2-HMAC-SHA-256(P="passwd", S="salt", c=1, dkLen=64)
	want := "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
		"49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
	if got := hex.EncodeToString(pbkdf2SHA256([]byte("passwd"), []byte("salt"), 1, 64)); got != want {
		t.Errorf("pbkdf2 = %s", got)
	}
}
