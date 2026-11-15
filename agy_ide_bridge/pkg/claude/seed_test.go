package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSeedKiloFreeOneShot(t *testing.T) {
	home := t.TempDir()
	SeedKiloFree(home)

	path := filepath.Join(home, ".claude", "agy_providers.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("seed did not write the store: %v", err)
	}
	var f providersFile
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatalf("store is not JSON: %v", err)
	}
	var got *Provider
	for i := range f.Providers {
		if f.Providers[i].ID == "kilo-free" {
			got = &f.Providers[i]
		}
	}
	if got == nil {
		t.Fatal("kilo-free entry missing after seed")
	}
	if got.BaseURL != "https://api.kilo.ai/api/gateway" {
		t.Errorf("base_url = %q", got.BaseURL)
	}
	if got.AuthMode != authXAPIKey {
		t.Errorf("auth_mode = %q, want %q", got.AuthMode, authXAPIKey)
	}
	if got.APIKey == "" {
		t.Error("placeholder API key missing (the save path requires one)")
	}
	if len(got.Models) == 0 {
		t.Error("no models seeded")
	}

	// deleting the entry must stick: the marker makes the seed one-shot
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	SeedKiloFree(home)
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("seed re-added a user-deleted entry")
	}
}

func TestSeedKiloFreeKeepsExisting(t *testing.T) {
	home := t.TempDir()
	dir := filepath.Join(home, ".claude")
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "agy_providers.json")
	keep := providersFile{Providers: []Provider{{ID: "mine", Name: "Mine", BaseURL: "https://x"}}}
	raw, _ := json.Marshal(keep)
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	SeedKiloFree(home)

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var f providersFile
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Providers) != 2 {
		t.Fatalf("providers = %d, want 2 (mine + kilo-free)", len(f.Providers))
	}
	if f.Providers[0].ID != "mine" {
		t.Errorf("first provider = %q, want mine untouched", f.Providers[0].ID)
	}
}
