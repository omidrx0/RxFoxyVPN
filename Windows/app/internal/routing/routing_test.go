package routing

import "testing"

// The counts below are the real ipdeny line counts of the embedded zone files
// (see the port guide §۱۲.۴). If a zone file is ever replaced, the test must be updated on purpose.
func TestZoneCounts(t *testing.T) {
	want := map[Region]int{
		RegionIR: 1972,
		RegionCN: 8815,
		RegionRU: 11482,
		RegionAF: 133,
		RegionTR: 2037,
		RegionID: 6292,
		RegionBR: 13080,
	}
	for region, n := range want {
		got := CIDRs(region)
		if len(got) != n {
			t.Errorf("region %s: parsed %d CIDRs, want %d", region, len(got), n)
		}
		if Count(region) != n {
			t.Errorf("region %s: Count = %d, want %d", region, Count(region), n)
		}
		for _, c := range got {
			if !cidrRe.MatchString(c) {
				t.Errorf("region %s: invalid CIDR %q in output", region, c)
			}
		}
	}
}

func TestNoneRegionIsEmpty(t *testing.T) {
	if got := CIDRs(RegionNone); got != nil {
		t.Fatalf("NONE must yield no CIDRs, got %d", len(got))
	}
	if got := DomainSuffixes(RegionNone); got != nil {
		t.Fatalf("NONE must yield no suffixes, got %v", got)
	}
}

func TestSuffixesKnown(t *testing.T) {
	for _, region := range Regions {
		if region == RegionNone {
			continue
		}
		if len(DomainSuffixes(region)) == 0 {
			t.Errorf("region %s has no domain suffixes", region)
		}
	}
}

func TestNormalizeAndKnown(t *testing.T) {
	for _, tc := range [][2]string{
		{"ir", "IR"}, {"IR", "IR"}, {" CN ", "CN"}, {"none", "NONE"}, {"xx", "NONE"}, {"", "NONE"},
	} {
		if got := Normalize(tc[0]); string(got) != tc[1] {
			t.Errorf("Normalize(%q) = %q, want %q", tc[0], got, tc[1])
		}
	}
	for _, ok := range []string{"NONE", "IR", "CN", "RU", "AF", "TR", "ID", "BR", "ir", " ir "} {
		if !Known(ok) {
			t.Errorf("Known(%q) = false, want true", ok)
		}
	}
	for _, bad := range []string{"", "XX", "IRAN"} {
		if Known(bad) {
			t.Errorf("Known(%q) = true, want false", bad)
		}
	}
}

func TestZoneCountsAreReal(t *testing.T) {
	// every embedded zone parses — this catches accidental truncation at copy time
	total := 0
	for _, region := range Regions {
		if region == RegionNone {
			continue
		}
		total += Count(region)
	}
	if want := 1972 + 8815 + 11482 + 133 + 2037 + 6292 + 13080; total != want {
		t.Fatalf("total = %d, want %d (sum of the zone files)", total, want)
	}
}
