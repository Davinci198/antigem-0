package ws

import "testing"

func TestAllowedOrigin(t *testing.T) {
	cases := []struct {
		origin string
		want   bool
	}{
		{"", true}, // native app / webview: no Origin header
		{"http://localhost:1236", true},
		{"http://127.0.0.1:8022", true},
		{"ws://[::1]:1236", true},
		{"https://localhost", true},
		{"http://evil.example", false},
		{"https://10.0.0.5:1236", false},
		{"http://127.0.0.1.evil.example", false}, // suffix tricks must not pass
		{"null", false},
		{"file://", false},
		{"://bad", false},
	}
	for _, c := range cases {
		if got := AllowedOrigin(c.origin); got != c.want {
			t.Errorf("AllowedOrigin(%q) = %v, want %v", c.origin, got, c.want)
		}
	}
}
