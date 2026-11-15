package hermes

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func writeKiloAppProvider(t *testing.T, home string) {
	t.Helper()
	dir := filepath.Join(home, ".claude")
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	f := appProvidersFile{}
	// mirror of pkg/claude's seeded Kilo Free entry
	raw, _ := json.Marshal(map[string]interface{}{
		"providers": []map[string]interface{}{{
			"id": "kilo-free", "name": "Kilo Free",
			"base_url": "https://api.kilo.ai/api/gateway",
			"api_key":  "no-key-required",
			"models":   []map[string]string{{"id": "nvidia/nemotron-3.5-lightning:free"}},
		}},
	})
	_ = f
	if err := os.WriteFile(filepath.Join(dir, "agy_providers.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
}

func TestHermesBuiltinProvider(t *testing.T) {
	cases := []struct {
		base string
		want string
	}{
		{"https://api.kilo.ai/api/gateway", "kilo"},
		{"https://api.kilo.ai/api/gateway/", "kilo"},
		{"https://API.KILO.AI/api/gateway", "kilo"},
		{"https://openrouter.ai/api/v1", ""},
		{"https://integrate.api.nvidia.com/v1", ""},
		{"", ""},
		{"://bad", ""},
	}
	for _, c := range cases {
		if got := hermesBuiltinProvider(c.base); got != c.want {
			t.Errorf("hermesBuiltinProvider(%q) = %q, want %q", c.base, got, c.want)
		}
	}
}

// A Kilo session must be created as the builtin slug with no wire key —
// a custom route stamps a Bearer and Kilo answers 401 to any non-empty token.
func TestCreateRoutesKiloToBuiltin(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")
	body := `{"conversation_id":"22222222-2222-3333-4444-555555555555","model":"nvidia/nemotron-3.5-lightning:free","provider_id":"kilo-free"}`
	// seed the app store the create resolves against
	writeKiloAppProvider(t, home)

	sid := createSession(t, m, body)
	s := m.session(sid)
	s.mu.Lock()
	gotProvider, gotBase, gotKey := s.gwProvider, s.gwBaseURL, s.gwAPIKey
	s.mu.Unlock()
	if gotProvider != "kilo" {
		t.Errorf("gwProvider = %q, want kilo", gotProvider)
	}
	if gotBase != "" || gotKey != "" {
		t.Errorf("builtin route must carry no wire key, got base=%q key=%q", gotBase, gotKey)
	}
}
