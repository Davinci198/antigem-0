package dsh

import "testing"

func TestChatModelsDropsTheCatalogNoise(t *testing.T) {
	ids := []string{
		"bigcode/starcoder2-15b",
		"nvidia/nv-embedqa-mistral-7b-v2",
		"nvidia/nemotron-parse",
		"nvidia/ai-synthetic-video-detector",
		"z-ai/glm-5.3-flash",
		"",
	}
	got := ChatModels(ids)
	want := []string{"bigcode/starcoder2-15b", "z-ai/glm-5.3-flash"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}

func TestDefaultModelPrefersAChatFamily(t *testing.T) {
	ids := []string{"bigcode/starcoder2-15b", "nvidia/nvclip", "moonshotai/kimi-k3.1-pro", "z-ai/glm-5.3-flash"}
	// glm comes before kimi in chatFamilies, so the harness starts on glm.
	if got := DefaultModel(ids); got != "z-ai/glm-5.3-flash" {
		t.Fatalf("got %q", got)
	}
}

func TestDefaultModelFallsBackToTheFirstUsableID(t *testing.T) {
	if got := DefaultModel([]string{"bigcode/starcoder2-15b"}); got != "bigcode/starcoder2-15b" {
		t.Fatalf("got %q", got)
	}
	if got := DefaultModel([]string{"nvidia/nv-embedqa-mistral-7b-v2"}); got != "" {
		t.Fatalf("a catalogue of embeddings has no default, got %q", got)
	}
	if got := DefaultModel(nil); got != "" {
		t.Fatalf("got %q", got)
	}
}
