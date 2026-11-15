package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Kilo Free: Kilo's keyless gateway (https://api.kilo.ai/api/gateway). The
// wire rejects any non-empty Bearer, so chats run with auth_mode "x-api-key"
// and a placeholder key; Hermes reaches the same endpoint through its builtin
// "kilo" provider (see pkg/hermes hermesBuiltinProvider).
const (
	kiloFreeID             = "kilo-free"
	kiloFreeName           = "Kilo Free"
	kiloFreeBaseURL        = "https://api.kilo.ai/api/gateway"
	kiloFreeModelsURL      = "https://api.kilo.ai/api/gateway/v1/models"
	kiloFreePlaceholderKey = "no-key-required"
)

// kiloFreeModels is the curated tool-capable free catalog (models.dev cost 0/0,
// audio-only Lyria rows excluded). Shared shape with the OpenCode seed and the
// Claude PRESETS entry.
var kiloFreeModels = []ProviderModel{
	{ID: "nvidia/nemotron-3.5-lightning:free", Name: "Nemotron 3.5 Lightning (free)", ContextLength: 1_000_000},
	{ID: "nvidia/nemotron-3.5-super-vl:free", Name: "Nemotron 3.5 Super VL (free)", ContextLength: 1_000_000},
	{ID: "nvidia/nemotron-3-ultra-550b-a55b:free", Name: "Nemotron 3 Ultra (free)", ContextLength: 1_000_000},
	{ID: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", Name: "Nemotron 3 Nano Omni (free)", ContextLength: 256_000},
	{ID: "kilo-auto/free", Name: "Auto Free", ContextLength: 256_000},
	{ID: "baidu/ernie-5.5-flash-preview:free", Name: "ERNIE 5.5 Flash (free)", ContextLength: 1_024_000},
	{ID: "inclusionai/ling-3.2-flash:free", Name: "Ling 3.2 Flash (free)", ContextLength: 1_048_576},
	{ID: "particle-ai/particle-1:free", Name: "Particle-1 (free)", ContextLength: 1_048_576},
	{ID: "dots-studio/dots-3-note-preview:free", Name: "Dots3-Note Preview (free)", ContextLength: 512_000},
	{ID: "reflection/beam-free", Name: "Beam 1.0 (Free)", ContextLength: 1_048_576},
	{ID: "humansand/ampersand-v0.1", Name: "Ampersand v0.1", ContextLength: 1_000_000},
	{ID: "cohere/north-mini-code:free", Name: "North Mini Code (free)", ContextLength: 256_000},
	{ID: "liquid/lfm-3-1.7b:free", Name: "LFM3-1.7B (free)", ContextLength: 262_144},
	{ID: "stealth/glyph-cluster", Name: "Glyph Cluster", ContextLength: 256_000},
	{ID: "openrouter/free", Name: "OpenRouter Free Models Router", ContextLength: 200_000},
	{ID: "openrouter/auto", Name: "Auto Router", ContextLength: 2_000_000},
}

// SeedKiloFree inserts the Kilo Free provider into ~/.claude/agy_providers.json
// on first run so the Hermes picker and the Claude chats both list it without
// the user pasting a URL. A marker file makes the seed one-shot: if the user
// deletes the entry later, it stays deleted. An already-present entry (or an
// unreadable store) is left untouched; a failed write does not drop the marker,
// so the next start retries.
func SeedKiloFree(homeDir string) {
	dir := filepath.Join(homeDir, ".claude")
	marker := filepath.Join(dir, ".kilo_free_seeded")
	if _, err := os.Stat(marker); err == nil {
		return
	}
	path := filepath.Join(dir, "agy_providers.json")
	var f providersFile
	if raw, err := os.ReadFile(path); err == nil {
		if json.Unmarshal(raw, &f) != nil {
			return // corrupt store: never overwrite what we cannot parse
		}
	}
	present := false
	for _, p := range f.Providers {
		if p.ID == kiloFreeID {
			present = true
			break
		}
	}
	if !present {
		f.Providers = append(f.Providers, Provider{
			ID:        kiloFreeID,
			Name:      kiloFreeName,
			BaseURL:   kiloFreeBaseURL,
			APIKey:    kiloFreePlaceholderKey,
			ModelsURL: kiloFreeModelsURL,
			AuthMode:  authXAPIKey,
			Models:    append([]ProviderModel(nil), kiloFreeModels...),
		})
		if err := writeJSONFile(path, f); err != nil {
			return
		}
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return
	}
	_ = os.WriteFile(marker, []byte("1\n"), 0600)
}
