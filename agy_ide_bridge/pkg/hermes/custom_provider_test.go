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
