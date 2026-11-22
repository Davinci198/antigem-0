package dsh

import "strings"

// nonChatMarkers are fragments of the ids that providers list next to their chat
// models. NVIDIA's catalogue is the loudest example: 33 live ids, of which the
// first alphabetically is bigcode/starcoder2-15b and the next few are embedding,
// rerank, parse and video-detector models. Handing that list to the harness makes
// it default to a model that cannot answer a prompt.
var nonChatMarkers = []string{
	"embed", "rerank", "parse", "detector", "nvclip", "reward",
	"translate", "pii", "arctic-embed", "cosmos",
}

// chatFamilies are the id prefixes a chat is likely to work well on; the first
// match becomes the harness default.
var chatFamilies = []string{
	"deepseek", "glm", "kimi", "qwen", "minimax", "llama", "nemotron",
	"mistral", "gemma", "gpt", "step", "palmyra", "zamba", "laguna", "muse",
}

// ChatModels drops the ids a chat cannot use, keeping the provider's order.
func ChatModels(ids []string) []string {
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if id == "" || isNonChat(id) {
			continue
		}
		out = append(out, id)
	}
	return out
}

// DefaultModel picks the model the harness starts on: the first entry of a known
// chat family, else the first usable id. Empty when there is nothing to offer.
func DefaultModel(ids []string) string {
	usable := ChatModels(ids)
	if len(usable) == 0 {
		return ""
	}
	for _, family := range chatFamilies {
		for _, id := range usable {
			if strings.Contains(strings.ToLower(id), family) {
				return id
			}
		}
	}
	return usable[0]
}

func isNonChat(id string) bool {
	low := strings.ToLower(id)
	for _, marker := range nonChatMarkers {
		if strings.Contains(low, marker) {
			return true
		}
	}
	return false
}
