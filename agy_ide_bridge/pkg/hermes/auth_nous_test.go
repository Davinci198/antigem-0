package hermes

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNousAuthStatusReadsAuthStore(t *testing.T) {
	home := t.TempDir()
	m := &Manager{homeDir: home}
	request := func() map[string]interface{} {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/api/hermes/auth/nous/status", nil)
		res := httptest.NewRecorder()
		m.ServeHTTP(res, req)
		if res.Code != http.StatusOK {
			t.Fatalf("status endpoint returned %d: %s", res.Code, res.Body.String())
		}
		var body map[string]interface{}
		if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		return body
	}
	if got := request()["logged_in"]; got != false {
		t.Fatalf("logged_in without auth store = %v", got)
	}
	authDir := filepath.Join(home, ".hermes")
	if err := os.MkdirAll(authDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(authDir, "auth.json"), []byte(`{"providers":{"nous":{"access_token":"token"}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := request()["logged_in"]; got != true {
		t.Fatalf("logged_in with providers.nous = %v", got)
	}
}

func TestNousLoginParsesDeviceInstructions(t *testing.T) {
	login := &nousLoginSession{ready: make(chan struct{})}
	login.consumeLine("  1. Open: https://portal.nousresearch.com/manage-subscription?user_code=ABCD-1234")
	login.consumeLine("  2. If prompted, enter code: ABCD-1234")
	<-login.ready
	snapshot := login.snapshot()
	if snapshot.URL != "https://portal.nousresearch.com/manage-subscription?user_code=ABCD-1234" {
		t.Fatalf("parsed URL = %q", snapshot.URL)
	}
	if snapshot.Code != "ABCD-1234" {
		t.Fatalf("parsed code = %q", snapshot.Code)
	}
}

func TestCreateRoutesNousToBuiltin(t *testing.T) {
	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, ".hermes"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".hermes", "auth.json"), []byte(`{"providers":{"nous":{"access_token":"token"}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	m := &Manager{homeDir: home, sessions: make(map[string]*Session)}
	body := `{"conversation_id":"nous-test-session","model":"nous/model-test","provider_id":"nous"}`
	req := httptest.NewRequest(http.MethodPost, "/api/hermes/sessions", strings.NewReader(body))
	res := httptest.NewRecorder()
	m.ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("create returned %d: %s", res.Code, res.Body.String())
	}
	s := m.session("nous-test-session")
	s.mu.Lock()
	provider, baseURL, apiKey := s.gwProvider, s.gwBaseURL, s.gwAPIKey
	s.mu.Unlock()
	if provider != "nous" {
		t.Fatalf("gwProvider = %q, want nous", provider)
	}
	if baseURL != "" || apiKey != "" {
		t.Fatalf("Nous builtin must not carry endpoint or key: base=%q key=%q", baseURL, apiKey)
	}
}

// A Nous pick on a chat that has no gateway session yet must land on the
// session itself (model + builtin provider, no endpoint or key): the next turn
// creates the gateway session from these fields, and that is the only path that
// ever ran a Nous model correctly.
func TestNousModelPickPersistsForTheNextTurn(t *testing.T) {
	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, ".hermes"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".hermes", "auth.json"),
		[]byte(`{"providers":{"nous":{"access_token":"token"}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	m := &Manager{homeDir: home, sessions: make(map[string]*Session)}
	id := "nous-pick-session"
	m.session(id)

	body := `{"model":"meituan/longcat-2.5:free","provider_id":"nous"}`
	req := httptest.NewRequest(http.MethodPost, "/api/hermes/sessions/"+id+"/model", strings.NewReader(body))
	res := httptest.NewRecorder()
	m.ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("model pick returned %d: %s", res.Code, res.Body.String())
	}
	var payload map[string]interface{}
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["deferred"] != true {
		t.Fatalf("a chat without a gateway session must defer to create, got %v", payload)
	}

	s := m.session(id)
	s.mu.Lock()
	model, provider, baseURL, apiKey := s.gwModel, s.gwProvider, s.gwBaseURL, s.gwAPIKey
	s.mu.Unlock()
	if model != "meituan/longcat-2.5:free" || provider != "nous" {
		t.Fatalf("stored model/provider = %q/%q", model, provider)
	}
	if baseURL != "" || apiKey != "" {
		t.Fatalf("Nous builtin must not carry endpoint or key: base=%q key=%q", baseURL, apiKey)
	}
}
