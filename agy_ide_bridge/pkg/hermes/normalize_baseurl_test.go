package hermes

import "testing"

func TestNormalizeBaseURL(t *testing.T) {
	cases := map[string]string{
		"https://integrate.api.nvidia.com":    "https://integrate.api.nvidia.com/v1",
		"https://integrate.api.nvidia.com/":   "https://integrate.api.nvidia.com/v1",
		"https://integrate.api.nvidia.com/v1": "https://integrate.api.nvidia.com/v1",
		"https://openrouter.ai/api":           "https://openrouter.ai/api",
		"https://vyceai.com":                  "https://vyceai.com/v1",
		"https://x.dev/v1/chat/completions":   "https://x.dev/v1",
		"":                                    "",
	}
	for in, want := range cases {
		if got := normalizeBaseURL(in); got != want {
			t.Errorf("normalizeBaseURL(%q) = %q, want %q", in, got, want)
		}
	}
}
