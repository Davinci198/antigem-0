package opencode

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSeedKiloFreeOneShot(t *testing.T) {
	home := t.TempDir()
	SeedKiloFree(home)

	path := filepath.Join(home, ".antigem_bridge", "opencode", "custom_providers.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("seed did not write the store: %v", err)
	}
	var list []CustomProvider
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatalf("store is not JSON: %v", err)
	}
	var got *CustomProvider
	for i := range list {
		if list[i].ID == "kilo-free" {
			got = &list[i]
		}
	}
	if got == nil {
		t.Fatal("kilo-free entry missing after seed")
	}
	if got.Kind != KindOpenAI {
		t.Errorf("kind = %q, want openai", got.Kind)
	}
	if got.APIKey != "" {
		t.Errorf("api_key = %q, want empty (Kilo 401s on any Bearer)", got.APIKey)
	}
	if len(got.Models) == 0 {
		t.Fatal("no models seeded")
	}

	// the generated config must not stamp the "none" placeholder on keyless entries
	cfgPath := filepath.Join(home, ".antigem_bridge", "opencode", "opencode.json")
	cfgRaw, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatalf("writeConfig did not run: %v", err)
	}
	if strings.Contains(string(cfgRaw), `"apiKey": "none"`) {
		t.Error(`config still writes apiKey "none" (401 on Kilo)`)
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
