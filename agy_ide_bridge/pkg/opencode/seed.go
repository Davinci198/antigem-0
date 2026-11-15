package opencode

import (
	"os"
	"path/filepath"
)

// SeedKiloFree inserts a keyless Kilo Free entry into the bridge's OpenCode
// custom-provider store (~/.antigem_bridge/opencode/custom_providers.json) on
// first run. A marker file makes the seed one-shot: a user-deleted entry stays
// deleted. An already-present entry (or an unreadable store) is left alone; a
// failed write does not drop the marker, so the next start retries.
func SeedKiloFree(homeDir string) {
	dir := filepath.Join(homeDir, ".antigem_bridge", "opencode")
	marker := filepath.Join(dir, ".kilo_free_seeded")
	if _, err := os.Stat(marker); err == nil {
		return
	}
	m := NewManager(homeDir, "", "")
	list, err := m.loadProviders()
	if err != nil {
		return // unreadable store: leave it for the user, retry next start
	}
	present := false
	for _, p := range list {
		if p.ID == "kilo-free" {
			present = true
			break
		}
	}
	if !present {
		list = append(list, CustomProvider{
			ID:      "kilo-free",
			Name:    "Kilo Free",
			Kind:    KindOpenAI,
			BaseURL: "https://api.kilo.ai/api/gateway",
			// No API key: Kilo's gateway is keyless and 401s on any Bearer.
			Models: kiloFreeModels(),
		})
		if err := m.saveProviders(list); err != nil {
			return
		}
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return
	}
	_ = os.WriteFile(marker, []byte("1\n"), 0600)
}

// kiloFreeModels is the curated tool-capable free catalog (models.dev cost 0/0,
// audio-only Lyria rows excluded) — same ids as pkg/claude's Kilo Free seed.
func kiloFreeModels() []CustomModel {
	ids := []struct {
		id, name string
		ctx      int64
	}{
		{"nvidia/nemotron-3.5-lightning:free", "Nemotron 3.5 Lightning (free)", 1_000_000},
		{"nvidia/nemotron-3.5-super-vl:free", "Nemotron 3.5 Super VL (free)", 1_000_000},
		{"nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra (free)", 1_000_000},
		{"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "Nemotron 3 Nano Omni (free)", 256_000},
		{"kilo-auto/free", "Auto Free", 256_000},
		{"baidu/ernie-5.5-flash-preview:free", "ERNIE 5.5 Flash (free)", 1_024_000},
		{"inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash (free)", 1_048_576},
		{"particle-ai/particle-1:free", "Particle-1 (free)", 1_048_576},
		{"dots-studio/dots-3-note-preview:free", "Dots3-Note Preview (free)", 512_000},
		{"reflection/beam-free", "Beam 1.0 (Free)", 1_048_576},
		{"humansand/ampersand-v0.1", "Ampersand v0.1", 1_000_000},
		{"cohere/north-mini-code:free", "North Mini Code (free)", 256_000},
		{"liquid/lfm-3-1.7b:free", "LFM3-1.7B (free)", 262_144},
		{"stealth/glyph-cluster", "Glyph Cluster", 256_000},
		{"openrouter/free", "OpenRouter Free Models Router", 200_000},
		{"openrouter/auto", "Auto Router", 2_000_000},
	}
	out := make([]CustomModel, 0, len(ids))
	for _, m := range ids {
		out = append(out, CustomModel{ID: m.id, Name: m.name, ContextLength: m.ctx})
	}
	return out
}
