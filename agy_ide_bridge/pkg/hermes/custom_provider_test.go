package hermes

import (
	"os"
	"path/filepath"
	"testing"
)

func TestHermesCustomProvider(t *testing.T) {
	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, ".hermes"), 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := `model: nvidia
custom_providers:
  - name: "omniroute"
    base_url: "http://127.0.0.1:20128/v1"
    api_key: "sk-test"
    model: "aapinex/deepseek-v4.1-flash"
    models:
      auto/best-coding: {}
  - name: "other"
    base_url: "https://example.test"
other_section:
  key: value
`
	if err := os.WriteFile(filepath.Join(home, ".hermes", "config.yaml"), []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}

	p := hermesCustomProvider(home, "omniroute")
	if p == nil {
		t.Fatal("expected omniroute to resolve")
	}
	if p.BaseURL != "http://127.0.0.1:20128/v1" {
		t.Errorf("base_url = %q", p.BaseURL)
	}
	if p.APIKey != "sk-test" {
		t.Errorf("api_key = %q", p.APIKey)
	}
	if p.ID != "custom:omniroute" {
		t.Errorf("id = %q", p.ID)
	}

	if got := hermesCustomProvider(home, "missing"); got != nil {
		t.Errorf("missing provider resolved to %+v", got)
	}

	// findAppProvider reaches the same entry through the custom: prefix.
	if p := findAppProvider(home, "custom:omniroute"); p == nil || p.BaseURL != "http://127.0.0.1:20128/v1" {
		t.Errorf("findAppProvider did not resolve custom:omniroute: %+v", p)
	}
}

// TestHermesCustomProviderKeyEnv covers the two shapes a real config.yaml
// actually uses and the parser above used to miss: a provider whose secret is
// only named (key_env) and a provider whose block ends because the section
// ends, not because another block starts.
func TestHermesCustomProviderKeyEnv(t *testing.T) {
	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, ".hermes"), 0o755); err != nil {
		t.Fatal(err)
	}
	cfg := `custom_providers:
  - name: "Api.cline.bot"
    base_url: "https://api.cline.bot/api/v1"
    key_env: "ANTIGEM_TEST_CLINE_KEY"
    model: "71,176"
  - name: "last-one"
    base_url: "https://api.mistral.ai/v1"
    api_key: "sk-mistral"
other_section:
  key: value
`
	if err := os.WriteFile(filepath.Join(home, ".hermes", "config.yaml"), []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("ANTIGEM_TEST_CLINE_KEY", "env-secret")

	p := hermesCustomProvider(home, "Api.cline.bot")
	if p == nil {
		t.Fatal("expected Api.cline.bot to resolve")
	}
	if p.BaseURL != "https://api.cline.bot/api/v1" {
		t.Errorf("base_url = %q", p.BaseURL)
	}
	// A key_env provider must probe with the real key, not anonymously.
	if p.APIKey != "env-secret" {
		t.Errorf("key_env not resolved: %q", p.APIKey)
	}

	// The last block sits directly before the next top-level key: no closing
	// block marker, only the section end. Its key must still be picked up.
	last := hermesCustomProvider(home, "last-one")
	if last == nil || last.APIKey != "sk-mistral" {
		t.Errorf("last block = %+v", last)
	}
	if last != nil && last.BaseURL != "https://api.mistral.ai/v1" {
		t.Errorf("last base_url = %q", last.BaseURL)
	}
}

// TestCustomProviderID guards the prefix match the picker rides on: the id
// crosses Kotlin -> JSON -> Go, so the prefix is matched case-insensitively.
func TestCustomProviderID(t *testing.T) {
	cases := map[string]string{
		"custom:omniroute": "omniroute",
		"Custom:OmniRoute": "OmniRoute",
		"CUSTOM:kiraai":    "kiraai",
	}
	for in, want := range cases {
		got, ok := customProviderID(in)
		if !ok || got != want {
			t.Errorf("customProviderID(%q) = %q, %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "custom:", "nvidia", "https", "customx:omniroute"} {
		if _, ok := customProviderID(in); ok {
			t.Errorf("customProviderID(%q) should not match", in)
		}
	}
}
